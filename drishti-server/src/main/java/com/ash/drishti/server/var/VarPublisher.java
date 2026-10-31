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

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the official end-of-day results to {@code <root>/risk-results/var-official/} (VAR_GUIDE.md section 19, docs/contracts/README.md
 * section 11) for other systems: one row per node of every hierarchy per business date, appended as one commit per capture run through
 * an {@link com.ash.drishti.server.official.OfficialSink}: a Delta table (the default) or an Iceberg table
 * ({@code drishti.var.official.format: iceberg}), the root a folder or {@code s3://} (2.9.5). The table is append-only ({@code delta.appendOnly}): a restated date adds rows with the next
 * {@code RESULT_VERSION}, nothing is overwritten. Its log and removed files are kept for the retention years (7 by default), so its
 * time travel reaches back that far. No engine reads it back (a guard test fails the build when a connector points there).
 *
 * <p>Beside it (2.9.4, {@code drishti.var.official.jsonl}, on by default), each published date is also written as JSON Lines, one row a
 * line (the Delta row's document): {@code <root>/risk-results/jsonl/var-official/<yyyy-MM-dd>/var-official-v<table version>.jsonl}, one
 * file per commit, so a restatement is a new file and nothing is overwritten.
 */
final class VarPublisher {

    /** The contract version the rows carry (VAR_GUIDE.md section 23.4, the change table). */
    static final String CONTRACT_VERSION = "1.4";                   // 1.4 (2.9.7): HORIZON and METHOD
    static final String FOLDER = "risk-results";
    static final String TABLE = "var-official";

    /** The published columns and their types, in order (VAR_GUIDE.md section 19; BUSINESS_DATE is the partition column business_date). */
    static final Map<String, String> COLUMNS = columns();

    private final com.ash.drishti.server.official.OfficialSink sink;
    private final boolean jsonl;

    VarPublisher(Path root, int retentionYears) {
        this(root, retentionYears, true);
    }

    VarPublisher(Path root, int retentionYears, boolean jsonl) {
        this(com.ash.drishti.server.official.OfficialSink.of("delta", root == null ? null : root.toString(), Map.of(), retentionYears), jsonl);
    }

    /** Publishes through {@code sink} (Delta or Iceberg, a folder or {@code s3://}; null: nothing is published). */
    VarPublisher(com.ash.drishti.server.official.OfficialSink sink, boolean jsonl) {
        this.sink = sink;
        this.jsonl = jsonl;
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
        c.put("SCENARIO_WINDOW", "long");                              // 2.9.4: the scenarios used (the newest N of the set)
        for (String s : List.of("STANDALONE_SUM", "DIVERSIFICATION", "HYPO_PNL", "LIMIT", "LIMIT_USE")) {
            c.put(s, "double");
        }
        for (String s : List.of("TRADE_COUNT", "POSITION_COUNT", "EXCEPTIONS_250")) {
            c.put(s, "long");
        }
        c.put("TRAFFIC_LIGHT", "string");
        c.put("HORIZON", "string");                                    // 2.9.7: 1D (a future 10-day record: 10D)
        c.put("METHOD", "string");                                     // 2.9.7: null for 1-day; SQRT10, SUM or FULL for a 10-day record
        return java.util.Collections.unmodifiableMap(c);
    }

    /** Where the table is ({@code <root>/risk-results/var-official}, a folder or a URI), or null when nothing is published. */
    String where() {
        return sink == null ? null : sink.where(TABLE);
    }

    /** The sink, or null. */
    com.ash.drishti.server.official.OfficialSink sink() {
        return sink;
    }

    /** "Delta version 4" or "Iceberg sequence number 4": the version a publish returned, as the record says it. */
    String describe(long version) {
        return sink.describe(version);
    }

    /** Where a commit's JSON Lines go on a local root: {@code <root>/risk-results/jsonl/var-official/<date>/var-official-v<version>.jsonl}. */
    Path jsonlFile(LocalDate date, long version) {
        return com.ash.drishti.server.official.OfficialSink.folder(sink.root()).resolve(FOLDER).resolve("jsonl").resolve(TABLE)
                .resolve(date.toString()).resolve(TABLE + "-v" + version + ".jsonl");
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

    /** Appends one date's rows (each a map of {@link #COLUMNS}) in one commit; returns the version written. */
    long publish(LocalDate date, List<Map<String, Object>> rows) throws IOException {
        if (sink == null) {
            throw new IOException("nowhere to publish to (drishti.var.official.root)");
        }
        long version = sink.append(TABLE, date, COLUMNS, rows, r -> r.get("NODE_ID") + "@" + date + "#v" + r.get("RESULT_VERSION"));
        if (jsonl) {                                                // the same rows as JSON Lines beside the table (2.9.4)
            sink.writeJsonl(TABLE, date, version, docs(date, rows));
        }
        return version;
    }
}
