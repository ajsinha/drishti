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
package com.ash.drishti.server.official;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The official results' sink on a local folder, as Delta (the default) and as Iceberg: the rows read back equal in both formats, a
 * restatement appends (the earlier rows kept, nothing overwritten), the JSON Lines beside the table, the version growing with each commit,
 * Iceberg's table properties (seven years of snapshots, append only), and writers appending at once all land.
 */
class OfficialSinkTest {

    static final String TABLE = "var-official";

    @TempDir
    Path tmp;

    private static String id(Map<String, Object> r) {
        return r.get("NODE_ID") + "@" + OfficialTables.DAY + "#v" + r.get("RESULT_VERSION");
    }

    @Test
    void deltaIsTheDefaultAndAnUnknownFormatIsRefused() {
        assertThat(OfficialSink.of(null, tmp.toString(), Map.of(), 7).format()).isEqualTo("delta");
        assertThat(OfficialSink.of("Iceberg", tmp.toString(), Map.of(), 7).format()).isEqualTo("iceberg");
        assertThat(OfficialSink.of("delta", " ", Map.of(), 7)).isNull();
        assertThatThrownBy(() -> OfficialSink.of("parquet", tmp.toString(), Map.of(), 7)).hasMessageContaining("delta or iceberg");
        assertThat(OfficialSink.of("delta", tmp.toString(), Map.of(), 7).describe(4)).isEqualTo("Delta version 4");
        assertThat(OfficialSink.of("iceberg", tmp.toString(), Map.of(), 7).describe(4)).isEqualTo("Iceberg sequence number 4");
    }

    @Test
    void theSameRowsReadBackEqualFromDeltaAndIcebergAndARestatementAppends() throws Exception {
        Map<String, Map<String, Map<String, Object>>> byFormat = new java.util.HashMap<>();
        for (String format : List.of("delta", "iceberg")) {
            Path root = tmp.resolve(format);
            OfficialSink sink = OfficialSink.of(format, root.toString(), Map.of(), 7);
            long v1 = sink.append(TABLE, OfficialTables.DAY, OfficialTables.columns(), OfficialTables.rows(1, 1.0), OfficialSinkTest::id);
            sink.writeJsonl(TABLE, OfficialTables.DAY, v1, OfficialTables.rows(1, 1.0));
            long v2 = sink.append(TABLE, OfficialTables.DAY, OfficialTables.columns(), OfficialTables.rows(2, 1.5), OfficialSinkTest::id);
            assertThat(v2).as(format + ": the version grows").isGreaterThan(v1);
            Map<String, Map<String, Object>> rows = OfficialTables.read(format, root.toString(), TABLE, Map.of());
            assertThat(rows).as(format + ": both versions kept").hasSize(6).containsKeys("MGMT~BANK@2026-09-30#v1", "MGMT~BANK@2026-09-30#v2");
            assertThat(Files.exists(root.resolve("risk-results/jsonl/var-official/2026-09-30/var-official-v" + v1 + ".jsonl"))).isTrue();
            byFormat.put(format, rows);
        }
        assertThat(byFormat.get("iceberg")).as("the documents are the same in both formats").isEqualTo(byFormat.get("delta"));
        String meta;
        try (var s = Files.list(tmp.resolve("iceberg/risk-results/var-official/metadata"))) {
            Path last = s.filter(p -> p.getFileName().toString().endsWith(".metadata.json")).sorted().reduce((a, b) -> b).orElseThrow();
            meta = Files.readString(last);
        }
        String compact = meta.replace(" ", "");
        assertThat(compact).contains("\"history.expire.max-snapshot-age-ms\":\"" + 2557L * 86_400_000L + "\"")
                .contains("\"write.metadata.delete-after-commit.enabled\":\"false\"").contains("\"drishti.append-only\":\"true\"");
    }

    /** Writers appending to one table at once: every commit lands (a lost race is retried on the newer version). */
    @ParameterizedTest
    @ValueSource(strings = {"delta", "iceberg"})
    void writersAtOnceAllLand(String format) throws Exception {
        Path root = tmp.resolve("race-" + format);
        OfficialSink sink = OfficialSink.of(format, root.toString(), Map.of(), 7);
        sink.append(TABLE, OfficialTables.DAY, OfficialTables.columns(), OfficialTables.rows(1, 1.0), OfficialSinkTest::id);   // the table exists
        List<Future<Long>> done = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (int i = 2; i <= 7; i++) {
                int v = i;
                done.add(pool.submit(() -> OfficialSink.of(format, root.toString(), Map.of(), 7)
                        .append(TABLE, OfficialTables.DAY, OfficialTables.columns(), OfficialTables.rows(v, v), OfficialSinkTest::id)));
            }
            for (Future<Long> f : done) {
                f.get();
            }
        }
        assertThat(OfficialTables.read(format, root.toString(), TABLE, Map.of())).hasSize(3 * 7);
    }

    @Test
    void aTableKeepsItsFormatWhenTheSettingChanges(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        OfficialSink delta = OfficialSink.of("delta", root.toString(), java.util.Map.of(), 7);
        delta.append("var-official", OfficialTables.DAY, OfficialTables.columns(), OfficialTables.rows(1, 1.0), r -> r.get("NODE_ID") + "#1");
        OfficialSink iceberg = OfficialSink.of("iceberg", root.toString(), java.util.Map.of(), 7);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> iceberg.append("var-official", OfficialTables.DAY, OfficialTables.columns(),
                OfficialTables.rows(2, 1.0), r -> r.get("NODE_ID") + "#2")).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("is a Delta table and the official format is iceberg");
    }
}
