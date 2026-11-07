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
package com.ash.drishti.dataload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The batch and the runs (DATA_ARCHITECTURE.md section 9.4): the whole ready batch is claimed in one step into {@code inprocess/<run>/}
 * before anything loads; files that become ready during the run wait for the next cycle; a cycle due while a run still loads is not
 * started but recorded as skipped, and Load now says the same; the run id is its start time and node.
 */
class BatchAndOverlapTest {

    @TempDir Path tmp;

    private static List<String> data(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> !n.endsWith(Inbox.READY) && !n.startsWith(Recovery.LOAD)).sorted().toList();
        }
    }

    @Test
    void theWholeReadyBatchIsClaimedInOneStepBeforeTheFirstLoad() throws IOException {
        Fixture f = new Fixture(tmp);
        for (int i = 0; i < 40; i++) {
            f.drop("trades-" + i + ".jsonl", Fixture.rows("2026-11-02", "T" + i), i % 2 == 0 ? "" : "connector: other\n");
        }
        f.drop("trades-unmarked.jsonl", Fixture.rows("2026-11-02", "U"), null);
        AtomicReference<List<String>> atFirstLoad = new AtomicReference<>();
        AtomicReference<List<String>> inboxAtFirstLoad = new AtomicReference<>();
        LoadRunner fake = f.fakeLoader();
        LoadRunner watching = (config, extra, log) -> {
            try {
                if (atFirstLoad.get() == null) {
                    atFirstLoad.set(data(config.getParent()));
                    inboxAtFirstLoad.set(data(f.inbox()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return fake.run(config, extra, log);
        };
        Cycle.Report r = f.cycle(watching, null).run("t");
        assertThat(r.run()).isEqualTo("20261103T150000Z-node-a");
        assertThat(atFirstLoad.get()).as("every ready file in the run's folder before the first load").hasSize(40);
        assertThat(inboxAtFirstLoad.get()).as("only the file without its marker stays").containsExactly("trades-unmarked.jsonl");
        assertThat(f.loads).as("two tables (two connectors): two loads, no limit on the batch").hasSize(2);
        assertThat(r.files()).hasSize(40).allMatch(o -> o.outcome().equals("loaded"));
        assertThat(f.ledgerLines().get(39).path("outcome").asText()).as("the 40 claims are written first").isEqualTo("claimed");
        assertThat(f.ledgerLines().get(40).path("outcome").asText()).isEqualTo("loaded");
    }

    @Test
    void filesThatBecomeReadyDuringTheRunWaitForTheNextCycle() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        LoadRunner fake = f.fakeLoader();
        LoadRunner dropping = (config, extra, log) -> {
            try {
                if (!Files.exists(f.inbox().resolve("trades-late.jsonl"))) {
                    f.drop("trades-late.jsonl", Fixture.rows("2026-11-02", "2"), "");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return fake.run(config, extra, log);
        };
        Cycle.Report first = f.cycle(dropping, null).run("t");
        assertThat(first.files()).extracting(Cycle.FileOutcome::file).containsExactly("trades-a.jsonl");
        assertThat(f.tree("inbox")).containsExactly("inbox/trades-late.jsonl", "inbox/trades-late.jsonl.ready");
        Cycle.Report second = f.cycle(fake, null).run("t");
        assertThat(second.files()).extracting(Cycle.FileOutcome::file).containsExactly("trades-late.jsonl");
        assertThat(second.run()).as("a run of the same second gets its own folder").isEqualTo("20261103T150000Z-node-a-2");
    }

    @Test
    void aCycleDueWhileARunStillLoadsIsSkippedAndLoadNowSaysTheSame() throws Exception {
        Fixture f = new Fixture(tmp);
        f.drop("trades-a.jsonl", Fixture.rows("2026-11-02", "1"), "");
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LoadRunner fake = f.fakeLoader();
        LoadRunner slow = (config, extra, log) -> {
            loading.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fake.run(config, extra, log);
        };
        DataInbox inbox = new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), slow, f.signal(), null);
        assertThat(inbox.runNow("a.sinha")).as("off: nothing starts").isEqualTo("the data inbox is off on this server");
        inbox.start();
        try {
            assertThat(inbox.runNow("a.sinha")).as("started").isNull();
            assertThat(loading.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(inbox.runNow("b.rao")).isEqualTo("skipped: run 20261103T150000Z-node-a still loading (0 min)");
            // another server on the same folder: its cycle is not started either
            Cycle.Report other = new Cycle(f.settings, "node-b", f.clock, Fixture.ZONE, f.packs(), fake, f.signal(), null, null).run("schedule");
            assertThat(other.state()).isEqualTo("skipped");
            assertThat(other.why()).isEqualTo("run 20261103T150000Z-node-a still loading (0 min)");
            release.countDown();
            for (int i = 0; i < 300 && f.tree("inbox").size() + f.tree("inprocess").size() > 0; i++) {
                Thread.sleep(50);
            }
            for (int i = 0; i < 300 && ((List<?>) inbox.status().get("cycles")).size() < 2; i++) {
                Thread.sleep(50);
            }
        } finally {
            release.countDown();
            inbox.stop();
        }
        @SuppressWarnings("unchecked")
        List<Cycle.Report> cycles = (List<Cycle.Report>) inbox.status().get("cycles");
        assertThat(cycles).extracting(Cycle.Report::state).containsExactly("done", "skipped");
        assertThat(cycles.get(1).trigger()).isEqualTo("by b.rao");
        assertThat(cycles.get(1).why()).isEqualTo("run 20261103T150000Z-node-a still loading (0 min)");
        assertThat(f.loads).as("one load: the skipped cycles started nothing").hasSize(1);
        assertThat(f.tree("outbox")).contains("outbox/2026-11-03/RUN/trades-a.jsonl");
    }

    @Test
    void maxFilesAndMaxGbAreOffByDefaultAndBoundARunOnlyWhenSet() throws IOException {
        DataLoadSettings d = DataLoadSettings.defaults();
        assertThat(d.maxFiles()).isZero();
        assertThat(d.maxBytes()).isEqualTo(Long.MAX_VALUE);
        Fixture f = new Fixture(tmp);
        for (int i = 0; i < 5; i++) {
            f.drop("trades-" + i + ".jsonl", Fixture.rows("2026-11-02", "T" + i), "");
        }
        f.settings = new DataLoadSettings(true, f.root.toString(), null, 30, 2, null, "256m", null, null, null, null, f.lake.toString(),
                f.rules.toString(), false, null, null);
        assertThat(f.run().files()).hasSize(2);
        assertThat(f.tree("inbox")).hasSize(6);
        Map<String, Object> st = new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), f.fakeLoader(), f.signal(), null).status();
        assertThat((List<?>) st.get("waiting")).hasSize(3);
    }
}
