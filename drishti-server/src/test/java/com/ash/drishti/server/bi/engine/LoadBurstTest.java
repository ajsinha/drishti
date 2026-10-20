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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * QA3B-07: a burst of data-load signals while BI pages are read. 2,976 signals (8 clients, a round every 5 s over 60 dates) rebuilt each BI
 * table 24 to 25 times per date, four rebuilds failed on a temporary file another build's purge removed, and the server was still at
 * 1,000 % CPU 20 minutes later. Now a signal for a date whose stored partition was built from the data the source holds now changes
 * nothing, a waiting rebuild runs only when the data changed, a partition is built by one build at a time, and an invalidation never
 * deletes a build's files.
 */
class LoadBurstTest {

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    QueryService.Loaded ds;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        DatasetParser.Result r = DatasetParser.parse(TestLake.yaml());
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), r.dataset(), "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private QueryService.Caller risk() {
        return new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null), (t, c) -> false);
    }

    private void preview() throws Exception {
        service.query(ds, QueryRequest.of(json.readTree("{\"fields\":[\"trades.desk\"],\"measures\":[\"Notional\"],"
                + "\"dates\":{\"from\":\"2026-10-08\",\"to\":\"2026-10-09\"}}")), risk(), false);
    }

    private void settle() throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while ((service.rebuildsWaiting() > 0 || lake.scanning.get() > 0) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(200);
    }

    /** Eight clients send 3,000 signals for the two dates while three readers ask the preview; returns the failures the readers saw. */
    private Queue<Throwable> burst(int signals, Runnable midway) throws Exception {
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AtomicBoolean signalling = new AtomicBoolean(true);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<?>> readers = new java.util.ArrayList<>();
            for (int r = 0; r < 3; r++) {
                readers.add(pool.submit(() -> {
                    while (signalling.get()) {
                        try {
                            preview();
                        } catch (Throwable e) {
                            failures.add(e);
                        }
                    }
                }));
            }
            List<Future<?>> clients = new java.util.ArrayList<>();
            java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
            for (int c = 0; c < 8; c++) {
                clients.add(pool.submit(() -> {
                    int i;
                    while ((i = sent.getAndIncrement()) < signals) {
                        if (i == signals / 2 && midway != null) {
                            midway.run();
                        }
                        LocalDate day = i % 2 == 0 ? TestLake.DAY1 : TestLake.DAY2;
                        service.onLoad("trade", day, List.of(ds));
                    }
                }));
            }
            for (Future<?> f : clients) {
                f.get();
            }
            settle();
            signalling.set(false);
            for (Future<?> f : readers) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        settle();
        return failures;
    }

    @Test
    void threeThousandSignalsForUnchangedDataRebuildNothing() throws Exception {
        lake.versions.put(TestLake.DAY1, "files-1");
        lake.versions.put(TestLake.DAY2, "files-1");
        preview();                                                         // both dates built
        lake.scanDelayMillis = 5;
        int scans = lake.scans.get();
        Queue<Throwable> failures = burst(3_000, null);
        assertThat(failures).as("previews during the burst").isEmpty();
        assertThat(lake.scans.get() - scans).as("source reads for 3,000 signals of unchanged data").isZero();
        assertThat(service.rebuildsRun()).isZero();
        assertThat(service.unchangedSignals()).isEqualTo(3_000);
    }

    @Test
    void aDateWhoseDataChangesMidBurstIsRebuiltOnceMoreNotPerSignal() throws Exception {
        lake.versions.put(TestLake.DAY1, "files-1");
        lake.versions.put(TestLake.DAY2, "files-1");
        preview();
        lake.scanDelayMillis = 20;                                         // a rebuild takes a while: signals arrive while it runs
        int scans = lake.scans.get();
        Queue<Throwable> failures = burst(3_000, () -> lake.versions.put(TestLake.DAY2, "files-2"));
        assertThat(failures).as("previews during the burst").isEmpty();
        // DAY2 read again once by a rebuild (and perhaps once by a preview that asked first), DAY1 never: not once per signal
        assertThat(lake.scans.get() - scans).as("source reads").isBetween(1, 3);
        assertThat(service.rebuildsRun()).as("background rebuilds").isLessThanOrEqualTo(2);
        int after = lake.scans.get();
        preview();
        assertThat(lake.scans.get()).as("settled: the preview reads the stored layer").isEqualTo(after);
    }

    @Test
    void withoutADataVersionTheBurstStaysBoundedAndNoBuildLosesItsFiles() throws Exception {
        // a source that cannot say its data version: every signal clears the date, so this is the worst case for the temporary-file race
        preview();
        lake.scanDelayMillis = 5;
        int scans = lake.scans.get();
        Queue<Throwable> failures = burst(3_000, null);
        assertThat(failures).as("no build lost its temporary file or its metadata (DRS-9027)").isEmpty();
        assertThat(service.rebuildsPeak()).as("one rebuild at a time per table").isEqualTo(1);
        // at most one running and one waiting per date: far fewer reads than signals
        assertThat(lake.scans.get() - scans).as("source reads for 3,000 signals").isLessThan(600);
        int after = lake.scans.get();
        preview();
        assertThat(lake.scans.get() - after).as("settled").isLessThanOrEqualTo(2);
    }
}
