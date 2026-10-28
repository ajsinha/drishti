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
package com.ash.drishti.server.official;

import com.ash.drishti.common.TempFiles;
import com.ash.drishti.deltalake.write.LakeManifest;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The official results as Delta tables (the default): one Delta commit per append by Drishti's own lake writer, the table created
 * {@code delta.appendOnly} with {@code retention} days of log and of removed files (time travel reaches back that far), partitioned by
 * {@code business_date}. A local root is written as before 2.9.5; an {@code s3://} root through drishti-lakeio, each commit file an
 * exclusive create, so of two writers committing at once one retries on the newer version ({@link LakeWriter#forTable}).
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
        Path staged = TempFiles.createFile(table + "-", ".rows");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(staged, StandardCharsets.UTF_8)) {
                for (Map<String, Object> r : rows) {
                    Map<String, Object> promoted = new LinkedHashMap<>();
                    columns.keySet().forEach(c -> promoted.put(c, r.get(c)));
                    Map<String, Object> doc = new LinkedHashMap<>(promoted);
                    doc.put("BUSINESS_DATE", date.toString());
                    Map<String, Object> line = new LinkedHashMap<>();      // one {id, doc, columns} a line (LakeWriter)
                    line.put("id", id.apply(r));
                    line.put("doc", JSON.writeValueAsString(doc));
                    line.put("columns", promoted);
                    w.write(JSON.writeValueAsString(line));
                    w.newLine();
                }
            }
            List<LakeManifest.Column> cols = new ArrayList<>(List.of(new LakeManifest.Column("id", "string"),
                    new LakeManifest.Column("doc", "string"), new LakeManifest.Column("business_date", "date")));
            columns.forEach((c, t) -> cols.add(new LakeManifest.Column(c, t)));
            List<String> stats = new ArrayList<>(List.of("id", "business_date"));
            for (String c : List.of("NODE_ID", "COUNTERPARTY", "RESULT_VERSION")) {
                if (columns.containsKey(c)) {
                    stats.add(c);
                }
            }
            String where = where(table);
            String path = OfficialSink.local(where) ? OfficialSink.folder(where).toString() : where;
            LakeManifest m = new LakeManifest(path, List.of("business_date"), cols, List.copyOf(columns.keySet()), "append", 10_000, stats,
                    List.of(new LakeManifest.Part(staged, rows.size(), Map.of("business_date", date.toString()))), properties());
            return LakeWriter.forTable(path, settings).write(m, x -> { }).version();
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
