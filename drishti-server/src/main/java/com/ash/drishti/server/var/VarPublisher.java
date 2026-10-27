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
package com.ash.drishti.server.var;

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

/**
 * Publishes the official end-of-day results to {@code <root>/risk-results/var-official/} (VAR_GUIDE.md section 19, docs/contracts/README.md
 * section 11) for other systems: one row per node of every hierarchy per business date, appended as one Delta commit per capture run
 * by Drishti's own lake writer. The table is append-only ({@code delta.appendOnly}): a restated date adds rows with the next
 * {@code RESULT_VERSION}, nothing is overwritten. Its log and removed files are kept for the retention years (7 by default), so its
 * time travel reaches back that far. No engine reads it back (a guard test fails the build when a connector points there).
 *
 * <p>Beside it (2.9.4, {@code drishti.var.official.jsonl}, on by default), each published date is also written as JSON Lines, one row a
 * line (the Delta row's document): {@code <root>/risk-results/jsonl/var-official/<yyyy-MM-dd>/var-official-v<table version>.jsonl}, one
 * file per commit, so a restatement is a new file and nothing is overwritten.
 */
final class VarPublisher {

    /** The contract version the rows carry (VAR_GUIDE.md section 23.4, the change table). */
    static final String CONTRACT_VERSION = "1.3";
    static final String FOLDER = "risk-results";
    static final String TABLE = "var-official";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The published columns and their types, in order (VAR_GUIDE.md section 19; BUSINESS_DATE is the partition column business_date). */
    static final Map<String, String> COLUMNS = columns();

    private final Path table;
    private final int retentionDays;

    private final boolean jsonl;

    VarPublisher(Path root, int retentionYears) {
        this(root, retentionYears, true);
    }

    VarPublisher(Path root, int retentionYears, boolean jsonl) {
        this.jsonl = jsonl;
        this.table = root == null ? null : root.resolve(FOLDER).resolve(TABLE);
        this.retentionDays = retentionYears * 365 + (retentionYears + 3) / 4;           // whole years with their leap days
    }

    private static Map<String, String> columns() {
        Map<String, String> c = new LinkedHashMap<>();
        for (String s : List.of("HIERARCHY", "NODE_ID", "NODE_NAME", "PARENT_ID", "LEVEL", "CAPTURE_ID")) {
            c.put(s, "string");
        }
        c.put("RESULT_VERSION", "long");
        c.put("INPUT_VERSIONS", "string");
        c.put("COMPUTED_AT", "string");
        c.put("CONTRACT_VERSION", "string");
        for (String s : List.of("VAR", "ES", "CONFIDENCE", "ES_CONFIDENCE")) {
            c.put(s, "double");
        }
        c.put("SCENARIO_SET_ID", "string");
        for (String s : List.of("STANDALONE_SUM", "DIVERSIFICATION", "HYPO_PNL", "LIMIT", "LIMIT_USE")) {
            c.put(s, "double");
        }
        for (String s : List.of("TRADE_COUNT", "POSITION_COUNT", "EXCEPTIONS_250")) {
            c.put(s, "long");
        }
        c.put("TRAFFIC_LIGHT", "string");
        return java.util.Collections.unmodifiableMap(c);
    }

    /** Where the table is, or null when the root is not a local or shared folder (an object store: not published by this writer). */
    Path table() {
        return table;
    }

    /** The Delta properties the table is created with: 7 years of log and of removed files (time travel), append only. */
    Map<String, String> properties() {
        return Map.of("delta.logRetentionDuration", "interval " + retentionDays + " days",
                "delta.deletedFileRetentionDuration", "interval " + retentionDays + " days", "delta.appendOnly", "true");
    }

    /** Where a commit's JSON Lines go: {@code <root>/risk-results/jsonl/var-official/<date>/var-official-v<version>.jsonl}. */
    Path jsonlFile(LocalDate date, long version) {
        return table.getParent().resolve("jsonl").resolve(TABLE).resolve(date.toString()).resolve(TABLE + "-v" + version + ".jsonl");
    }

    private static List<Map<String, Object>> docs(LocalDate date, List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> doc = new LinkedHashMap<>();
            COLUMNS.keySet().forEach(c -> doc.put(c, r.get(c)));
            doc.put("BUSINESS_DATE", date.toString());
            out.add(doc);
        }
        return out;
    }

    /** Appends one date's rows (each a map of {@link #COLUMNS}) in one commit; returns the table version written. */
    long publish(LocalDate date, List<Map<String, Object>> rows) throws IOException {
        if (table == null) {
            throw new IOException("no local folder to publish to (drishti.var.official.root)");
        }
        Path staged = TempFiles.createFile("var-official-", ".rows");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(staged, StandardCharsets.UTF_8)) {
                for (Map<String, Object> r : rows) {
                    Map<String, Object> promoted = new LinkedHashMap<>();
                    COLUMNS.keySet().forEach(c -> promoted.put(c, r.get(c)));
                    String id = r.get("NODE_ID") + "@" + date + "#v" + r.get("RESULT_VERSION");
                    Map<String, Object> doc = new LinkedHashMap<>(promoted);
                    doc.put("BUSINESS_DATE", date.toString());
                    Map<String, Object> line = new LinkedHashMap<>();      // one {id, doc, columns} a line (LakeWriter)
                    line.put("id", id);
                    line.put("doc", JSON.writeValueAsString(doc));
                    line.put("columns", promoted);
                    w.write(JSON.writeValueAsString(line));
                    w.newLine();
                }
            }
            List<LakeManifest.Column> cols = new ArrayList<>(List.of(new LakeManifest.Column("id", "string"),
                    new LakeManifest.Column("doc", "string"), new LakeManifest.Column("business_date", "date")));
            COLUMNS.forEach((c, t) -> cols.add(new LakeManifest.Column(c, t)));
            LakeManifest m = new LakeManifest(table.toString(), List.of("business_date"), cols, List.copyOf(COLUMNS.keySet()), "append", 10_000,
                    List.of("id", "business_date", "NODE_ID", "RESULT_VERSION"),
                    List.of(new LakeManifest.Part(staged, rows.size(), Map.of("business_date", date.toString()))), properties());
            long version = new LakeWriter().write(m, x -> { }).version();
            if (jsonl) {                                            // the same rows as JSON Lines beside the table (2.9.4)
                com.ash.drishti.server.dist.JsonlWriter.writeFile(jsonlFile(date, version), docs(date, rows));
            }
            return version;
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
