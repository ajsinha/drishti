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
package com.ash.drishti.banking.official;

import com.ash.drishti.lakehost.LakeRowsLoad;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The official results as Delta tables (the default): one Delta commit per append by Drishti Lake's loader, the table created
 * {@code delta.appendOnly} with {@code retention} days of log and of removed files (time travel reaches back that far), partitioned by
 * {@code business_date}. A local root or an {@code s3://} root alike: each commit file is an exclusive create, so of two writers committing at once
 * one retries on the newer version (Drishti Lake's loader).
 */
final class DeltaSink implements OfficialSink {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String root;
    private final Map<String, String> settings;
    private final int retentionDays;

    DeltaSink(String root, Map<String, String> settings, int retentionDays) {
        this.root = root;
        this.settings = settings;
        this.retentionDays = retentionDays;
    }

    @Override
    public String format() {
        return "delta";
    }

    @Override
    public String root() {
        return root;
    }

    @Override
    public Map<String, String> settings() {
        return settings;
    }

    /** The Delta properties a table is created with: the retention of log and removed files, append only. */
    Map<String, String> properties() {
        return Map.of("delta.logRetentionDuration", "interval " + retentionDays + " days",
                "delta.deletedFileRetentionDuration", "interval " + retentionDays + " days", "delta.appendOnly", "true");
    }

    @Override
    public long append(String table, LocalDate date, Map<String, String> columns, List<Map<String, Object>> rows,
            Function<Map<String, Object>, String> id) throws IOException {
        sameFormat(table);
        List<LakeRowsLoad.Row> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> promoted = new LinkedHashMap<>();
            columns.keySet().forEach(c -> promoted.put(c, r.get(c)));
            Map<String, Object> doc = new LinkedHashMap<>(promoted);
            doc.put("BUSINESS_DATE", date.toString());
            out.add(new LakeRowsLoad.Row(id.apply(r), JSON.writeValueAsString(doc), promoted));
        }
        String where = where(table);
        String path = OfficialSink.local(where) ? OfficialSink.folder(where).toString() : where;
        return LakeRowsLoad.load("delta", path, FOLDER, table, date, "append", out, List.copyOf(columns.keySet()), List.of(), properties(), settings);
    }
}
