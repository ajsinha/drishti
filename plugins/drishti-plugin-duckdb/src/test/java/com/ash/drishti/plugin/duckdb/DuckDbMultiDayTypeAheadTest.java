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
package com.ash.drishti.plugin.duckdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The DuckDB connector keeps five business days of ids; closed days can sit in memory-mapped files. */
class DuckDbMultiDayTypeAheadTest {

    @TempDir
    Path dir;

    private static final LocalDate D0 = LocalDate.of(2026, 9, 28);

    private static String line(String id, LocalDate d) {
        return "{\"domain\":\"desk\",\"kind\":\"trade\",\"id\":\"" + id + "\",\"date\":\"" + d + "\",\"doc\":\"{\\\"id\\\":\\\"" + id + "\\\"}\"}";
    }

    private Path load() throws Exception {
        Path db = dir.resolve("desk.duckdb");
        List<String> lines = new ArrayList<>();
        for (int d = 0; d < 5; d++) {
            for (int i = 0; i < 3; i++) {
                lines.add(line("T-" + d + "-" + i, D0.plusDays(d)));
            }
            lines.add(line("T-ALWAYS", D0.plusDays(d)));
        }
        Path f = dir.resolve("rows.jsonl");
        Files.write(f, lines, StandardCharsets.UTF_8);
        DuckDbLoader.main(new String[] {f.toString(), db.toString(), "--parsers", "2", "--memory-limit", "256MB"});
        return db;
    }

    private DuckDbSourcePlugin started(Path db, Map<String, String> extra) {
        JsonCodec codec = new JsonCodec();
        Map<String, String> settings = new java.util.HashMap<>(
                Map.of("path", db.toString(), "table", "desk.entities", "source-name", "desk-duckdb", "scan-threads", "2", "memory-limit", "256MB"));
        settings.putAll(extra);
        DuckDbSourcePlugin p = new DuckDbSourcePlugin();
        try {
            p.start(new SourceContext() {
                public Map<String, String> settings() {
                    return settings;
                }

                public DataNode parseJson(InputStream in) throws IOException {
                    return codec.read(in);
                }

                public ScheduledExecutorService scheduler() {
                    return Executors.newSingleThreadScheduledExecutor();
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    private static List<String> ids(List<EntityHit> hits) {
        return hits.stream().map(h -> h.ref().id()).toList();
    }

    @Test
    void eachPastDateAnswersWithItsOwnIdsFromTheHeapOrFromMappedFiles() throws Exception {
        Path db = load();
        Path index = dir.resolve("index");
        System.setProperty("drishti.index.dir", index.toString());
        DuckDbSourcePlugin p = started(db, Map.of("index.days", "5", "index.storage", "mmap"));
        try {
            p.datedIndexForTest().awaitWarm(15_000);
            for (int d = 0; d < 5; d++) {
                assertThat(ids(p.search("trade", "T-", 20, AsOf.of(D0.plusDays(d))))).containsExactlyInAnyOrder("T-" + d + "-0", "T-" + d + "-1",
                        "T-" + d + "-2", "T-ALWAYS");
            }
            assertThat(p.datedIndexForTest().onDisk("trade", D0)).isTrue();
            assertThat(p.datedIndexForTest().onDisk("trade", D0.plusDays(4))).isFalse();
            assertThat(ids(p.search("trade", "T-4", 20, AsOf.LATEST))).contains("T-4-0");     // live: the newest date, not a key of its own
            assertThat(p.health()).contains("type-ahead index: 5 days (4 on disk");
            assertThat(p.cacheStats()).containsEntry("indexDaysOnDisk", 4);
        } finally {
            System.clearProperty("drishti.index.dir");
            p.close();
        }
    }

    @Test
    void theDefaultKeepsOneDay() throws Exception {
        DuckDbSourcePlugin p = started(load(), Map.of());
        try {
            assertThat(p.health()).isEqualTo("UP");
            assertThat(p.cacheStats()).doesNotContainKey("indexDays");
        } finally {
            p.close();
        }
    }
}
