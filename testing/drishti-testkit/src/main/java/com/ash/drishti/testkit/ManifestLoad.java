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
package com.ash.drishti.testkit;

import com.ash.drishti.lakehost.LakeRowsLoad;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A table written by a test from a manifest ({@code {table, partitionColumns, columns, promoted, mode, parts: [{file, values: {business_date}}]}}, each
 * part file one {@code [id, doc, columns]} array or {@code {id, doc, columns}} object a line) through Drishti Lake's loader: the parts of a business date
 * are one load of that date ({@code overwrite-dates} replaces it, {@code append} adds to it). The fixtures that wrote Delta tables through Lake's manifest
 * writer (no longer on its public surface) call this instead.
 */
public final class ManifestLoad {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ManifestLoad() {
    }

    /** The call the fixtures made to Lake's command line ({@code lake write --manifest M}): the exit code, the failure's words on {@code err}. */
    public static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        try {
            run(Path.of(args[args.length - 1]));
            return 0;
        } catch (RuntimeException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    /** Loads the manifest file's tables; throws {@link IllegalStateException} with the loader's words when a load fails. */
    public static void run(Path manifest) {
        try {
            JsonNode m = JSON.readTree(manifest.toFile());
            String table = m.path("table").asText();
            Path t = Path.of(table).toAbsolutePath();
            String kind = t.getFileName().toString();
            String domain = t.getParent().getFileName().toString();
            List<String> promoted = new ArrayList<>();
            m.path("promoted").forEach(c -> promoted.add(c.asText()));
            List<String> vectors = new ArrayList<>();
            boolean[] float32 = new boolean[1];
            m.path("columns").forEach(c -> {
                if (c.path("type").asText().startsWith("vector")) {
                    vectors.add(c.path("name").asText());
                    float32[0] |= "vector32".equals(c.path("type").asText());
                }
            });
            promoted.removeAll(vectors);
            promoted.remove("_load_id");                                  // the loader writes the load's own id                                  // a vector is not a promoted column to the loader
            String mode = switch (m.path("mode").asText("append")) {
                case "overwrite-dates" -> "replace-date";
                case "overwrite-partitions", "append" -> m.path("mode").asText();             // the loader's own modes: passed through
                default -> m.path("mode").asText();
            };
            Map<LocalDate, List<List<LakeRowsLoad.Row>>> byDate = new TreeMap<>();      // a date's parts, each a file of its own
            for (JsonNode p : m.path("parts")) {
                LocalDate d = LocalDate.parse(p.path("values").path("business_date").asText());
                List<LakeRowsLoad.Row> rows = new ArrayList<>();
                byDate.computeIfAbsent(d, x -> new ArrayList<>()).add(rows);
                for (String line : Files.readAllLines(Path.of(p.path("file").asText()))) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode n = JSON.readTree(line);
                    JsonNode doc = n.isArray() ? n.get(1) : n.path("doc");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cols = JSON.convertValue(n.isArray() ? n.get(2) : n.path("columns"), LinkedHashMap.class);
                    if (cols != null) {
                        cols.remove("_load_id");
                    }
                    rows.add(new LakeRowsLoad.Row(n.isArray() ? n.get(0).asText() : n.path("id").asText(),
                            doc.isTextual() ? doc.asText() : doc.toString(), cols == null ? new LinkedHashMap<>() : cols));
                }
            }
            Map<String, String> properties = new LinkedHashMap<>();
            m.path("properties").fields().forEachRemaining(e -> properties.put(e.getKey(), e.getValue().asText()));
            Map<String, Object> layout = new LinkedHashMap<>();
            if (float32[0]) {
                layout.put("vector-type", "float32");
            }
            if (m.has("rowGroupMb")) {
                layout.put("row-group-mb", m.path("rowGroupMb").asDouble());
            }
            for (Map.Entry<LocalDate, List<List<LakeRowsLoad.Row>>> e : byDate.entrySet()) {
                String partMode = mode;
                for (List<LakeRowsLoad.Row> part : e.getValue()) {
                    LakeRowsLoad.load("delta", t.toString(), domain, kind, e.getKey(), partMode, part, promoted, vectors, properties, Map.of(), layout);
                    if (!"overwrite-partitions".equals(mode)) {
                        partMode = "append";                                      // the date's next parts are added to what the first wrote
                    }                                                             // (overwrite-partitions: each part replaces its own partition)
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(manifest + ": " + e.getMessage(), e);
        }
    }
}
