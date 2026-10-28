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

import java.io.IOException;
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

    /** The published columns of the node table, in order (BUSINESS_DATE is the partition column business_date). */
    static final Map<String, String> COLUMNS = columns(false);
    /** The counterparty table's: the node table's and the counterparty's own. */
    static final Map<String, String> CPTY_COLUMNS = columns(true);

    private final com.ash.drishti.server.official.OfficialSink sink;
    private final boolean jsonl;

    ExposurePublisher(Path root, int retentionYears) {
        this(root, retentionYears, true);
    }

    ExposurePublisher(Path root, int retentionYears, boolean jsonl) {
        this(com.ash.drishti.server.official.OfficialSink.of("delta", root == null ? null : root.toString(), Map.of(), retentionYears), jsonl);
    }

    /** Publishes through {@code sink} (Delta or Iceberg, a folder or {@code s3://}; null: nothing is published). */
    ExposurePublisher(com.ash.drishti.server.official.OfficialSink sink, boolean jsonl) {
        this.sink = sink;
        this.jsonl = jsonl;
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

    /** Where the tables are ({@code <root>/risk-results}), or null when nothing is published. */
    String where() {
        return sink == null ? null : sink.root() + "/" + FOLDER;
    }

    /** {@code delta} or {@code iceberg}. */
    String format() {
        return sink == null ? "delta" : sink.format();
    }

    /** "Delta version 4" or "Iceberg sequence number 4". */
    String describe(long version) {
        return sink.describe(version);
    }

    /** Where a commit's JSON Lines go on a local root: {@code <root>/risk-results/jsonl/<table>/<date>/<table>-v<version>.jsonl}. */
    Path jsonlFile(String table, LocalDate date, long version) {
        return com.ash.drishti.server.official.OfficialSink.folder(sink.root()).resolve(FOLDER).resolve("jsonl").resolve(table)
                .resolve(date.toString()).resolve(table + "-v" + version + ".jsonl");
    }

    /** Appends a date's rows to one table ({@link #NODES} or {@link #CPTY}) in one commit; returns the version written. */
    long publish(String table, LocalDate date, List<Map<String, Object>> rows) throws IOException {
        if (sink == null) {
            throw new IOException("nowhere to publish to (drishti.ccr.exposure.official.root)");
        }
        Map<String, String> columns = table.equals(CPTY) ? CPTY_COLUMNS : COLUMNS;
        long version = sink.append(table, date, columns, rows,
                r -> r.get("HIERARCHY") + ":" + r.get("NODE_ID") + "@" + date + "#v" + r.get("RESULT_VERSION"));
        if (jsonl) {                                                // the same rows as JSON Lines beside the table (2.9.4)
            List<Map<String, Object>> docs = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> doc = new LinkedHashMap<>();
                columns.keySet().forEach(c -> doc.put(c, r.get(c)));
                doc.put("BUSINESS_DATE", date.toString());
                docs.add(doc);
            }
            sink.writeJsonl(table, date, version, docs);
        }
        return version;
    }
}
