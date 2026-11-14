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
package com.ash.drishti.lakeload;

import com.ash.drishti.common.FileDates;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.io.Relocation;
import com.ash.drishti.lakeio.RangeReader;
import com.ash.drishti.lakeio.S3Settings;
import com.ash.drishti.lakeload.Inputs.InputFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The child kinds of a load (docs/guides/DATA_LAKE_GUIDE.md section 5.7): after the parent's table is loaded, each array the pack's
 * {@code ingest:} block declares a child kind ({@link ChildKinds}) is split off its parent's documents into rows of its own, written to a
 * scratch file {@code <child kind>.<business date>.jsonl} (the business date the parent's file was loaded under: {@link ParseWorker#decide}),
 * and loaded as a table of its own by this same loader, in the parent's format, with the pack's layout of the child kind. A load without
 * {@code source.pack} and {@code target.kind}, or whose kind has no child kind in the pack, is exactly what it was.
 */
final class ChildLoads {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChildLoads() {}

    /** Loads the child kinds of the load {@code cfg} made (its files {@code given}, else {@code source.files}); the worst exit code. */
    static int run(LoadConfig cfg, List<InputFile> given, PrintStream out, boolean json, boolean quiet) {
        ObjectNode tree = cfg.tree();
        String pack = tree == null ? null : tree.path("source").path("pack").asText(null);
        String kind = tree == null ? null : tree.path("target").path("kind").asText(null);
        if (pack == null || pack.isBlank() || kind == null || kind.isBlank() || cfg.report().dryRun()) {
            return LakeLoad.OK;
        }
        List<ChildKinds> kids;
        try {
            kids = ChildKinds.of(Path.of(pack), kind);
        } catch (IOException | RuntimeException e) {
            return LakeLoad.OK;                              // the load already read this pack; nothing declared here
        }
        if (kids.isEmpty()) {
            return LakeLoad.OK;
        }
        Path scratch = null;
        try {
            scratch = com.ash.drishti.common.TempFiles.createDir("drishti-children-");
            Map<String, Long> rows = split(cfg, given, kids, scratch);
            int worst = LakeLoad.OK;
            for (ChildKinds c : kids) {
                if (rows.getOrDefault(c.kind(), 0L) == 0) {
                    continue;
                }
                List<String> files = new ArrayList<>();
                try (Stream<Path> s = Files.list(scratch)) {
                    s.filter(p -> p.getFileName().toString().startsWith(c.kind() + ".")).sorted().forEach(p -> files.add(p.toString()));
                }
                if (!quiet) {
                    out.println("child kind " + c.kind() + ": " + rows.get(c.kind()) + " rows from " + c.field() + " of " + kind);
                }
                LoadConfig child = LoadConfig.of(childTree(tree, c, files), System::getenv);
                int code = LakeLoad.runOne(child, null, out, json, quiet, System.nanoTime()).code();
                worst = worse(worst, code);
                if (code == LakeLoad.FAILED || code == LakeLoad.LOCKED) {
                    break;
                }
            }
            return worst;
        } catch (IOException | InterruptedException | RuntimeException e) {
            out.println("lakeload: the child kinds of " + kind + ": " + e.getMessage());
            return LakeLoad.FAILED;
        } finally {
            if (scratch != null) {
                try (Stream<Path> s = Files.walk(scratch)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException e) {
                    // the temp folder is the operating system's to clear
                }
            }
        }
    }

    private static int worse(int a, int b) {
        int[] rank = {0, 3, 1, 2};                                  // OK < PARTIAL < LOCKED < FAILED (LakeLoad's codes)
        return rank[b] > rank[a] ? b : a;
    }

    /** The child's configuration: the parent's, over its own files, kind, id and layout (what is the parent's own is left out). */
    static ObjectNode childTree(ObjectNode parent, ChildKinds c, List<String> files) {
        ObjectNode t = parent.deepCopy();
        t.remove("datasets");
        ObjectNode s = t.with("source");
        for (String k : List.of("id", "date", "as-of", "promote", "vectors", "vector", "flatten", "partition-by", "latest-by", "dataset", "connector",
                "archive", "format")) {
            s.remove(k);
        }
        s.putArray("files").addAll(files.stream().map(f -> (JsonNode) t.textNode(f)).toList());
        s.put("recursive", false);
        s.put("date-from", "auto");
        ObjectNode tg = t.with("target");
        tg.remove("table");
        tg.remove("batch-id");
        tg.put("kind", c.kind());
        if (tg.hasNonNull("path")) {                              // an explicit table path: the child is its sibling
            String path = tg.get("path").asText();
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            tg.put("path", path.substring(0, slash + 1) + c.kind());
        }
        if (tg.has("layout") && tg.get("layout").isObject()) {
            ((ObjectNode) tg.get("layout")).remove("sort-by");
        }
        for (String k : List.of("summary", "manifest", "progress")) {
            if (t.path("report").hasNonNull(k)) {
                t.with("report").put(k, LoadConfig.named(t.path("report").get(k).asText(), c.kind()));
            }
        }
        if (t.path("rejects").hasNonNull("file")) {
            t.with("rejects").put("file", LoadConfig.named(t.path("rejects").get("file").asText(), c.kind()));
        }
        return t;
    }

    /** Writes the child rows of every parent document of the load to {@code scratch/<child>.<date>.jsonl}; {child kind: rows}. */
    private static Map<String, Long> split(LoadConfig cfg, List<InputFile> given, List<ChildKinds> kids, Path scratch)
            throws IOException, InterruptedException {
        Map<String, Long> rows = new LinkedHashMap<>();
        Map<String, BufferedWriter> writers = new HashMap<>();
        RowPlan plan = new RowPlan(cfg.source(), cfg.target().layout().sortBy());
        Meter meter = new Meter("children", 1);
        try (FileAccess io = new FileAccess(S3Settings.from(cfg.source().storage()), Relocation.none(), null)) {
            List<InputFile> files = given != null ? given : Inputs.resolve(cfg.source().files(), cfg.source().recursive(), io);
            Map<Archives.Member, InputFile> members = new HashMap<>();
            Map<Archives.Plan, List<Archives.Member>> byArchive = new LinkedHashMap<>();
            for (InputFile f : files) {
                if (f.member() != null) {
                    members.put(f.member(), f);
                    byArchive.computeIfAbsent(f.plan(), k -> new ArrayList<>()).add(f.member());
                }
            }
            for (InputFile f : files) {
                if (f.member() == null && !f.isArchive()) {
                    LocalDate day = ParseWorker.decide(plan, f, FileDates.today()).date();
                    String path = io.resolve(Inputs.uri(f.path()));
                    try (RangeReader r = io.storage(path).open(path, f.size());
                            InputStream in = decompress(f, new SourceReader.RangeStream(r, 0, r.length(), meter))) {
                        lines(in, kids, day, scratch, writers, rows);
                    }
                }
            }
            for (Map.Entry<Archives.Plan, List<Archives.Member>> e : byArchive.entrySet()) {
                Archives.read(io, e.getKey(), e.getValue(), cfg.archive().limits(), meter, 1 << 20, (m, in) -> {
                    LocalDate day = ParseWorker.decide(plan, members.get(m), FileDates.today()).date();
                    lines(in, kids, day, scratch, writers, rows);
                });
            }
        } finally {
            for (BufferedWriter w : writers.values()) {
                w.close();
            }
        }
        return rows;
    }

    private static InputStream decompress(InputFile f, InputStream raw) throws IOException {
        return switch (f.compression()) {
            case NONE -> raw;
            case GZIP -> new GzipMembers(raw, 1 << 20);
            case ZSTD -> new com.github.luben.zstd.ZstdInputStream(raw);
        };
    }

    private static void lines(InputStream in, List<ChildKinds> kids, LocalDate day, Path scratch, Map<String, BufferedWriter> writers,
            Map<String, Long> rows) throws IOException {
        if (day == null) {
            return;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode doc;
            try {
                doc = JSON.readTree(line);
            } catch (IOException e) {
                continue;                                          // a bad line: the parent's load rejected it already
            }
            if (doc == null || !doc.isObject()) {
                continue;
            }
            for (ChildKinds c : kids) {
                for (ObjectNode row : c.explode(doc)) {
                    String name = c.kind() + "." + day + ".jsonl";
                    BufferedWriter w = writers.get(name);
                    if (w == null) {
                        w = Files.newBufferedWriter(scratch.resolve(name), StandardCharsets.UTF_8);
                        writers.put(name, w);
                    }
                    w.write(JSON.writeValueAsString(row));
                    w.write('\n');
                    rows.merge(c.kind(), 1L, Long::sum);
                }
            }
        }
    }
}
