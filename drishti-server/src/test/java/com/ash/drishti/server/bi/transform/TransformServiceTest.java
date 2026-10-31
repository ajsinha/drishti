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
package com.ash.drishti.server.bi.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the Transform service keeps and refuses (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20.10), with a runner that writes a real Parquet file but no Python: the result is made once
 * per reader and version of the inputs and served until it expires, a second reader or a new version runs again, a failure is never kept, the limits on rows,
 * bytes and concurrent runs hold, a declared column that the frame lacks is refused, and the columns of the last good frame are remembered across a restart.
 */
class TransformServiceTest {

    @TempDir Path dir;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T09:00:00Z"));
    final AtomicInteger runs = new AtomicInteger();
    final AtomicReference<RuntimeException> fail = new AtomicReference<>();
    final AtomicReference<CountDownLatch> hold = new AtomicReference<>();
    int rowsToReturn = 3;
    String[] columns = {"desk", "mtm"};
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    /** A runner that makes {@code rowsToReturn} rows of {@code columns} as a Parquet file with DuckDB. */
    TransformRunner runner = r -> {
        runs.incrementAndGet();
        CountDownLatch latch = hold.get();
        if (latch != null) {
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (fail.get() != null) {
            throw fail.get();
        }
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            String select = String.join(", ", java.util.Arrays.stream(columns).map(col -> "i AS " + col).toList());
            s.execute("COPY (SELECT " + select + " FROM range(" + rowsToReturn + ") t(i)) TO '" + r.output().toString().replace("'", "''") + "' (FORMAT parquet)");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return new TransformRunner.Result(r.output(), java.util.Arrays.stream(columns).map(col -> new TransformRunner.Column(col, "decimal")).toList(), rowsToReturn, 7);
    };

    TransformProperties props(Integer entries, Integer maxRows, Long maxBytes, Integer concurrent, Duration wait) {
        return new TransformProperties(true, null, null, null, Duration.ofMinutes(30), entries, null, maxRows, maxBytes, null, concurrent, wait, null);
    }

    TransformService service(TransformProperties p) {
        return new TransformService(p, r -> runner.run(r), dir, clock);
    }

    TransformService.Prepared prepared() {
        return new TransformService.Prepared(Map.of("name", "t"), List.of(new byte[0]), Map.of());
    }

    @BeforeEach
    void reset() {
        runs.set(0);
    }

    @AfterEach
    void close() {
        pool.shutdownNow();
    }

    @Test
    void theResultIsMadeOnceForAReaderAndVersionAndServedUntilItExpires() {
        TransformService s = service(props(null, null, null, null, null));
        TransformService.Output first = s.ensure("ds", "ds", "rita|v1", Map.of(), this::prepared);
        assertThat(first.cached()).isFalse();
        assertThat(first.rows()).isEqualTo(3);
        assertThat(Files.isRegularFile(first.file())).isTrue();
        TransformService.Output again = s.ensure("ds", "ds", "rita|v1", Map.of(), () -> {
            throw new AssertionError("the inputs must not be asked again for a kept result");
        });
        assertThat(again.cached()).isTrue();
        assertThat(again.file()).isEqualTo(first.file());
        assertThat(runs).hasValue(1);
        // another reader, another version of the inputs: each its own run
        s.ensure("ds", "ds", "hana|v1", Map.of(), this::prepared);
        s.ensure("ds", "ds", "rita|v2", Map.of(), this::prepared);
        assertThat(runs).hasValue(3);
        // after the time-to-live the result is made again
        now.set(now.get().plus(Duration.ofMinutes(31)));
        assertThat(s.ensure("ds", "ds", "rita|v1", Map.of(), this::prepared).cached()).isFalse();
        assertThat(runs).hasValue(4);
        assertThat(s.stats()).containsEntry("runs", 4L).containsEntry("cacheHits", 1L);
    }

    @Test
    void concurrentRequestsForOneReaderRunTheTransformOnce() throws Exception {
        TransformService s = service(props(null, null, null, 4, null));
        CountDownLatch gate = new CountDownLatch(1);
        hold.set(gate);
        List<Future<TransformService.Output>> all = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(pool.submit(() -> s.ensure("ds", "ds", "rita|v1", Map.of(), this::prepared)));
        }
        Thread.sleep(200);
        gate.countDown();
        for (Future<TransformService.Output> f : all) {
            assertThat(f.get(10, TimeUnit.SECONDS).rows()).isEqualTo(3);
        }
        assertThat(runs).hasValue(1);
    }

