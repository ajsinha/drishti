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
package com.ash.drishti.server.flight;

import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Answers of the query layer as tables Flight sends: the engine's result columns, and rows of objects (the Data view's reader). */
final class FlightTables {

    private FlightTables() {}

    /** The engine's result columns (text, 64-bit integers, doubles; nulls kept) as a table. */
    static ArrowTable of(List<ArrowIpcWriter.Column> columns, int rows) {
        ArrowTable.Builder b = ArrowTable.builder();
        for (ArrowIpcWriter.Column c : columns) {
            Object[] v = c.values();
            BitSet nulls = new BitSet(rows);
            for (int i = 0; i < rows; i++) {
                if (v[i] == null) {
                    nulls.set(i);
                }
            }
            switch (c.kind()) {
                case INT64 -> {
                    long[] d = new long[rows];
                    for (int i = 0; i < rows; i++) {
                        d[i] = v[i] == null ? 0 : ((Number) v[i]).longValue();
                    }
                    b.column(new ArrowTable.Column(c.name(), ArrowTable.Type.INT64, 1, d, nulls));
                }
                case FLOAT64 -> {
                    double[] d = new double[rows];
                    for (int i = 0; i < rows; i++) {
                        d[i] = v[i] == null ? 0 : ((Number) v[i]).doubleValue();
                    }
                    b.column(new ArrowTable.Column(c.name(), ArrowTable.Type.FLOAT64, 1, d, nulls));
                }
                case TEXT -> {
                    String[] d = new String[rows];
                    for (int i = 0; i < rows; i++) {
                        d[i] = v[i] == null ? null : String.valueOf(v[i]);
                    }
                    b.column(new ArrowTable.Column(c.name(), ArrowTable.Type.UTF8, 1, d, nulls));
                }
            }
        }
        return b.build();
    }

    /**
     * Rows of objects (the chosen columns in order) as a table: a column whose values are all whole numbers is INT64, all numbers FLOAT64,
     * all booleans BOOL, anything else text (a nested value as its JSON).
     */
    static ArrowTable ofRows(List<String> names, List<Map<String, Object>> rows) {
        ArrowTable.Builder b = ArrowTable.builder();
        int n = rows.size();
        for (String name : names) {
            Object[] v = new Object[n];
            boolean ints = true;
            boolean nums = true;
            boolean bools = true;
            BitSet nulls = new BitSet(n);
            for (int i = 0; i < n; i++) {
                Object o = rows.get(i).get(name);
                v[i] = o;
                if (o == null) {
                    nulls.set(i);
                    continue;
                }
                ints &= o instanceof Long || o instanceof Integer || o instanceof Short || o instanceof Byte;
                nums &= o instanceof Number;
                bools &= o instanceof Boolean;
            }
            if (nulls.cardinality() == n) {
                ints = false;
                nums = false;
                bools = false;
            }
            if (ints) {
                long[] d = new long[n];
                for (int i = 0; i < n; i++) {
                    d[i] = v[i] == null ? 0 : ((Number) v[i]).longValue();
                }
                b.column(new ArrowTable.Column(name, ArrowTable.Type.INT64, 1, d, nulls));
            } else if (nums) {
                double[] d = new double[n];
                for (int i = 0; i < n; i++) {
                    d[i] = v[i] == null ? 0 : ((Number) v[i]).doubleValue();
                }
                b.column(new ArrowTable.Column(name, ArrowTable.Type.FLOAT64, 1, d, nulls));
            } else if (bools) {
                boolean[] d = new boolean[n];
                for (int i = 0; i < n; i++) {
                    d[i] = Boolean.TRUE.equals(v[i]);
                }
                b.column(new ArrowTable.Column(name, ArrowTable.Type.BOOL, 1, d, nulls));
            } else {
                String[] d = new String[n];
                for (int i = 0; i < n; i++) {
                    d[i] = v[i] == null ? null : text(v[i]);
                }
                b.column(new ArrowTable.Column(name, ArrowTable.Type.UTF8, 1, d, nulls));
            }
        }
        return b.build();
    }

    private static String text(Object o) {
        if (o instanceof Map<?, ?> || o instanceof List<?>) {
            try {
                return JSON.writeValueAsString(o);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return String.valueOf(o);
            }
        }
        return String.valueOf(o);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** A small metadata map for a table's schema. */
    static Map<String, String> meta(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    /** The names of a list of rows' first row (a fallback when the chosen columns are not known). */
    static List<String> names(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
    }
}
