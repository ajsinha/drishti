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
package com.ash.drishti.tables.read;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.preview.Preview;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The flat virtual table offered for a table a data load created (VIRTUAL_TABLES.md section 10, V8; DATA_LOAD_RULES.md section 7): one
 * column per scalar field the sample's documents hold (a nested object's scalars one level down, {@code INDEX.NAME} as {@code INDEX_NAME}),
 * each typed from its values; the id field as the key when it is unique in the sample; each list of objects named as a grain the person
 * may choose instead (one row per element). Never saved by itself: the person accepts it (one click), edits it, or ignores it.
 */
public final class TableOffer {

    /** The most columns offered (the widest documents keep their first fields). */
    public static final int MAX_COLUMNS = 200;

    /**
     * What is offered.
     *
     * @param table the suggested definition (one row per document)
     * @param grains the lists of objects found, as grains ({@code $.LEGS[*]}), each with how many elements the sample held
     * @param documents how many documents the suggestion was read from
     */
    public record Offer(TableDef table, Map<String, Integer> grains, int documents) {}

    private TableOffer() {}

    /** The offer for {@code name} ({@code <domain>.<name>}) over {@code from}, read from {@code sample}. */
    public static Offer suggest(String name, TableDef.Source from, List<Preview.Doc> sample) {
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, Integer> grains = new LinkedHashMap<>();
        for (Preview.Doc d : sample) {
            if (d.doc() instanceof DataNode.Obj o) {
                fields(o, "$", "", types, grains, 0);
            }
        }
        Map<String, ColumnDef> columns = new LinkedHashMap<>();
        Map<String, String> paths = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : types.entrySet()) {
            if (columns.size() >= MAX_COLUMNS) {
                break;
            }
            String path = e.getKey();
            String column = column(path);
            if (TableDef.BUILT_INS.contains(column) || columns.containsKey(column)) {
                continue;
            }
            columns.put(column, ColumnDef.ofPath(column, path, e.getValue()));
            paths.put(column, path);
        }
        List<String> keys = new ArrayList<>();
        for (String k : columns.keySet()) {
            String l = k.toLowerCase(Locale.ROOT);
            if ((l.equals("id") || l.endsWith("_id")) && unique(sample, paths.get(k))) {
                keys.add(k);
                break;
            }
        }
        Map<String, Object> about = new LinkedHashMap<>();
        about.put("description", "Offered from a sample of " + sample.size() + " documents; review the columns before accepting.");
        TableDef t = new TableDef(name, null, "1.0.0", about, from, null, null, columns, keys, null, null, TableDef.Materialize.NONE, null, null,
                Map.of(), List.of(), List.of(), List.of());
        return new Offer(t, grains, sample.size());
    }

    /** The offer read one row per element of {@code grain} instead: the element's scalars as {@code @} paths, the entity's id kept. */
    public static Offer atGrain(Offer offer, String grain, List<Preview.Doc> sample) {
        Map<String, String> types = new LinkedHashMap<>();
        String rel = grain.substring(2, grain.length() - 3);                  // $.LEGS[*] -> LEGS
        for (Preview.Doc d : sample) {
            DataNode list = d.doc().at(rel);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof DataNode.Obj o) {
                    fields(o, "@", "", types, new LinkedHashMap<>(), 1);
                }
            }
        }
        Map<String, ColumnDef> columns = new LinkedHashMap<>();
        offer.table().keys().forEach(k -> columns.put(k, offer.table().columns().get(k)));
        for (Map.Entry<String, String> e : types.entrySet()) {
            String column = column(e.getKey());
            if (columns.size() < MAX_COLUMNS && !TableDef.BUILT_INS.contains(column) && !columns.containsKey(column)) {
                columns.put(column, ColumnDef.ofPath(column, e.getKey(), e.getValue()));
            }
        }
        TableDef t = offer.table();
        TableDef at = new TableDef(t.name(), null, t.version(), t.about(), t.from(), grain, null, columns, List.of(), null, null, t.materialize(), null,
                null, Map.of(), List.of(), List.of(), List.of());
        return new Offer(at, offer.grains(), offer.documents());
    }

    private static void fields(DataNode.Obj o, String root, String prefix, Map<String, String> types, Map<String, Integer> grains, int depth) {
        for (Map.Entry<String, DataNode> f : o.fields().entrySet()) {
            String path = root + "." + prefix + f.getKey();
            DataNode v = f.getValue();
            if (v instanceof DataNode.Obj inner) {
                if (depth < 1) {
                    fields(inner, root, prefix + f.getKey() + ".", types, grains, depth + 1);
                }
            } else if (v instanceof DataNode.Arr a) {
                if (a.size() > 0 && a.get(0) instanceof DataNode.Obj && prefix.isEmpty() && "$".equals(root)) {
                    grains.merge(path + "[*]", a.size(), Integer::sum);
                }
            } else if (v instanceof DataNode.Val val && val.value() != null) {
                types.merge(path, type(val.value()), TableOffer::wider);
            } else {
                types.putIfAbsent(path, null);
            }
        }
    }

    /** The column a path names: its parts after the root joined by {@code _}. */
    static String column(String path) {
        return path.substring(2).replace('.', '_');
    }

    static String type(Object v) {
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger) {
            return "integer";
        }
        if (v instanceof Number) {
            return "decimal";
        }
        String s = String.valueOf(v);
        if (s.length() == 10) {
            try {
                LocalDate.parse(s);
                return "date";
            } catch (DateTimeParseException e) {
                return "string";
            }
        }
        if (s.length() > 10 && s.charAt(10) == 'T') {
            try {
                OffsetDateTime.parse(s);
                return "timestamp";
            } catch (DateTimeParseException e) {
                return "string";
            }
        }
        return "string";
    }

    /** Two types seen for one path: the one both fit (integer and decimal give decimal; anything else that differs, text). */
    static String wider(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null || a.equals(b)) {
            return a;
        }
        Set<String> both = Set.of(a, b);
        return both.equals(Set.of("integer", "decimal")) ? "decimal" : "string";
    }

    private static boolean unique(List<Preview.Doc> sample, String path) {
        Set<String> seen = new HashSet<>();
        String rel = path.substring(2);
        for (Preview.Doc d : sample) {
            DataNode v = d.doc().at(rel);
            if (v == null || v.isNull() || !seen.add(d.businessDate() + "|" + v.asText())) {
                return false;
            }
        }
        return !seen.isEmpty();
    }
}
