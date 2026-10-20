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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Interop, write: tables written by drishti-iceberg (v1 and v2, every type, partitioned by day, bucket, truncate and
 * identity, identifier fields, nested types; and a Drishti-layout table from the native loader) are read back by
 * pyiceberg, an independent implementation, and equal what was written. Runs where {@code uv} is installed (it fetches
 * pyiceberg into its own cache, never the repository); skipped elsewhere.
 */
class PyicebergReadsNativeTest {

    static final List<String> COPIED = List.of("v1_basic", "v2_types", "t_day", "t_bucket_str", "t_truncate_dec", "t_identity", "t_month_of_date",
            "snowflake_counterparties");

    static String uv() {
        for (String dir : (System.getenv().getOrDefault("PATH", "") + File.pathSeparator + System.getProperty("user.home") + "/.local/bin")
                .split(File.pathSeparator)) {
            for (String name : List.of("uv", "uv.exe")) {
                File f = new File(dir, name);
                if (f.canExecute()) {
                    return f.getPath();
                }
            }
        }
        return null;
    }

    /** Copies a fixture table's current rows into a new table of the same schema, spec and format, written by drishti-iceberg. */
    static String copy(Table src, Catalog dst, String name, ExecutorService pool) throws Exception {
        Schema schema = src.metadata().schema();
        PartitionSpec spec = src.metadata().spec();
        Map<String, String> props = new LinkedHashMap<>(Map.of("format-version", String.valueOf(src.metadata().formatVersion())));
        Table t = dst.create(name, schema, spec, src.metadata().sortOrder(), props);
        List<Integer> ids = schema.columns().stream().map(NestedField::id).toList();
        Map<List<Object>, List<Object[]>> byPartition = new LinkedHashMap<>();
        for (FileTask task : src.plan(src.currentSnapshot().orElseThrow(), Expr.TRUE, pool)) {
            try (TaskReader r = src.rows(task, schema, ids, Expr.TRUE, false)) {
                while (r.hasNext()) {
                    Object[] row = r.next().clone();
                    Object[] part = new Object[spec.fields().size()];
                    for (int i = 0; i < part.length; i++) {
                        PartitionSpec.Field f = spec.fields().get(i);
                        Object v = row[ids.indexOf(f.sourceId())];
                        part[i] = f.transform().apply((Type.Primitive) schema.field(f.sourceId()).type(), v);
                    }
                    byPartition.computeIfAbsent(Arrays.asList(part), k -> new ArrayList<>()).add(row);
                }
            }
        }
        List<DataFile> files = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Object[]>> e : byPartition.entrySet()) {
            String path = t.metadata().location() + "/data/" + UUID.randomUUID() + ".parquet";
            try (ParquetDataWriter w = new ParquetDataWriter(t.io(), path, schema, t.metadata().properties(), spec.specId(), e.getKey().toArray(),
                    t.metadata().sortOrder().orderId())) {
                for (Object[] row : e.getValue()) {
                    w.write(row);
                }
                files.add(w.finish());
            }
        }
        Operations.append(t, files, Map.of());
        return t.metadataLocation();
    }

    @Test
    void pyicebergReadsWhatDrishtiIcebergWrites() throws Exception {
        String uv = uv();
        assumeTrue(uv != null, "uv is not installed: the pyiceberg round trip is skipped");
        Path fixtures = Fixtures.copy();
        Path out = Files.createTempDirectory("drishti-iceberg-roundtrip");
        Map<String, String> written = new LinkedHashMap<>();
        try (Catalog src = Catalogs.open(Fixtures.settings(fixtures)); Catalog dst = Catalogs.open(Map.of("root", out.toString(), "domain", "rt"));
             ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String name : COPIED) {
                written.put(name, copy(src.load(name).orElseThrow(), dst, name, pool));
            }
        }
        // a Drishti-layout table, from the native loader
        Path lines = out.resolve("rows.jsonl");
        Files.writeString(lines, "{\"domain\":\"desk\",\"kind\":\"trade\",\"id\":\"T-1\",\"date\":\"2026-09-30\",\"doc\":\"{}\",\"columns\":{\"mtm\":1.5}}\n"
                + "{\"domain\":\"desk\",\"kind\":\"trade\",\"id\":\"T-2\",\"date\":\"2026-09-30\",\"doc\":\"{}\",\"columns\":{\"mtm\":2.5}}\n");
        IcebergLoader.main(new String[] {lines.toString(), out.toString(), "--engine", "native", "--spill-dir", out.resolve("spill").toString()});
        try (Catalog desk = Catalogs.open(Map.of("root", out.toString(), "domain", "desk"))) {
            written.put("layout", desk.load("trade").orElseThrow().metadataLocation());
        }
        List<String> cmd = new ArrayList<>(List.of(uv, "run", "--no-project", "--with", "pyiceberg", "--with", "pyiceberg-core", "--with", "pyarrow",
                "--with", "sqlalchemy", "python", Path.of("../../tools/iceberg/readback.py").toAbsolutePath().normalize().toString()));
        for (String m : written.values()) {
            cmd.add(m.replaceFirst("^file:(?!//)", "file://"));
        }
        Process p = new ProcessBuilder(cmd).directory(out.toFile()).redirectErrorStream(false).start();
        byte[] stdout = p.getInputStream().readAllBytes();
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(300, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(stderr).isZero();
        JsonNode read = Fixtures.JSON.readTree(stdout);
        JsonNode expected = Fixtures.expected(fixtures);
        int i = 0;
        for (Map.Entry<String, String> e : written.entrySet()) {
            JsonNode got = read.get(cmd.get(cmd.size() - written.size() + i++));
            if (e.getKey().equals("layout")) {
                assertThat(got.path("rows")).hasSize(2);
                assertThat(got.path("rows").toString()).contains("\"id\":\"T-1\"", "\"mtm\":2.5", "\"business_date\":\"2026-09-30\"");
                continue;
            }
            JsonNode want = expected.get(e.getKey()).path("snapshots");
            List<String> wantRows = NativeReadInteropTest.expectedRows(want.get(want.size() - 1));
            List<String> gotRows = new ArrayList<>();
            got.path("rows").forEach(r -> gotRows.add(Fixtures.canonical(r)));
            gotRows.sort(null);
            assertThat(gotRows).as(e.getKey()).isEqualTo(wantRows);
            assertThat(got.path("format-version").asInt()).isEqualTo(expected.get(e.getKey()).path("format-version").asInt());
        }
    }
}
