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

import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.lakeio.S3Settings;
import com.ash.drishti.lakeio.S3Storage;
import com.ash.drishti.server.dist.JsonlWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Where the official end-of-day results go (VAR_GUIDE.md section 19, EXPOSURE_GUIDE.md's official records; 2.9.5): the tables
 * {@code <root>/risk-results/<table>} as <b>Delta</b> (the default, {@link DeltaSink}) or <b>Iceberg</b> ({@link IcebergSink}), by
 * configuration ({@code drishti.var.official.format}, {@code drishti.ccr.exposure.official.format}), the root a local or shared folder
 * or {@code s3://bucket/prefix} (credentials from the {@code s3.*} settings, else the AWS default chain). Every write is an append:
 * nothing is overwritten (a restated date adds rows with the next result version). Beside each table, each commit's rows as JSON Lines
 * at {@code <root>/risk-results/jsonl/<table>/<date>/<table>-v<version>.jsonl}, on the same storage. Thread-safe per call.
 */
public interface OfficialSink {

    String FOLDER = "risk-results";

    /** The format's name: {@code delta} or {@code iceberg}. */
    String format();

    /** The root the results go under (without a trailing slash). */
    String root();

    /** The table's location: {@code <root>/risk-results/<table>}. */
    default String where(String table) {
        return root() + "/" + FOLDER + "/" + table;
    }

    /** How the version an append returned is said: "Delta version 4", "Iceberg sequence number 4". */
    default String describe(long version) {
        return ("iceberg".equals(format()) ? "Iceberg sequence number " : "Delta version ") + version;
    }

    /**
     * Appends one date's rows to the table in one commit, creating the table on the first write; returns the version written (Delta's
     * table version, Iceberg's sequence number: both grow with every commit).
     *
     * @param columns the published columns and their types ({@code string}, {@code long}, {@code double}), in order
     * @param rows the rows, each a map of {@code columns}
     * @param id a row's id in the table
     */
    long append(String table, LocalDate date, Map<String, String> columns, List<Map<String, Object>> rows,
            Function<Map<String, Object>, String> id) throws IOException;

    /** The JSON Lines of one commit beside the table: written whole, on the sink's storage. */
    default void writeJsonl(String table, LocalDate date, long version, List<? extends Map<String, ?>> docs) throws IOException {
        String path = root() + "/" + FOLDER + "/jsonl/" + table + "/" + date + "/" + table + "-v" + version + ".jsonl";
        if (LocalPaths.isLocal(path)) {
            JsonlWriter.writeFile(java.nio.file.Path.of(LocalPaths.toLocal(path, LocalPaths.WINDOWS)), docs);
            return;
        }
        StringBuilder b = new StringBuilder();
        for (Map<String, ?> d : docs) {
            b.append(JsonlWriter.line(d)).append('\n');
        }
        S3Storage s3 = new S3Storage(S3Settings.from(settings()));
        try {
            s3.put(path, b.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            s3.close();
        }
    }

    /** The storage settings ({@code s3.endpoint}, {@code s3.region}, {@code s3.access-key}, {@code s3.secret-key}, …). */
    Map<String, String> settings();

    /**
     * The sink of {@code format} ({@code delta} when blank) at {@code root}; null when there is no root (nothing is published).
     *
     * @param retentionYears how long time travel reaches back (7 years for the risk results)
     */
    static OfficialSink of(String format, String root, Map<String, String> settings, int retentionYears) {
        if (root == null || root.isBlank()) {
            return null;
        }
        String r = root.strip().replaceAll("/+$", "");
        Map<String, String> s = settings == null ? Map.of() : Map.copyOf(settings);
        int days = retentionYears * 365 + (retentionYears + 3) / 4;                  // whole years with their leap days
        String f = format == null || format.isBlank() ? "delta" : format.strip().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "delta" -> new DeltaSink(r, s, days);
            case "iceberg" -> new IcebergSink(r, s, days);
            default -> throw new IllegalArgumentException("the official results' format is delta or iceberg, not '" + format + "'");
        };
    }

    /** Whether the root is a local or shared folder (a {@code file:} path or a plain one). */
    static boolean local(String root) {
        return LocalPaths.isLocal(root);
    }

    /** A local root as a folder path. */
    static java.nio.file.Path folder(String root) {
        return java.nio.file.Path.of(LocalPaths.toLocal(root, LocalPaths.WINDOWS));
    }
}
