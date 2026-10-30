/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.bi.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * A JSON answer sent as an Apache Arrow IPC stream when the caller asks for one with {@code Accept: application/vnd.apache.arrow.stream}
 * (2.9.7, "Arrow where it pays", docs/guides/DATA_FORMATS.md section 6). The answer's big table becomes the Arrow record batch;
 * everything else in the answer (its columns, meta, totals, notes) rides in the schema's custom metadata as JSON, so the browser rebuilds
 * the very answer the JSON call gives, cell for cell. Three shapes of table:
 * <ul>
 *   <li>{@code rows}: a list of lists, named by the answer's {@code columns} (a BI answer: {@code {columns: [{name, type}], rows: [[..]]}});</li>
 *   <li>{@code records}: a list of objects, one column per key (a VaR contribution table); a key some objects leave out is listed in
 *       {@code absent}, so the rebuilt object leaves it out too;</li>
 *   <li>{@code arrays}: parallel lists of the same length (a P&amp;L path's values and its dates).</li>
 * </ul>
 * Cells are text (repeated text as a dictionary), 64-bit integers, doubles or booleans, each with nulls. A table holding anything else (an
 * object, a list, a column mixing text and numbers, a NaN) is not sent as Arrow: {@link #encode} answers empty and the caller sends
 * JSON. Metadata keys: {@code drishti.answer} (the rest of the answer, JSON) and {@code drishti.table} ({@code {shape, at | keys, absent}}).
 * The console's {@code core/arrow_answer.py} writes the same stream from a JSON answer with pyarrow.
 */
public final class ArrowAnswers {

    public static final String MEDIA = "application/vnd.apache.arrow.stream";
    public static final String ANSWER_KEY = "drishti.answer";
    public static final String TABLE_KEY = "drishti.table";

    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private ArrowAnswers() {}

    /** True when the {@code Accept} header names the Arrow stream (a browser that loaded Arrow JS, or a client that reads Arrow). */
    public static boolean wanted(String accept) {
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains(MEDIA);
    }

    /**
     * The answer as Arrow when the caller asked for it and the table can be written, else the JSON answer as before. {@code shape} is
     * {@code rows} or {@code records} with {@code at} the key of the table, or {@code arrays} with {@code at} the comma-separated keys.
     */
    public static ResponseEntity<Object> negotiate(String accept, Map<String, Object> answer, String shape, String at) {
        if (wanted(accept)) {
            Optional<byte[]> bytes = encode(answer, shape, at);
            if (bytes.isPresent()) {
                return ResponseEntity.ok().contentType(MediaType.parseMediaType(MEDIA)).header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header(HttpHeaders.VARY, HttpHeaders.ACCEPT).body(bytes.get());
            }
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT).body(answer);
    }

    /** The answer as an Arrow stream with the rest of it in the schema's metadata; empty when the table cannot be written as Arrow. */
    public static Optional<byte[]> encode(Map<String, Object> answer, String shape, String at) {
        if (answer == null) {
            return Optional.empty();
        }
        Map<String, Object> rest = new LinkedHashMap<>(answer);
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("shape", shape);
        List<ArrowIpcWriter.Column> cols = new ArrayList<>();
        int n;
        switch (shape) {
            case "rows" -> {
                n = rows(list(rest.remove(at)), answer.get("columns") instanceof List<?> l ? l : null, cols);
                table.put("at", at);
            }
            case "records" -> {
                List<String> absent = new ArrayList<>();
                n = records(list(rest.remove(at)), cols, absent);
                table.put("at", at);
                table.put("absent", absent);
            }
            case "arrays" -> {
                List<String> keys = List.of(at.split(","));
                n = -1;
                for (String k : keys) {
                    List<?> values = list(rest.remove(k));
                    if (values == null || (n >= 0 && values.size() != n)) {
                        return Optional.empty();
                    }
                    n = values.size();
                    ArrowIpcWriter.Column col = column(k, values.toArray());
                    if (col == null) {
                        return Optional.empty();
                    }
                    cols.add(col);
                }
                table.put("keys", keys);
            }
            default -> throw new IllegalArgumentException("shape is rows, records or arrays: " + shape);
        }
        if (n < 0) {
            return Optional.empty();
        }
        Map<String, String> meta = new LinkedHashMap<>();
        try {
            meta.put(ANSWER_KEY, JSON.writeValueAsString(rest));
            meta.put(TABLE_KEY, JSON.writeValueAsString(table));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        Set<Integer> dictionaries = new java.util.HashSet<>();
        Set<Integer> booleans = new java.util.HashSet<>();
        for (int c = 0; c < cols.size(); c++) {
            Object[] v = cols.get(c).values();
            if (java.util.Arrays.stream(v).anyMatch(Boolean.class::isInstance)) {
                booleans.add(c);
            } else if (cols.get(c).kind() == ArrowIpcWriter.Kind.TEXT && repeats(v)) {
                dictionaries.add(c);
            }
        }
        return Optional.of(ArrowIpcWriter.write(cols, n, meta, dictionaries, booleans));
    }

    /** A list of lists named by {@code declared} into {@code cols}; the row count, or -1 when it cannot be written. */
    private static int rows(List<?> rows, List<?> declared, List<ArrowIpcWriter.Column> cols) {
        if (rows == null || declared == null) {
            return -1;
        }
        int n = rows.size();
        List<List<?>> lists = new ArrayList<>(n);
        for (Object o : rows) {
            List<?> row = list(o);
            if (row == null || row.size() != declared.size()) {
                return -1;
            }
            lists.add(row);
        }
        for (int c = 0; c < declared.size(); c++) {
            Object[] v = new Object[n];
            for (int r = 0; r < n; r++) {
                v[r] = lists.get(r).get(c);
            }
            String name = declared.get(c) instanceof Map<?, ?> m ? String.valueOf(m.get("name")) : String.valueOf(declared.get(c));
            ArrowIpcWriter.Column col = column(name, v);
            if (col == null) {
                return -1;
            }
            cols.add(col);
        }
        return n;
    }

    /** A list of objects into one column per key ({@code absent}: the keys some objects leave out); the row count, or -1. */
    private static int records(List<?> rows, List<ArrowIpcWriter.Column> cols, List<String> absent) {
        if (rows == null) {
            return -1;
        }
        int n = rows.size();
        Set<String> keys = new LinkedHashSet<>();
        for (Object o : rows) {
            if (!(o instanceof Map<?, ?> m)) {
                return -1;
            }
            m.keySet().forEach(k -> keys.add(String.valueOf(k)));
        }
        for (String k : keys) {
            Object[] v = new Object[n];
            boolean missing = false;
            boolean nulls = false;
            for (int r = 0; r < n; r++) {
                Map<?, ?> m = (Map<?, ?>) rows.get(r);
                if (!m.containsKey(k)) {
                    missing = true;
                } else if (m.get(k) == null) {
                    nulls = true;
                }
                v[r] = m.get(k);
            }
            if (missing && nulls) {
                return -1;                                            // null and left out mean different things here: JSON keeps both
            }
            if (missing) {
                absent.add(k);
            }
            ArrowIpcWriter.Column col = column(k, v);
            if (col == null) {
                return -1;
            }
            cols.add(col);
        }
        return n;
    }

    /** One column of the values, typed by what they hold; null when they hold something not written as Arrow here. */
    static ArrowIpcWriter.Column column(String name, Object[] v) {
        boolean text = false;
        boolean integral = false;
        boolean real = false;
        boolean bool = false;
        for (Object o : v) {
            if (o == null) {
                continue;
            }
            if (o instanceof Boolean) {
                bool = true;
            } else if (o instanceof String) {
                text = true;
            } else if (o instanceof Long || o instanceof Integer || o instanceof Short || o instanceof Byte) {
                integral = true;
            } else if (o instanceof Double || o instanceof Float) {
                double d = ((Number) o).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return null;                                      // JSON has no NaN: the JSON answer stays the reference
                }
                real = true;
            } else {
                return null;                                          // a decimal, an object, a list: JSON keeps it exactly
            }
        }
        if (bool) {                                                   // true and false, one bit a cell; with anything else: JSON
            return text || integral || real ? null : new ArrowIpcWriter.Column(name, ArrowIpcWriter.Kind.INT64, v);
        }
        if (text && (integral || real)) {
            return null;
        }
        if (text || (!integral && !real)) {
            return new ArrowIpcWriter.Column(name, ArrowIpcWriter.Kind.TEXT, v);
        }
        if (real) {
            Object[] d = new Object[v.length];
            for (int i = 0; i < v.length; i++) {
                d[i] = v[i] == null ? null : ((Number) v[i]).doubleValue();
            }
            return new ArrowIpcWriter.Column(name, ArrowIpcWriter.Kind.FLOAT64, d);
        }
        return new ArrowIpcWriter.Column(name, ArrowIpcWriter.Kind.INT64, v);
    }

    /**
     * True when a text column repeats its values enough to be sent as a dictionary (each distinct value once, 4-byte indices): at most half
     * as many distinct values as cells, over at least 16 cells. Desks, books, currencies and dates are; trade ids are not.
     */
    static boolean repeats(Object[] v) {
        if (v.length < 16) {
            return false;
        }
        Set<Object> seen = new java.util.HashSet<>();
        for (Object o : v) {
            if (o != null && seen.add(o) && seen.size() > v.length / 2) {
                return false;
            }
        }
        return true;
    }

    /** A list, a Java array (a {@code long[]} series) as a list, or null. */
    private static List<?> list(Object o) {
        if (o instanceof List<?> l) {
            return l;
        }
        if (o != null && o.getClass().isArray()) {
            List<Object> out = new ArrayList<>(Array.getLength(o));
            for (int i = 0; i < Array.getLength(o); i++) {
                out.add(Array.get(o, i));
            }
            return out;
        }
        return null;
    }
}
