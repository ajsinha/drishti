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
package com.ash.drishti.lakeconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The checks of docs/architecture/LAKE_CONFIG.md section 6, run at start and by {@code drishti.py lake check}: every Delta or Iceberg
 * connector names a known lake; every table of a lake file that exists on a local disk matches it (its format, its partition columns,
 * its promoted columns present). A difference is reported, never rewritten. A table on S3 or in memory is reported as not checked from
 * here (the server's connector checks it when it opens the table).
 */
public final class LakeCheck {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One finding: {@code level} error (the lake file and the table disagree, or a connector names no known lake) or note. */
    public record Finding(String level, String lake, String table, String message) {}

    private LakeCheck() {
    }

    /** Checks every lake, and every connector setting map given ({@code name -> (plugin, settings)}). */
    public static List<Finding> check(Lakes lakes, Map<String, Map.Entry<String, Map<String, String>>> connectors) {
        List<Finding> out = new ArrayList<>();
        connectors.forEach((name, c) -> {
            if (!ConnectorLake.PLUGINS.contains(c.getKey())) {
                return;
            }
            String lake = c.getValue().get(ConnectorLake.LAKE);
            if (lake == null || lake.isBlank()) {
                if (ConnectorLake.legacy(c.getValue())) {
                    out.add(new Finding("note", "", "", "connector " + name + " still describes its lake itself: run drishti.py lake migrate-config"));
                }
            } else if (lakes.find(lake.strip()).isEmpty()) {
                out.add(new Finding("error", lake, "", "connector " + name + " names lake " + lake + ", which has no file in " + lakes.folder()));
            }
        });
        for (Lake l : lakes.all().values()) {
            for (LakeTable t : l.tables().values()) {
                out.addAll(table(l, t));
            }
        }
        return out;
    }

    /** One table against what is on disk. */
    public static List<Finding> table(Lake lake, LakeTable t) {
        List<Finding> out = new ArrayList<>();
        String loc = lake.location(t);
        if (lake.inMemory() || loc.matches("^[a-z][a-z0-9+]*://.*") && !loc.startsWith("file://")) {
            out.add(new Finding("note", lake.name(), t.name(), "not checked from here: " + loc + " is not a local folder"));
            return out;
        }
        Path dir = Path.of(loc.replaceFirst("^file://", ""));
        if (!Files.isDirectory(dir)) {
            out.add(new Finding("note", lake.name(), t.name(), "no table yet at " + dir + " (a writer creates it as the lake file says)"));
            return out;
        }
        boolean delta = Files.isDirectory(dir.resolve("_delta_log"));
        boolean iceberg = Files.isDirectory(dir.resolve("metadata"));
        String found = delta ? "delta" : iceberg ? "iceberg" : "none";
        if (!found.equals(t.format())) {
            out.add(new Finding("error", lake.name(), t.name(), "the lake file says " + t.format() + "; " + dir + " is " + (found.equals("none")
                    ? "neither a Delta nor an Iceberg table" : "a " + found + " table")));
            return out;
        }
        try {
            Shape s = delta ? deltaShape(dir) : icebergShape(dir);
            if (s == null) {
                out.add(new Finding("note", lake.name(), t.name(), "the table's schema is only in a checkpoint: partition and columns not checked"));
                return out;
            }
            List<String> want = t.format().equals("delta") ? t.partitionColumns() : List.of(LakeTable.BUSINESS_DATE);
            if (!s.partitions().equals(want)) {
                out.add(new Finding("error", lake.name(), t.name(), "partitioned by " + s.partitions() + " on disk; the lake file says " + want
                        + " (reported, never rewritten: rebuild the table or correct the lake file)"));
            }
            List<String> missing = t.promote().stream().filter(c -> !s.columns().contains(c)).toList();
            if (!missing.isEmpty()) {
                out.add(new Finding("error", lake.name(), t.name(), "promoted columns missing from the table: " + missing));
            }
        } catch (IOException e) {
            out.add(new Finding("error", lake.name(), t.name(), "cannot read the table's metadata at " + dir + ": " + e.getMessage()));
        }
        return out;
    }

    /** A table's partition columns and its column names. */
    record Shape(List<String> partitions, Set<String> columns) {}

    /** The last metaData action of the Delta log's JSON commits (null when only a checkpoint holds it). */
    static Shape deltaShape(Path table) throws IOException {
        List<Path> commits;
        try (Stream<Path> s = Files.list(table.resolve("_delta_log"))) {
            commits = s.filter(p -> p.getFileName().toString().matches("\\d{20}\\.json")).sorted(Comparator.reverseOrder()).toList();
        }
        for (Path c : commits) {
            JsonNode meta = null;
            try (BufferedReader r = Files.newBufferedReader(c)) {
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    if (line.contains("\"metaData\"")) {
                        meta = JSON.readTree(line).path("metaData");
                    }
                }
            }
            if (meta != null) {
                List<String> parts = new ArrayList<>();
                meta.path("partitionColumns").forEach(p -> parts.add(p.asText()));
                Set<String> cols = new HashSet<>();
                JSON.readTree(meta.path("schemaString").asText("{}")).path("fields").forEach(f -> cols.add(f.path("name").asText()));
                return new Shape(parts, cols);
            }
        }
        return null;
    }

    /** The current schema and partition spec of the newest Iceberg metadata file. */
    static Shape icebergShape(Path table) throws IOException {
        Path newest;
        try (Stream<Path> s = Files.list(table.resolve("metadata"))) {
            newest = s.filter(p -> p.getFileName().toString().endsWith(".metadata.json")).max(Comparator.comparing(Path::getFileName)).orElse(null);
        }
        if (newest == null) {
            return null;
        }
        JsonNode m = JSON.readTree(newest.toFile());
        int schemaId = m.path("current-schema-id").asInt(0);
        JsonNode schema = null;
        for (JsonNode sc : m.path("schemas")) {
            if (sc.path("schema-id").asInt() == schemaId) {
                schema = sc;
            }
        }
        if (schema == null) {
            schema = m.path("schema");
        }
        Map<Integer, String> byId = new java.util.HashMap<>();
        Set<String> cols = new HashSet<>();
        schema.path("fields").forEach(f -> {
            byId.put(f.path("id").asInt(), f.path("name").asText());
            cols.add(f.path("name").asText());
        });
        int specId = m.path("default-spec-id").asInt(0);
        List<String> parts = new ArrayList<>();
        for (JsonNode spec : m.path("partition-specs")) {
            if (spec.path("spec-id").asInt() == specId) {
                spec.path("fields").forEach(f -> parts.add(byId.getOrDefault(f.path("source-id").asInt(), f.path("name").asText())));
            }
        }
        return new Shape(parts, cols);
    }
}
