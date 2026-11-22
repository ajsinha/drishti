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
package com.ash.drishti.server;

import com.ash.lake.iceberg.table.IcebergAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * The lake fixtures' tables written to Iceberg too, from the very lines they write to Delta (2.9.5, the format equality tests): while
 * {@link #into} runs a fixture's write, every staged table it writes ({@code [id, doc, {columns}]} a line, by business date) is also sent
 * through {@link IcebergLoader} into {@code <iceberg root>/risk/<kind>} (each date replaced, as {@code overwrite-dates}), or, for an
 * {@code append} (an intraday batch), as one Iceberg append. A vector column (a list of numbers) becomes {@code list<double>}. The Delta
 * tables are written as before; nothing of a fixture's rows changes.
 */
public final class IcebergMirror {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<Path> TARGET = new ThreadLocal<>();

    private IcebergMirror() {
    }

    /** Runs {@code write} (a fixture's write to a Delta root) with its tables also written to {@code icebergRoot}; returns its result. */
    public static <T> T into(Path icebergRoot, Callable<T> write) throws Exception {
        Path was = TARGET.get();
        TARGET.set(icebergRoot.toAbsolutePath());
        try {
            return write.call();
        } finally {
            if (was == null) {
                TARGET.remove();
            } else {
                TARGET.set(was);
            }
        }
    }

    /** Whether a mirror is on (a fixture's write inside {@link #into}). */
    public static boolean on() {
        return TARGET.get() != null;
    }

    /** One table's staged lines by business date ({@code [id, doc, {columns}]}), into the mirror; {@code mode} as the Delta write's. */
    public static void table(String kind, Map<String, List<String>> byDay, String mode) throws Exception {
        Path root = TARGET.get();
        if (root == null) {
            return;
        }
        if ("append".equals(mode)) {
            for (Map.Entry<String, List<String>> e : byDay.entrySet()) {
                List<IcebergAppender.Row> rows = new ArrayList<>();
                Map<String, Boolean> promoted = new LinkedHashMap<>();
                Set<String> vectors = new LinkedHashSet<>();
                for (String line : e.getValue()) {
                    JsonNode a = JSON.readTree(line);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cols = JSON.convertValue(a.get(2), Map.class);
                    cols.forEach((c, v) -> {
                        if (v instanceof List<?>) {
                            vectors.add(c);
                        } else if (v != null) {
                            promoted.merge(c, v instanceof Number, (x, y) -> x && y);
                        } else {
                            promoted.putIfAbsent(c, false);
                        }
                    });
                    rows.add(new IcebergAppender.Row(a.get(0).asText(), a.get(1).asText(), cols));
                }
                vectors.forEach(promoted::remove);
                IcebergAppender.append(com.ash.drishti.lakehost.DrishtiLake.runtime(), Map.of("root", root.toString()), "risk", kind, LocalDate.parse(e.getKey()), rows, promoted, vectors, Map.of());
            }
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byDay.entrySet()) {
            for (String line : e.getValue()) {
                JsonNode a = JSON.readTree(line);
                ObjectNode l = JSON.createObjectNode();
                l.put("domain", "risk").put("kind", kind).put("id", a.get(0).asText()).put("date", e.getKey()).put("doc", a.get(1).asText());
                l.set("columns", a.get(2));
                lines.add(l.toString());
            }
        }
        Path work = Files.createDirectories(root.resolve("_work"));
        Path feed = Files.createTempDirectory(work, kind + "-");
        try {
            // the producer of the feed: one tar of <kind>/<YYYY-MM-DD>/ folders (or one dated file), so each row lands under its own
            // business date by the load rules (DATA_LAKE_GUIDE.md), never under the business today of an undated name
            // Drishti Lake's one loader (source.format: loader-rows), each business date replaced
            Set<String> promote = new LinkedHashSet<>();
            Set<String> vectors = new LinkedHashSet<>();
            Map<String, List<String>> byDate = new java.util.TreeMap<>();
            for (String line : lines) {
                JsonNode l = JSON.readTree(line);
                l.path("columns").fields().forEachRemaining(f -> (f.getValue().isArray() ? vectors : promote).add(f.getKey()));
                byDate.computeIfAbsent(l.path("date").asText(), d -> new ArrayList<>()).add(line);
            }
            promote.removeAll(vectors);
            for (Map.Entry<String, List<String>> d : byDate.entrySet()) {
                Files.write(feed.resolve(kind + "." + d.getKey() + ".jsonl"), d.getValue());
            }
            Path config = work.resolve(feed.getFileName() + ".yaml");
            Files.writeString(config, "source:\n  files: '" + feed + "/*.jsonl'\n  format: loader-rows\n"
                    + (promote.isEmpty() ? "" : "  promote: [" + String.join(", ", promote) + "]\n")
                    + (vectors.isEmpty() ? "" : "  vectors: [" + String.join(", ", vectors) + "]\n")
                    + "target:\n  format: iceberg\n  path: '" + root.resolve("risk").resolve(kind) + "'\n  domain: risk\n  kind: " + kind + "\n  mode: replace-date\n");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int code = com.ash.lake.load.LakeLoad.run(com.ash.drishti.lakehost.DrishtiLake.runtime(), new String[] {"--config", config.toString()},
                    new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
            Files.deleteIfExists(config);
            if (code != 0) {
                throw new IllegalStateException("the loader failed (" + code + "): " + out);
            }
        } finally {
            try (var all = Files.walk(feed)) {
                all.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
