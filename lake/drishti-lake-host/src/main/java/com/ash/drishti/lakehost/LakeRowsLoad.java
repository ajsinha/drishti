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
            if (!storage.isEmpty()) {
                target.set("storage-options", JSON.valueToTree(storage));
            }
            cfg.putObject("report").put("summary", summary.toString()).put("run-log", false);
            Path config = dir.resolve("load.yaml");
            Files.writeString(config, cfg.toString(), StandardCharsets.UTF_8);
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            int code = com.ash.lake.load.LakeLoad.run(DrishtiLake.runtime(), new String[] {"--config", config.toString(), "-q"},
                    new PrintStream(said, true, StandardCharsets.UTF_8));
            if (code != 0) {
                throw new IOException("loading " + rows.size() + " row(s) of " + kind + " " + date + " into " + table + " failed (" + code + "): "
                        + said.toString(StandardCharsets.UTF_8).strip());
            }
            JsonNode v = JSON.readTree(summary.toFile()).path("commit").path("dates").path(date.toString()).path("version");
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
