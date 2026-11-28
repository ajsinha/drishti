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
package com.ash.drishti.lakehost;

import com.ash.drishti.common.TempFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Rows written into a Delta or Iceberg table the one way Drishti Lake offers a host: its loader ({@code LakeLoad.run}) with
 * {@code source.format: loader-rows}, {@code target.mode: append} (or another mode), the table's properties and the commit's version read from the
 * run's summary. Drishti's own writers of official results, the intraday stream and test fixtures go through here; none calls a Lake class that is not
 * on its public surface.
 */
public final class LakeRowsLoad {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.Set<String> SECRETS = java.util.Set.of("access-key", "secret-key", "session-token", "s3.access-key", "s3.secret-key",
            "s3.session-token");
    private static final Map<String, String> KEY_PROPERTIES = Map.of("s3.access-key", "aws.accessKeyId", "s3.secret-key", "aws.secretAccessKey",
            "s3.session-token", "aws.sessionToken");
    private static final Object KEYS = new Object();

    private LakeRowsLoad() {
    }

    /** One row as the loader takes it: the id, the document as JSON text and the promoted columns (a vector as a list of numbers). */
    public record Row(String id, String doc, Map<String, Object> columns) {
    }

    /**
     * Loads one business date's rows into the table at {@code table} (a folder or an {@code s3://} prefix), creating it on the first load; returns the
     * version the commit made (Delta's table version, Iceberg's sequence number).
     *
     * @param format {@code delta} or {@code iceberg}
     * @param mode {@code append}, {@code replace-date}, ...
     * @param promote the promoted columns, in order
     * @param vectors the columns the document holds as vectors
     * @param properties the table's properties (kept as they are when already set)
     * @param storage the storage settings ({@code s3.endpoint}, ...), may be empty
     */
    public static long load(String format, String table, String domain, String kind, LocalDate date, String mode, List<Row> rows, List<String> promote,
            List<String> vectors, Map<String, String> properties, Map<String, String> storage) throws IOException {
        return load(format, table, domain, kind, date, mode, rows, promote, vectors, properties, storage, Map.of());
    }

    /**
     * As {@link #load}, for a table named by its lake's layout: {@code root}, {@code domain} and the table's {@code folder} are joined by
     * {@code TableCatalogue} (the one join), or {@code uri} (a table the lake file gave its own location, when not blank) is the location as it is.
     */
    public static long loadAt(String format, String uri, String root, String domain, String folder, String kind, LocalDate date, String mode,
            List<Row> rows, List<String> promote, List<String> vectors, Map<String, String> properties, Map<String, String> storage,
            Map<String, Object> layout) throws IOException {
        String where = uri != null && !uri.isBlank() ? uri : com.ash.drishti.api.resolution.TableCatalogue.join(root, domain, folder);
        return load(format, where, domain, kind, date, mode, rows, promote, vectors, properties, storage, layout);
    }

    /** As {@link #load(String, String, String, String, LocalDate, String, List, List, List, Map, Map)} with the loader's {@code target.layout} settings (a row group size, say). */
    public static long load(String format, String table, String domain, String kind, LocalDate date, String mode, List<Row> rows, List<String> promote,
            List<String> vectors, Map<String, String> properties, Map<String, String> storage, Map<String, Object> layout) throws IOException {
        Path dir = TempFiles.createDir("lake-rows-");
        try {
            StringBuilder lines = new StringBuilder();
            for (Row r : rows) {
                ObjectNode n = JSON.createObjectNode().put("domain", domain).put("kind", kind).put("id", r.id()).put("date", date.toString());
                n.put("doc", r.doc());
                n.set("columns", JSON.valueToTree(r.columns() == null ? Map.of() : r.columns()));
                lines.append(n).append('\n');
            }
            Files.writeString(dir.resolve(kind + "." + date + ".jsonl"), lines.toString(), StandardCharsets.UTF_8);
            Path summary = dir.resolve("summary.json");
            ObjectNode cfg = JSON.createObjectNode();
            ObjectNode source = cfg.putObject("source");
            source.put("files", dir.resolve(kind + "." + date + ".jsonl").toString()).put("format", "loader-rows");
            source.set("promote", JSON.valueToTree(promote));
            if (!vectors.isEmpty()) {
                source.set("vectors", JSON.valueToTree(vectors));
            }
            ObjectNode target = cfg.putObject("target");
            target.put("format", format).put("path", table).put("domain", domain).put("kind", kind).put("mode", mode);
            if (!properties.isEmpty()) {
                target.set("properties", JSON.valueToTree(properties));
            }
            if (!layout.isEmpty()) {
                target.set("layout", JSON.valueToTree(layout));
            }
            Map<String, String> options = new java.util.LinkedHashMap<>();
            storage.forEach((k, v) -> {
                if (!SECRETS.contains(k)) {
                    options.put(k, v);                             // the loader's file never holds a key
                }
            });
            if (!options.isEmpty()) {
                target.set("storage-options", JSON.valueToTree(options));
            }
            cfg.putObject("report").put("summary", summary.toString()).put("run-log", false);
            Path config = dir.resolve("load.yaml");
            Files.writeString(config, cfg.toString(), StandardCharsets.UTF_8);
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            int code;
            synchronized (KEYS) {                                  // a key a caller gives reaches the loader by the AWS default chain (system properties) for this load
                Map<String, String> was = new java.util.HashMap<>();
                for (Map.Entry<String, String> e : KEY_PROPERTIES.entrySet()) {
                    String v = storage.get(e.getKey());
                    if (v != null && !v.isBlank()) {
                        was.put(e.getValue(), System.getProperty(e.getValue()));
                        System.setProperty(e.getValue(), v);
                    }
                }
                try {
                    code = com.ash.lake.load.LakeLoad.run(DrishtiLake.runtime(), new String[] {"--config", config.toString(), "--no-lock", "-q"},
                            new PrintStream(said, true, StandardCharsets.UTF_8));      // no lake lock: writers at once commit optimistically, as every append does
                } finally {
                    was.forEach((k, v) -> {
                        if (v == null) {
                            System.clearProperty(k);
                        } else {
                            System.setProperty(k, v);
                        }
                    });
                }
            }
            if (code != 0) {
                throw new IOException("loading " + rows.size() + " row(s) of " + kind + " " + date + " into " + table + " failed (" + code + "): "
                        + said.toString(StandardCharsets.UTF_8).strip());
            }
            JsonNode v = JSON.readTree(java.nio.file.Files.readAllBytes(summary)).path("commit").path("dates").path(date.toString()).path("version");
            if (!v.isNumber()) {
                throw new IOException("the loader's summary names no version for " + kind + " " + date + " (" + table + ")");
            }
            return v.asLong();
        } finally {
            try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }
}