    @Test
    void aFailureIsNeverKeptAndTheNextRequestTriesAgain() {
        TransformService s = service(props(null, null, null, null, null));
        fail.set(new DrishtiException(ErrorCode.PYTHON_FAILED, "ZeroDivisionError: boom"));
        assertThatThrownBy(() -> s.ensure("ds", "ds", "rita|v1", Map.of(), this::prepared)).isInstanceOf(DrishtiException.class).hasMessageContaining("boom");
        fail.set(null);
        assertThat(s.ensure("ds", "ds", "rita|v1", Map.of(), this::prepared).cached()).isFalse();
        assertThat(runs).hasValue(2);
        assertThat(s.stats()).containsEntry("failures", 1L);
    }

    @Test
    void theRowByteAndColumnLimitsAreEnforcedAndTheOffendingFileIsRemoved() throws Exception {
        rowsToReturn = 50;
        TransformService tooLong = service(props(null, 10, null, null, null));
        assertThatThrownBy(() -> tooLong.ensure("ds", "ds", "k1", Map.of(), this::prepared)).isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.PYTHON_LIMIT)).hasMessageContaining("50 rows").hasMessageContaining("max-rows");
        TransformService tooBig = service(props(null, null, 10L, null, null));
        assertThatThrownBy(() -> tooBig.ensure("ds", "ds", "k2", Map.of(), this::prepared)).hasMessageContaining("bytes").hasMessageContaining("max-bytes");
        rowsToReturn = 3;
        TransformService s = service(props(null, null, null, null, null));
        assertThatThrownBy(() -> s.ensure("ds", "ds", "k3", Map.of("desk", "string", "share", "decimal"), this::prepared)).isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.TRANSFORM_FAILED)).hasMessageContaining("share");
        try (var files = Files.walk(dir.resolve("ds"))) {
            assertThat(files.filter(f -> f.toString().endsWith(".parquet"))).isEmpty();
        }
    }

    @Test
    void onlyAsManyRunsAsTheSettingAllowsAndTheRestWaitThenAreTurnedAway() throws Exception {
        TransformService s = service(props(null, null, null, 1, Duration.ofMillis(300)));
        CountDownLatch gate = new CountDownLatch(1);
        hold.set(gate);
        Future<TransformService.Output> running = pool.submit(() -> s.ensure("ds", "ds", "rita|v1", Map.of(), this::prepared));
        Thread.sleep(200);
        assertThatThrownBy(() -> s.ensure("ds", "ds", "hana|v1", Map.of(), this::prepared)).isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.PYTHON_BUSY)).hasMessageContaining("max-concurrent");
        gate.countDown();
        assertThat(running.get(10, TimeUnit.SECONDS).rows()).isEqualTo(3);
    }

    @Test
    void theOldestResultsOfADatasetAreDroppedPastTheLimitAndInvalidateDropsAll() throws Exception {
        TransformService s = service(props(2, null, null, null, null));
        TransformService.Output a = s.ensure("ds", "ds", "a", Map.of(), this::prepared);
        now.set(now.get().plusSeconds(1));
        s.ensure("ds", "ds", "b", Map.of(), this::prepared);
        now.set(now.get().plusSeconds(1));
        s.ensure("ds", "ds", "c", Map.of(), this::prepared);
        assertThat(Files.exists(a.file())).as("the oldest of three, with room for two").isFalse();
        assertThat(s.stats()).containsEntry("kept", 2);
        assertThat(s.invalidate("ds")).isEqualTo(2);
        assertThat(s.stats()).containsEntry("kept", 0);
        assertThat(s.ensure("ds", "ds", "b", Map.of(), this::prepared).cached()).isFalse();
    }

    @Test
    void theColumnsOfTheLastGoodFrameAreRememberedAcrossARestart() {
        TransformService s = service(props(null, null, null, null, null));
        assertThat(s.learned("lake-derived")).isEmpty();
        s.ensure("ds", "lake-derived", "k", Map.of(), this::prepared);
        assertThat(s.learned("lake-derived")).containsEntry("desk", "decimal").containsEntry("mtm", "decimal");
        assertThat(service(props(null, null, null, null, null)).learned("lake-derived")).containsKeys("desk", "mtm");     // a new service reads what the old one stored
        columns = new String[] {"desk", "mtm", "share"};
        s.ensure("ds", "lake-derived", "k2", Map.of(), this::prepared);
        assertThat(s.learned("lake-derived")).containsKey("share");
    }
}
