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
package com.ash.drishti.tables.catalog;

import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.TableDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tables a pack's kinds give (docs/architecture/ARCHITECTURE.md §28 section 3.6): never migrated, regenerated whenever the packs change, as BI's
 * pack-generated datasets {@code pack.<pack>.<kind>} were. One table {@code <pack>.<kind>} per kind (one row per entity and business date),
 * and one table per list a pack names ({@code tables: { arrays: { trade: [LEGS] } }} in {@code pack.yaml}) with that list as its grain
 * ({@code trading.trade_legs}). Read-only: an edit is refused; a table over it narrows it. The server describes each kind (its connector's
 * columns or its samples', references from the link graph) and hands the description here.
 */
public final class PackTables {

    /**
     * A kind as a table.
     *
     * @param pack the pack
     * @param kind the kind
     * @param version the pack's version
     * @param columns column name to its type, in order
     * @param references column name to the kind its values point at
     * @param meanings column name to its glossary term
     * @param live true when the kind's connector pushes (the table is then live by id)
     * @param arrays a list field to its elements' columns (name to type)
     */
    public record KindInfo(String pack, String kind, String version, Map<String, String> columns, Map<String, String> references,
            Map<String, String> meanings, boolean live, Map<String, Map<String, String>> arrays) {
        public KindInfo {
            columns = columns == null ? Map.of() : columns;
            references = references == null ? Map.of() : references;
            meanings = meanings == null ? Map.of() : meanings;
            arrays = arrays == null ? Map.of() : arrays;
        }
    }

    private PackTables() {}

    /** The tables of one kind: its own and one per list. */
    public static List<TableDef> generate(KindInfo k) {
        List<TableDef> out = new ArrayList<>();
        String domain = part(k.pack());
        String base = domain + "." + part(k.kind());
        Map<String, Object> about = new LinkedHashMap<>();
        about.put("title", k.kind() + " (pack " + k.pack() + ")");
        about.put("owner", "pack:" + k.pack());
        about.put("endorsement", "certified");
        Map<String, ColumnDef> columns = new LinkedHashMap<>();
        k.columns().forEach((name, type) -> columns.put(name, column(name, "$." + name, type, k)));
        TableDef.Materialize mat = k.live() ? new TableDef.Materialize(TableDef.Materialize.MODE_LIVE, null, null) : TableDef.Materialize.ON_LOAD;
        out.add(new TableDef(base, "pack." + k.pack() + "." + k.kind(), version(k.version()), about, new TableDef.Source(k.kind(), k.pack(), null, null, null,
                null, null, null, null, null, null), null, null, columns, k.live() ? List.of("id") : List.of(), null, "one row per " + k.kind(), mat, null, null,
                Map.of("on-load", true), List.of(), List.of(), List.of()));
        for (Map.Entry<String, Map<String, String>> a : k.arrays().entrySet()) {
            Map<String, ColumnDef> elements = new LinkedHashMap<>();
            elements.put("PARENT_ID", ColumnDef.ofPath("PARENT_ID", "$.id", "string"));
            a.getValue().forEach((name, type) -> elements.put(name, column(name, "@." + name, type, k)));
            Map<String, Object> aboutList = new LinkedHashMap<>(about);
            aboutList.put("title", k.kind() + " " + a.getKey() + " (pack " + k.pack() + ")");
            out.add(new TableDef(base + "_" + part(a.getKey()), null, version(k.version()), aboutList, new TableDef.Source(k.kind(), k.pack(), null, null,
                    null, null, null, null, null, null, null), "$." + a.getKey() + "[*]", null, elements, List.of("PARENT_ID", "index"), null,
                    "one row per element of " + a.getKey(), TableDef.Materialize.ON_LOAD, null, null, Map.of("on-load", true), List.of(), List.of(), List.of()));
        }
        return out;
    }

    private static ColumnDef column(String name, String path, String type, KindInfo k) {
        return new ColumnDef(name, path, null, null, type, k.references().containsKey(name) ? "dimension" : null, k.meanings().get(name),
                k.references().get(name), null, null, null, null, false, null, false, null, null);
    }

    private static String part(String s) {
        String n = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        return n.isEmpty() || !Character.isLetter(n.charAt(0)) ? "t_" + n : n;
    }

    private static String version(String v) {
        return v == null ? "1.0.0" : v.matches("\\d+") ? v + ".0.0" : v.matches("\\d+\\.\\d+") ? v + ".0" : v;
    }
}
