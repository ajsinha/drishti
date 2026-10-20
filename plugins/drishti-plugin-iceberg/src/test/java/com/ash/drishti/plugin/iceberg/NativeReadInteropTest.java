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

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.read.JsonValues;
import com.ash.drishti.iceberg.read.TaskReader;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Interop, read: every snapshot of every pyiceberg-written fixture table (v1 and v2, every type, every transform,
 * partition and schema evolution, a file without field ids read through the name mapping) read by drishti-iceberg
 * equals what pyiceberg read from it, row for row.
 */
class NativeReadInteropTest {

    static Path root;
    static ExecutorService pool;

    @BeforeAll
    static void copy() {
        root = Fixtures.copy();
        pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterAll
    static void close() {
        pool.close();
    }

    static List<String> tables() {
        List<String> out = new ArrayList<>();
        Fixtures.expected(Path.of("src/test/resources/iceberg-fixtures")).fieldNames().forEachRemaining(out::add);
        return out;
    }

    /** Every live row of a snapshot as JSON (decimals as text, as the fixtures record them), sorted. */
    static List<String> rows(Table t, Snapshot s, ExecutorService pool) {
        Schema schema = t.metadata().schemaFor(s);
        List<Integer> ids = schema.columns().stream().map(NestedField::id).toList();
        List<String> out = new ArrayList<>();
        for (FileTask task : t.plan(s, Expr.TRUE, pool)) {
            try (TaskReader r = t.rows(task, schema, ids, Expr.TRUE, false)) {
                while (r.hasNext()) {
                    out.add(Fixtures.canonical(JsonValues.row(schema.columns(), r.next(), new JsonValues.Options(true, false))));
                }
            }
        }
        out.sort(null);
        return out;
    }

    static List<String> expectedRows(JsonNode snapshot) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : snapshot.path("rows")) {
            out.add(Fixtures.canonical(r));
        }
        out.sort(null);
        return out;
    }

    @ParameterizedTest
    @MethodSource("tables")
    void everySnapshotReadsAsPyicebergReadIt(String name) throws Exception {
        JsonNode want = Fixtures.expected(root).get(name);
        try (Catalog c = Catalogs.open(Fixtures.settings(root))) {
            Table t = c.load(name).orElseThrow();
            assertThat(t.metadata().formatVersion()).isEqualTo(want.path("format-version").asInt());
            for (JsonNode snap : want.path("snapshots")) {
                Snapshot s = t.metadata().snapshot(snap.path("id").asLong()).orElseThrow();
                assertThat(rows(t, s, pool)).as(name + " snapshot " + s.snapshotId()).containsExactlyElementsOf(expectedRows(snap));
            }
        }
    }

}
