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

import com.ash.drishti.deltalake.write.LakeManifest;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * The demo lakes of some packs, built in-test from the committed test data exactly as {@code drishti.py lake demo} builds them: each
 * dataset of {@code data/testdata/testdata.yaml} for the packs named becomes {@code <lake>/<domain>/<kind>/}, the table the pack's
 * Delta connector reads (its {@code domain}, its {@code layout.<kind>.columns} promoted), keyed by the dataset's key, one partition per
 * business day. The days are the last three weekdays up to today, so a page opens "as of" today's business date (a weekend resolves to
 * the Friday) and the days before it. Built once per JVM into {@code target/test-lakes/<packs>}.
 */
final class PublicPackLakes {

    static final Path TESTDATA = Path.of("..", "data", "testdata");
    static final Path PACKS = Path.of("..", "config", "packs");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, Path> BUILT = new java.util.concurrent.ConcurrentHashMap<>();

    private PublicPackLakes() {}

    /** The business days written: the last three weekdays up to today. */
    static List<LocalDate> days() {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = LocalDate.now(java.time.ZoneId.of("America/New_York"));     // the server's business-date zone out.size() < 3; d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(0, d);
            }
        }
        return out;
    }

    /** The lake root holding the packs' tables (built on the first call). */
    static synchronized Path lake(String... packs) {
        String name = String.join("+", packs);
        return BUILT.computeIfAbsent(name, n -> {
            try {
                return build(Path.of("target", "test-lakes", n).toAbsolutePath(), List.of(packs));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static Path build(Path lake, List<String> packs) throws IOException {
        if (Files.exists(lake)) {
            try (var walk = Files.walk(lake)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Path stage = Files.createDirectories(lake.resolve("_stage"));
        JsonNode manifest = YAML.readTree(TESTDATA.resolve("testdata.yaml").toFile());
        for (JsonNode ds : manifest.path("datasets")) {
            String pack = ds.path("pack").asText();
            if (!packs.contains(pack) || !(ds.has("snapshot") || ds.has("samples"))) {
                continue;                                  // the sixty-day synthetic datasets are not needed here
            }
            String kind = ds.path("kind").asText();
            JsonNode connector = YAML.readTree(PACKS.resolve(pack).resolve("pack.yaml").toFile()).path("connectors").path(ds.path("connector").asText());
            JsonNode settings = connector.path("settings");
            List<String> promoted = new ArrayList<>();
            settings.path("layout").path(kind).path("columns").forEach(c -> promoted.add(c.asText()));
            TreeMap<String, JsonNode> docs = ds.has("snapshot")
                    ? snapshot(TESTDATA.resolve(ds.path("folder").asText()).resolve(ds.path("snapshot").asText()), ds.path("key").asText())
                    : samples(Path.of("..").resolve(ds.path("samples").asText()), kind);
            table(lake.resolve(settings.path("domain").asText(pack)).resolve(kind), stage.resolve(pack + "-" + kind + ".rows"), docs, promoted);
        }
        try (var walk = Files.walk(stage)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        return lake;
    }

    /** A snapshot's documents by id (its key field, as text). */
    private static TreeMap<String, JsonNode> snapshot(Path snapshot, String key) throws IOException {
        TreeMap<String, JsonNode> docs = new TreeMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(snapshot)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    JsonNode d = JSON.readTree(line);
                    JsonNode id = d.path(key);
                    docs.put(id.isNumber() && id.canConvertToLong() ? String.valueOf(id.asLong()) : id.asText(), d);
                }
            }
        }
        return docs;
    }

    /** A pack's sample documents of a kind by id (static reference data, as lake demo reads them), without their _meta. */
    private static TreeMap<String, JsonNode> samples(Path folder, String kind) throws IOException {
        TreeMap<String, JsonNode> docs = new TreeMap<>();
        for (Map.Entry<String, String> e : com.ash.drishti.common.PackSamples.documents(folder, kind, java.util.Set.of(), 0).entrySet()) {
            ObjectNode d = (ObjectNode) JSON.readTree(e.getValue());
            d.remove("_meta");
            docs.put(e.getKey(), d);
        }
        return docs;
    }

    /** One table: the documents, sorted by id, written for each day. */
    private static void table(Path table, Path rows, TreeMap<String, JsonNode> docs, List<String> promoted) throws IOException {
        Map<String, Boolean> numeric = new LinkedHashMap<>();
        for (String p : promoted) {
            boolean num = !p.matches("(?i).*(^|_)(id|ids|ref|refs|key)$");
            for (JsonNode d : docs.values()) {
                JsonNode v = d.get(p);
                if (v != null && !v.isNull() && !v.isNumber()) {
                    num = false;
                    break;
                }
            }
            numeric.put(p, num);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : docs.entrySet()) {
            ObjectNode cols = JSON.createObjectNode();
            for (String p : promoted) {
                JsonNode v = e.getValue().get(p);
                String column = p.replace(".", "__");
                if (v == null || v.isNull()) {
                    cols.putNull(column);
                } else if (numeric.get(p)) {
                    cols.put(column, v.asDouble());
                } else {
                    cols.put(column, v.isTextual() ? v.asText() : v.isBoolean() ? String.valueOf(v.asBoolean()) : JSON.writeValueAsString(v));
                }
            }
            lines.add(JSON.writeValueAsString(List.of(e.getKey(), JSON.writeValueAsString(e.getValue()), cols)));
        }
        Files.write(rows, lines, StandardCharsets.UTF_8);
        List<LakeManifest.Column> columns = new ArrayList<>(List.of(new LakeManifest.Column("id", "string"), new LakeManifest.Column("doc", "string"),
                new LakeManifest.Column("business_date", "date")));
        List<String> names = new ArrayList<>();
        for (String p : promoted) {
            columns.add(new LakeManifest.Column(p.replace(".", "__"), numeric.get(p) ? "double" : "string"));
            names.add(p.replace(".", "__"));
        }
        List<String> stats = new ArrayList<>(List.of("id", "business_date"));
        stats.addAll(names);
        List<LakeManifest.Part> parts = new ArrayList<>();
        for (LocalDate day : days()) {
            parts.add(new LakeManifest.Part(rows, docs.size(), Map.of("business_date", day.toString())));
        }
        new LakeWriter().write(new LakeManifest(table.toString(), List.of("business_date"), columns, names, "overwrite-dates", 5_000, stats, parts),
                done -> { });
    }
}
