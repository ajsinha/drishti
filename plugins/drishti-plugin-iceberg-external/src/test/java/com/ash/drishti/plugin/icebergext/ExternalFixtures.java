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
package com.ash.drishti.plugin.icebergext;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.read.TaskReader;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.write.Operations;
import com.ash.drishti.iceberg.write.ParquetDataWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * The pyiceberg-written fixture tables of the Iceberg connector's tests (made by {@code tools/iceberg/fixtures.py}, in
 * {@code plugins/drishti-plugin-iceberg/src/test/resources/iceberg-fixtures}): the shapes other engines write
 * (DRISHTI_ICEBERG.md section 15.5). A test copies them into a path-based lake of its own, where the Iceberg library can
 * add merge-on-read delete files as Spark and Flink do.
 */
final class ExternalFixtures {

    static final String WRITTEN_AT = "file:///tmp/drishti-iceberg-fixtures";
    static final Path SOURCE = Path.of("../drishti-plugin-iceberg/src/test/resources/iceberg-fixtures").toAbsolutePath().normalize();
    static final ObjectMapper JSON = new ObjectMapper();

    private ExternalFixtures() {}

    static JsonNode expected() {
        try {
            return JSON.readTree(SOURCE.resolve("expected.json").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Settings reading the fixtures where they are (relocated), each named as a kind by its metadata file. */
    static Map<String, String> metadataSettings(Map<String, String> kindToFixture) {
        Map<String, String> s = new HashMap<>();
        s.put("relocate", WRITTEN_AT + " -> " + SOURCE.toUri().toString().replaceAll("/+$", ""));
        JsonNode e = expected();
        kindToFixture.forEach((kind, fx) -> s.put("metadata-location." + kind, WRITTEN_AT + "/" + e.get(fx).path("metadata").asText()));
        return s;
    }

    /** Copies a fixture table's current rows into a new path-based table {@code <root>/<ns>/<name>}, each partition in {@code split} files. */
    static void copy(String fixture, Path root, String ns, String name, ExecutorService pool, int split) throws IOException {
        try (Catalog src = Catalogs.open(metadataSettings(Map.of(fixture, fixture)));
             Catalog dst = Catalogs.open(Map.of("root", root.toString(), "domain", ns))) {
            Table from = src.load(fixture).orElseThrow();
            Schema schema = from.metadata().schema();
            PartitionSpec spec = from.metadata().spec();
            Table to = dst.create(name, schema, spec, from.metadata().sortOrder(), new LinkedHashMap<>(Map.of("format-version", "2")));
            List<Integer> ids = schema.columns().stream().map(NestedField::id).toList();
            Map<List<Object>, List<Object[]>> parts = new LinkedHashMap<>();
            for (FileTask task : from.plan(from.currentSnapshot().orElseThrow(), Expr.TRUE, pool)) {
                try (TaskReader r = from.rows(task, schema, ids, Expr.TRUE, false)) {
                    while (r.hasNext()) {
                        Object[] row = r.next();
                        Object[] p = new Object[spec.fields().size()];
                        for (int i = 0; i < p.length; i++) {
                            PartitionSpec.Field f = spec.fields().get(i);
                            p[i] = f.transform().apply((Type.Primitive) schema.field(f.sourceId()).type(), row[ids.indexOf(f.sourceId())]);
                        }
                        parts.computeIfAbsent(Arrays.asList(p), k -> new ArrayList<>()).add(row);
                    }
                }
            }
            List<DataFile> files = new ArrayList<>();
            for (Map.Entry<List<Object>, List<Object[]>> e : parts.entrySet()) {
                // rows dealt round-robin into split files: their id ranges overlap, as an unsorted writer's do
                for (int part = 0; part < split; part++) {
                    String path = to.metadata().location() + "/data/" + UUID.randomUUID() + ".parquet";
                    try (ParquetDataWriter w = new ParquetDataWriter(to.io(), path, schema, to.metadata().properties(), spec.specId(),
                            e.getKey().toArray(), 0)) {
                        List<Object[]> rows = e.getValue();
                        for (int i = part; i < rows.size(); i += split) {
                            w.write(rows.get(i));
                        }
                        if (w.rows() > 0) {
                            files.add(w.finish());
                        }
                    }
                }
            }
            Operations.append(to, files, Map.of());
        }
    }

    static void copy(String fixture, Path root, String ns, String name, ExecutorService pool) throws IOException {
        copy(fixture, root, ns, name, pool, 1);
    }

    static Path temp(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void delete(Path p) {
        try (Stream<Path> all = Files.walk(p)) {
            all.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException e) {
            // under target/: mvn clean removes it
        }
    }
}
