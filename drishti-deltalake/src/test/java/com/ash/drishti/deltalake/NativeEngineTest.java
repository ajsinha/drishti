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
package com.ash.drishti.deltalake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadRequest;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The native engine reads what Kernel's Hadoop engine reads, row for row: the fixture lakes (written by delta-rs),
 * an older version, a checkpoint, one row group for {@code id = X}, byte ranges, and many threads at once.
 */
class NativeEngineTest {

    static Path lake;
    static Path layout;
    static NativeEngine engine;
    static Engine hadoop;

    @BeforeAll
    static void setUp() throws Exception {
        lake = copy(Path.of("src/test/resources/lake"));
        layout = copy(Path.of("src/test/resources/lake-layout"));
        engine = NativeEngine.create();
        hadoop = DefaultEngine.create(new Configuration());
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    static Path copy(Path src) throws Exception {
        Path root = Files.createTempDirectory("drishti-deltalake");
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = root.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
        return root;
    }

    @Test
    void readsTheSameRowsAsTheHadoopEngine() throws Exception {
        for (String table : List.of("desk/trade", "desk/counterparty")) {
            String path = lake.resolve(table).toString();
            Map<String, String> mine = TableReader.rows(engine, path, null);
            assertThat(mine).isNotEmpty().isEqualTo(TableReader.rows(hadoop, path, null));
        }
        String trades = layout.resolve("desk/trade").toString();
        assertThat(TableReader.rows(engine, trades, null)).hasSize(80).isEqualTo(TableReader.rows(hadoop, trades, null));
    }

    @Test
    void readsAnOlderVersion() throws Exception {
        String path = lake.resolve("desk/trade").toString();
        Map<String, String> v0 = TableReader.rows(engine, path, 0L);
        assertThat(v0).isEqualTo(TableReader.rows(hadoop, path, 0L)).isNotEqualTo(TableReader.rows(engine, path, null));
        assertThat(v0.get("2026-09-30|T-1")).contains("\"mtm\": 120");
        assertThat(Table.forPath(engine, path).getLatestSnapshot(engine).getVersion()).isEqualTo(1);
    }

    @Test
    void tablePathsInEveryLocalFormResolveToOneTable() throws Exception {
        Path t = lake.resolve("desk/trade");
        String canonical = engine.getFileSystemClient().resolvePath(t.toString());
        assertThat(canonical).isEqualTo("file:" + t.toAbsolutePath());
        assertThat(engine.getFileSystemClient().resolvePath(t.toUri().toString())).isEqualTo(canonical);   // file:///…/trade/
        assertThat(engine.getFileSystemClient().resolvePath("file:" + t)).isEqualTo(canonical);
        assertThat(TableReader.rows(engine, t.toUri().toString(), null)).isEqualTo(TableReader.rows(engine, t.toString(), null));
    }

    @Test
    void aMissingTableIsNotFound() {
        assertThatThrownBy(() -> Table.forPath(engine, lake.resolve("desk/nothing").toString()).getLatestSnapshot(engine))
                .isInstanceOf(TableNotFoundException.class);
        assertThatThrownBy(() -> engine.getFileSystemClient().listFrom(lake.resolve("nope/_delta_log/0").toString()))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void listingsAreSortedAndStartAtTheGivenName() throws Exception {
        String log = engine.getFileSystemClient().resolvePath(layout.resolve("desk/trade/_delta_log").toString());
        List<String> names = new ArrayList<>();
        try (CloseableIterator<FileStatus> it = engine.getFileSystemClient().listFrom(log + "/00000000000000000002")) {
            it.forEachRemaining(f -> names.add(f.getPath().substring(log.length() + 1)));
        }
        assertThat(names).containsExactly("00000000000000000002.json", "00000000000000000003.json", "00000000000000000004.json",
                "00000000000000000005.json");
    }

    @Test
    void anIdPredicateReadsOneRowGroup() throws Exception {
        // the layout fixture: files of 15 trades sorted by id, row groups of 4
        FileStatus file = TableReader.files(engine, layout.resolve("desk/trade").toString()).stream()
                .filter(f -> f.getPath().contains("business_date=2026-09-30")).findFirst().orElseThrow();
        List<String> all = TableReader.idsWhere(engine, file, "zzz-none");
        assertThat(all).isEmpty();                                                     // every row group pruned
        List<String> ids = new ArrayList<>(TableReader.rows(engine, layout.resolve("desk/trade").toString(), null).keySet());
        for (String key : ids) {
            String id = key.substring(key.indexOf('|') + 1);
            List<String> one = TableReader.idsWhere(engine, file, id);
            assertThat(one).isEqualTo(TableReader.idsWhere(hadoop, file, id)).hasSizeLessThanOrEqualTo(4);
            if (!one.isEmpty()) {
                assertThat(one).contains(id);                                          // the one row group that holds it
            }
        }
    }

    @Test
    void aCheckpointIsReadWithoutTheCommitsBeforeIt() throws Exception {
        Path root = copy(Path.of("src/test/resources/lake-layout"));
        String path = root.resolve("desk/trade").toString();
        Map<String, String> before = TableReader.rows(engine, path, null);
        Table.forPath(hadoop, path).checkpoint(hadoop, 5);                           // Kernel writes it (the test only)
        for (int v = 0; v <= 4; v++) {
            Files.delete(root.resolve("desk/trade/_delta_log/%020d.json".formatted(v)));   // only the checkpoint is left
        }
        try (Stream<Path> log = Files.list(root.resolve("desk/trade/_delta_log"))) {
            assertThat(log.anyMatch(f -> f.toString().endsWith(".checkpoint.parquet"))).isTrue();
        }
        assertThat(TableReader.rows(engine, path, null)).isEqualTo(before);
    }

    @Test
    void byteRangesAreRead() throws Exception {
        Path f = lake.resolve("desk/trade/_delta_log/00000000000000000000.json");
        byte[] all = Files.readAllBytes(f);
        FileReadRequest req = new FileReadRequest() {
            @Override
            public String getPath() {
                return "file:" + f;
            }

            @Override
            public int getStartOffset() {
                return 5;
            }

            @Override
            public int getReadLength() {
                return 20;
            }
        };
        try (CloseableIterator<ByteArrayInputStream> it = engine.getFileSystemClient().readFiles(Utils.singletonCloseableIterator(req))) {
            assertThat(it.next().readAllBytes()).isEqualTo(java.util.Arrays.copyOfRange(all, 5, 25));
        }
    }

    @Test
    void writesAreRefused() {
        assertThatThrownBy(() -> engine.getFileSystemClient().mkdirs("file:/tmp/x")).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("only reads");
        assertThatThrownBy(() -> engine.getJsonHandler().writeJsonFileAtomically("file:/tmp/x.json", Utils.toCloseableIterator(
                java.util.Collections.emptyIterator()), false)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> engine.storage("abfs://c@acct.dfs.core.windows.net/lake")).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("engine to hadoop");
    }

    @Test
    void oneEngineServesManyThreads() throws Exception {
        String path = layout.resolve("desk/trade").toString();
        Map<String, String> expected = TableReader.rows(engine, path, null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Map<String, String>>> runs = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                runs.add(pool.submit(() -> TableReader.rows(engine, path, null)));
            }
            for (Future<Map<String, String>> r : runs) {
                assertThat(r.get()).isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
