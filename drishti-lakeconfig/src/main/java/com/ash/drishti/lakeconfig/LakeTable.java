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
package com.ash.drishti.lakeconfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One table of a lake as its lake file describes it (docs/architecture/LAKE_CONFIG.md section 3), the lake's {@code defaults:} already
 * applied. Every table is partitioned by {@code business_date} first, always: {@link #partitionBy()} names only what comes after it.
 *
 * @param domain      the domain ({@code trading} of {@code trading.trade})
 * @param table       the entry's name within its domain ({@code trade} of {@code trading.trade}; {@code trade_endur} of
 *                    {@code trading.trade_endur}, a second table of one kind, which then says {@code kind: trade})
 * @param kind        the kind ({@code trade}: the entry's {@code kind:}, else its {@code table})
 * @param path        the table's folder under the lake's root ({@code trading/trade_endur}; default {@code <domain>/<kind>})
 * @param format      delta or iceberg (the lake's unless the table says otherwise)
 * @param partitionBy the partition columns after {@code business_date} (at most four; Delta only)
 * @param promote     the promoted columns (a connector's {@code layout.<kind>.columns})
 * @param vectors     the vector fields ({@code layout.<kind>.vectors})
 * @param flatten     the nested fields written as columns (field path to column)
 * @param id          the id field of the kind's documents (a load's {@code --key}); null: {@code id}
 * @param date        the business-date field; null: the writer's own (a load's {@code --date} or {@code as-of})
 * @param latestBy    which of two rows of one id on one date wins; null: the writer's default
 * @param loadMode    {@code replace} (the default: a load replaces each business date it holds) or {@code append} (rows added, each load's
 *                    batch id recorded in the commit so a batch is never appended twice)
 * @param sortBy      the sort within a date ({@code id} is the row id)
 * @param layout      the file layout: {@code file-rows}, {@code row-group-rows}, {@code file-size-mb}, {@code row-group-mb},
 *                    {@code compression}, {@code vector-compression} (only those given)
 * @param retention   business dates or years kept ({@code 520d}, {@code 7y}); null: everything
 * @param maintenance compaction, vacuum or expire-snapshots age, checkpoint interval, statistics columns, as written
 * @param timeTravel  how long old versions stay readable; null: the format's default
 * @param properties  Delta or Iceberg table properties passed through as they are
 */
public record LakeTable(String domain, String table, String kind, String path, String format, List<String> partitionBy, List<String> promote,
        List<String> vectors, Map<String, String> flatten, String id, String date, String latestBy, String loadMode, List<String> sortBy,
        Map<String, String> layout, String retention, Map<String, String> maintenance, String timeTravel, Map<String, String> properties) {

    /** The partition column every table has, first. */
    public static final String BUSINESS_DATE = "business_date";
    /** The load mode when the table names none. */
    public static final String REPLACE = "replace";
    /** The load mode that adds rows, each batch committed once. */
    public static final String APPEND = "append";

    public LakeTable {
        partitionBy = List.copyOf(partitionBy);
        promote = List.copyOf(promote);
        vectors = List.copyOf(vectors);
        sortBy = List.copyOf(sortBy);
        flatten = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(flatten));
        layout = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(layout));
        maintenance = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(maintenance));
        properties = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /** {@code <domain>.<table>}, the table's name in its lake file. */
    public String name() {
        return domain + "." + table;
    }

    /** Every partition column in order: {@code business_date}, then {@link #partitionBy()}. */
    public List<String> partitionColumns() {
        List<String> out = new java.util.ArrayList<>();
        out.add(BUSINESS_DATE);
        out.addAll(partitionBy);
        return out;
    }

    /** Whether loads add rows (each batch once) rather than replace the dates they hold. */
    public boolean appends() {
        return APPEND.equals(loadMode);
    }

    /** The table's folder name: the last segment of {@link #path()} ({@code trade_endur}). */
    public String folder() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The folder the table sits in under the root: {@link #path()} without its last segment ({@code trading}); empty at the root. */
    public String parent() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** A layout value ({@code file-rows}) as a number, or {@code dflt}. */
    public long layoutNumber(String key, long dflt) {
        String v = layout.get(key);
        return v == null || v.isBlank() ? dflt : Long.parseLong(v.trim());
    }

    /** The table as plain maps and lists, for {@code drishti.py} (the JSON dump) and Admin -> Lakes. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name());
        m.put("domain", domain);
        m.put("table", table);
        m.put("kind", kind);
        m.put("path", path);
        m.put("format", format);
        m.put("partition-by", partitionBy);
        m.put("partition-columns", partitionColumns());
        m.put("promote", promote);
        m.put("vectors", vectors);
        m.put("flatten", flatten);
        m.put("id", id);
        m.put("date", date);
        m.put("latest-by", latestBy);
        m.put("load-mode", loadMode);
        m.put("sort-by", sortBy);
        m.put("layout", layout);
        m.put("retention", retention);
        m.put("maintenance", maintenance);
        m.put("time-travel", timeTravel);
        m.put("properties", properties);
        return m;
    }
}
