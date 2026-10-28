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
package com.ash.drishti.plugin.icebergext;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * How one kind is read from one external table (ICEBERG_EXTERNAL_CONNECTOR.md section 2), from the connector's settings
 * {@code tables.<kind>.*} (YAML nested under {@code tables:}, flattened by the server):
 *
 * @param table {@code namespace.table} (dots: namespace levels), or the table's name in the catalog's namespace
 * @param id the id columns (several are joined by {@code idSeparator}); empty: the table's identifier fields
 * @param date the business-date column (a date, or a timestamp with {@code dateFrom: day}); null: from the partition spec, else none
 * @param dateZone the zone a timestamptz's business date is taken in
 * @param mode {@code snapshot} (every entity every date) or {@code effective} (a row when the entity changes)
 * @param documentColumn a column holding the JSON document ({@code document: column:<name>}), or null: the row is the document
 * @param exclude columns left out of the document and the searchable fields
 * @param rename column to the name the document and the pack use
 * @param links column (after renaming) to the kind its values name
 * @param depth how deep nested structs are flattened into searchable fields
 */
record KindConfig(String kind, String table, List<String> id, String idSeparator, String date, boolean dateFromDay, ZoneId dateZone, String mode,
        String documentColumn, Set<String> exclude, Map<String, String> rename, Map<String, String> links, int depth) {

    boolean effective() {
        return "effective".equals(mode);
    }

    /** The name a column has in the document. */
    String nameOf(String column) {
        return rename.getOrDefault(column, column);
    }

    /** The namespace levels and the table's name ({@code risk_mart.trades}: [risk_mart], trades). */
    List<String> namespace() {
        int dot = table.lastIndexOf('.');
        return dot < 0 ? List.of() : Arrays.asList(table.substring(0, dot).split("\\."));
    }

    String tableName() {
        return table.substring(table.lastIndexOf('.') + 1);
    }

    /** The kinds of {@code tables.<kind>.*} in the settings. */
    static List<KindConfig> from(Map<String, String> s) {
        Set<String> kinds = new TreeSet<>();
        s.keySet().forEach(k -> {
            if (k.startsWith("tables.")) {
                String rest = k.substring(7);
                int dot = rest.indexOf('.');
                if (dot > 0) {
                    kinds.add(rest.substring(0, dot));
                }
            }
        });
        List<KindConfig> out = new ArrayList<>();
        for (String kind : kinds) {
            out.add(of(kind, s, "tables." + kind + ".", null));
        }
        return out;
    }

    /** One kind's settings under {@code prefix}; {@code table} when discovered ({@code tables: auto}). */
    static KindConfig of(String kind, Map<String, String> s, String prefix, String table) {
        String t = table != null ? table : s.getOrDefault(prefix + "table", kind).trim();
        Map<String, String> rename = new LinkedHashMap<>();
        Map<String, String> links = new LinkedHashMap<>();
        s.forEach((k, v) -> {
            if (k.startsWith(prefix + "columns.rename.")) {
                rename.put(k.substring((prefix + "columns.rename.").length()), v.trim());
            } else if (k.startsWith(prefix + "links.")) {
                links.put(k.substring((prefix + "links.").length()), v.trim());
            }
        });
        String document = s.getOrDefault(prefix + "document", "row").trim();
        String docColumn = document.startsWith("column:") ? document.substring(7).trim() : null;
        if (docColumn == null && !document.equals("row")) {
            throw new IllegalArgumentException("tables." + kind + ".document must be row or column:<name>, not " + document);
        }
        String mode = s.getOrDefault(prefix + "mode", "snapshot").trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("snapshot") && !mode.equals("effective")) {
            throw new IllegalArgumentException("tables." + kind + ".mode must be snapshot or effective, not " + mode);
        }
        String dateFrom = s.getOrDefault(prefix + "date-from", "").trim();
        if (!dateFrom.isEmpty() && !dateFrom.equalsIgnoreCase("day")) {
            throw new IllegalArgumentException("tables." + kind + ".date-from takes day (the business date of a timestamp), not " + dateFrom);
        }
        String date = s.getOrDefault(prefix + "date", "").trim();
        return new KindConfig(kind, t, list(s.getOrDefault(prefix + "id", "")), s.getOrDefault(prefix + "id-separator", ":"),
                date.isEmpty() ? null : date, !dateFrom.isEmpty(), ZoneId.of(s.getOrDefault(prefix + "date-zone", "UTC").trim()), mode, docColumn,
                new TreeSet<>(list(s.getOrDefault(prefix + "columns.exclude", ""))), rename, links,
                Integer.parseInt(s.getOrDefault(prefix + "depth", "2").trim()));
    }

    static List<String> list(String v) {
        List<String> out = new ArrayList<>();
        for (String p : v.replaceAll("^\\[|]$", "").split(",")) {
            if (!p.isBlank()) {
                out.add(p.trim());
            }
        }
        return out;
    }

    /** {@code risk_mart.trades} to a kind name: the table's name in lower case with {@code -} for {@code _}. */
    static String kindOf(String tableName) {
        return tableName.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
