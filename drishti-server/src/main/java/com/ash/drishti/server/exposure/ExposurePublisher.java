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
package com.ash.drishti.server.exposure;

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
 * Publishes the official end-of-day exposure results (EXPOSURE_GUIDE.md section 20, NSET.md section 9.5) for other systems, as VaR's
 * ({@code VarPublisher}): {@code <root>/risk-results/exposure-official/} one row per node of the MGMT and LEGAL hierarchies (figures over
 * the node's trades; no counterparty named), and {@code <root>/risk-results/exposure-official-cpty/} one row per netting set,
 * counterparty and connected group (restricted: it names counterparties). Each is appended as one Delta commit per capture by Drishti's
 * own lake writer, append-only: a restated date adds rows with the next {@code RESULT_VERSION}. Their logs and removed files are kept for
 * the retention years. No paths are published, and no engine reads these tables back.
 *
 * <p>Beside them (2.9.4, {@code drishti.ccr.exposure.official.jsonl}, on by default), each published date is also written as JSON Lines,
 * one row a line (the Delta row's document): {@code <root>/risk-results/jsonl/<table>/<yyyy-MM-dd>/<table>-v<table version>.jsonl}, one
 * file per commit.
 */
final class ExposurePublisher {

    /** The contract version the rows carry (EXPOSURE_GUIDE.md's change table). */
    static final String CONTRACT_VERSION = "1.1";
    static final String FOLDER = "risk-results";
    static final String NODES = "exposure-official";
    static final String CPTY = "exposure-official-cpty";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The published columns of the node table, in order (BUSINESS_DATE is the partition column business_date). */
    static final Map<String, String> COLUMNS = columns(false);
    /** The counterparty table's: the node table's and the counterparty's own. */
    static final Map<String, String> CPTY_COLUMNS = columns(true);

    private final Path root;
    private final int retentionDays;

    private final boolean jsonl;

    ExposurePublisher(Path root, int retentionYears) {
        this(root, retentionYears, true);
    }

    ExposurePublisher(Path root, int retentionYears, boolean jsonl) {
        this.jsonl = jsonl;
        this.root = root == null ? null : root.resolve(FOLDER);
        this.retentionDays = retentionYears * 365 + (retentionYears + 3) / 4;
    }

    private static Map<String, String> columns(boolean cpty) {
        Map<String, String> c = new LinkedHashMap<>();
        for (String s : List.of("HIERARCHY", "NODE_ID", "NODE_NAME", "PARENT_ID", "LEVEL")) {
            c.put(s, "string");
        }
        if (cpty) {
            c.put("COUNTERPARTY", "string");
            c.put("LEGAL_ENTITY", "string");
        }
        c.put("RESULT_VERSION", "long");
        c.put("INPUT_VERSIONS", "string");
        c.put("COMPUTED_AT", "string");
        c.put("CONTRACT_VERSION", "string");
        c.put("RUN_ID", "string");
        c.put("PATHS", "long");
        c.put("CALIBRATION", "string");
        for (String s : List.of("CE", "EPE", "EEPE", "PEAK_EE", "PEAK_PFE_95", "PEAK_PFE_99", "EAD_IMM")) {
            c.put(s, "double");
        }
        c.put("PEAK_PFE_DATE", "string");
        for (String s : List.of("PFE_95_1Y", "PFE_95_5Y", "EE_1Y", "EE_5Y")) {
            c.put(s, "double");
        }
        c.put("PROFILE", "string");
        c.put("NETTING_SETS", "long");
        c.put("TRADES", "long");
        c.put("FAILED_TRADES", "long");
        c.put("PARTIAL_SETS", "long");
        return java.util.Collections.unmodifiableMap(c);
    }

    /** The folder the tables are under, or null when the root is not a local or shared folder. */
    Path root() {
        return root;
    }

    Map<String, String> properties() {
        return Map.of("delta.logRetentionDuration", "interval " + retentionDays + " days",
                "delta.deletedFileRetentionDuration", "interval " + retentionDays + " days", "delta.appendOnly", "true");
    }

    /** Where a commit's JSON Lines go: {@code <root>/risk-results/jsonl/<table>/<date>/<table>-v<version>.jsonl}. */
    Path jsonlFile(String table, LocalDate date, long version) {
        return root.resolve("jsonl").resolve(table).resolve(date.toString()).resolve(table + "-v" + version + ".jsonl");
    }

    /** Appends a date's rows to one table ({@link #NODES} or {@link #CPTY}) in one commit; returns the table version written. */
    long publish(String table, LocalDate date, List<Map<String, Object>> rows) throws IOException {
        if (root == null) {
            throw new IOException("no local folder to publish to (drishti.ccr.exposure.official.root)");
        }
        Map<String, String> columns = table.equals(CPTY) ? CPTY_COLUMNS : COLUMNS;
        Path staged = TempFiles.createFile("exposure-official-", ".rows");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(staged, StandardCharsets.UTF_8)) {
                for (Map<String, Object> r : rows) {
                    Map<String, Object> promoted = new LinkedHashMap<>();
                    columns.keySet().forEach(c -> promoted.put(c, r.get(c)));
                    String id = r.get("HIERARCHY") + ":" + r.get("NODE_ID") + "@" + date + "#v" + r.get("RESULT_VERSION");
                    Map<String, Object> doc = new LinkedHashMap<>(promoted);
                    doc.put("BUSINESS_DATE", date.toString());
                    w.write(JSON.writeValueAsString(List.of(id, JSON.writeValueAsString(doc), promoted)));
                    w.newLine();
                }
            }
            List<LakeManifest.Column> cols = new ArrayList<>(List.of(new LakeManifest.Column("id", "string"),
                    new LakeManifest.Column("doc", "string"), new LakeManifest.Column("business_date", "date")));
            columns.forEach((c, t) -> cols.add(new LakeManifest.Column(c, t)));
            LakeManifest m = new LakeManifest(root.resolve(table).toString(), List.of("business_date"), cols, List.copyOf(columns.keySet()), "append",
                    10_000, List.of("id", "business_date", "NODE_ID", "RESULT_VERSION"),
                    List.of(new LakeManifest.Part(staged, rows.size(), Map.of("business_date", date.toString()))), properties());
            long version = new LakeWriter().write(m, x -> { }).version();
            if (jsonl) {                                            // the same rows as JSON Lines beside the table (2.9.4)
                List<Map<String, Object>> docs = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    columns.keySet().forEach(c -> doc.put(c, r.get(c)));
                    doc.put("BUSINESS_DATE", date.toString());
                    docs.add(doc);
                }
                com.ash.drishti.server.dist.JsonlWriter.writeFile(jsonlFile(table, date, version), docs);
            }
            return version;
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
