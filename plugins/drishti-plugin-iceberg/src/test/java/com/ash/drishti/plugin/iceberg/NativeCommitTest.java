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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.write.Commit;
import com.ash.drishti.iceberg.write.Operations;
import com.ash.drishti.plugin.iceberg.IcebergLayout.Row;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Commits on local disk are safe with several writers: each new metadata version is an exclusive create (a hard link),
 * so of writers committing at once exactly one wins each version and the others re-read and commit again; no commit is
 * lost. A rewrite of a day that changed under it is refused, not applied. A commit that finally fails leaves no files.
 */
class NativeCommitTest {

    static List<Row> rows(String prefix, int n) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Row(String.format("%s-%03d", prefix, i), "{\"i\":" + i + "}", Map.of("mtm", (double) i)));
        }
        return out;
    }

    static long liveRows(Table t, ExecutorService pool) {
        t.refresh();
        return t.plan(t.currentSnapshot().orElseThrow(), Expr.TRUE, pool).stream().mapToLong(f -> f.file().recordCount()).sum();
    }

    @Test
    void writersCommittingAtOnceLoseNothing() throws Exception {
        Path root = Files.createTempDirectory("drishti-iceberg-commits");
        Map<String, String> settings = Map.of("root", root.toString(), "domain", "desk");
        try (Catalog setup = Catalogs.open(settings)) {
            NativeLayout.open(setup, "trade", Map.of("mtm", true), IcebergLayout.DEFAULT_ROW_GROUP_BYTES, 1000);
        }
        int writers = 4;
        int commitsEach = 5;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int me = w;
                done.add(pool.submit(() -> {
                    try (Catalog c = Catalogs.open(settings)) {                 // each writer its own catalog, as separate processes would
                        Table t = c.load("trade").orElseThrow();
                        start.await();
                        for (int i = 0; i < commitsEach; i++) {
                            LocalDate day = LocalDate.of(2026, 9, 1 + me * commitsEach + i);
                            List<DataFile> files = NativeLayout.writeDay(t, day, rows("W" + me, 10).iterator(), 1000);
                            Operations.append(t, files, Map.of());
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        }
        try (Catalog c = Catalogs.open(settings); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Table t = c.load("trade").orElseThrow();
            assertThat(t.metadata().snapshots()).hasSize(writers * commitsEach);
            assertThat(liveRows(t, pool)).isEqualTo(writers * commitsEach * 10L);
            Set<String> files = new HashSet<>();
            t.plan(t.currentSnapshot().orElseThrow(), Expr.TRUE, pool).forEach(f -> files.add(f.file().path()));
            assertThat(files).hasSize(writers * commitsEach);
        }
        try (Stream<Path> tmp = Files.walk(root)) {
            assertThat(tmp.filter(p -> p.getFileName().toString().endsWith(".tmp"))).isEmpty();   // no half-made metadata left
        }
    }

    @Test
    void aRewriteOfADayThatChangedMeanwhileIsRefused() throws Exception {
        Path root = Files.createTempDirectory("drishti-iceberg-validate");
        try (Catalog c = Catalogs.open(Map.of("root", root.toString(), "domain", "desk")); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Table t = NativeLayout.open(c, "trade", Map.of("mtm", true), IcebergLayout.DEFAULT_ROW_GROUP_BYTES, 1000);
            LocalDate day = LocalDate.of(2026, 9, 30);
            NativeLayout.commitDays(t, Map.of(day, NativeLayout.writeDay(t, day, rows("A", 10).iterator(), 1000)));
            List<FileTask> read = t.plan(t.currentSnapshot().orElseThrow(), Expr.TRUE, pool);
            Set<String> readPaths = new HashSet<>();
            read.forEach(f -> readPaths.add(f.file().path()));
            // another writer appends to the day after the rewrite read it
            Operations.append(t, NativeLayout.writeDay(t, day, rows("B", 3).iterator(), 1000), Map.of());
            List<DataFile> rewritten = NativeLayout.writeDay(t, day, rows("A", 10).iterator(), 1000);
            assertThatThrownBy(() -> Operations.rewriteFiles(t, readPaths, rewritten, f -> day.equals(NativeLayout.dateOf(t, f)), readPaths))
                    .isInstanceOf(Commit.ValidationFailed.class).hasMessageContaining("1 added");
            assertThat(liveRows(t, pool)).isEqualTo(13);                     // nothing lost
            // a rewrite of what is there now goes through
            Set<String> now = new HashSet<>();
            t.plan(t.currentSnapshot().orElseThrow(), Expr.TRUE, pool).forEach(f -> now.add(f.file().path()));
            Operations.rewriteFiles(t, now, NativeLayout.writeDay(t, day, rows("C", 13).iterator(), 1000), f -> day.equals(NativeLayout.dateOf(t, f)), now);
            assertThat(liveRows(t, pool)).isEqualTo(13);
        }
    }
}
