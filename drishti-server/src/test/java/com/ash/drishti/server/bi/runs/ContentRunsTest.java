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
package com.ash.drishti.server.bi.runs;

import static com.ash.drishti.server.bi.runs.RunServiceTest.VIEWER;
import static com.ash.drishti.server.bi.runs.RunServiceTest.question;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.identity.content.FileContentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BI runs in the content store (docs/architecture/DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9, BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 13.3): two in-process nodes sharing one
 * filesystem store root. A run submitted on one node has its status and result read on the other; a dead node's unfinished run is adopted by
 * one node through the compare-and-set lease; a share made on one node is seen on the other; schedules fire on one node only; the import.
 */
class ContentRunsTest {

    @TempDir
    Path dir;
    RunService a;
    RunService b;

    RunService node(String name) {
        RunsProperties p = RunsProperties.defaults(dir.resolve(name));
        FileContentStore store = new FileContentStore(dir.resolve("fstore"));
        return new RunService(p, new ContentRunRecords(store, 0), new ContentRunStore(p, store), Clock.systemUTC(), name, RunMetrics.NONE);
    }

    @AfterEach
    void tearDown() {
        for (RunService r : new RunService[] {a, b}) {
            if (r != null) {
                r.close();
            }
        }
    }

    static Run waitFor(RunService on, String id, RunState state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Optional<Run> r = on.records().get(id);
            if (r.isPresent() && r.get().state() == state) {
                return r.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("run " + id + " did not reach " + state + ": " + on.records().get(id));
    }

    @Test
    void aRunSubmittedOnOneNodeHasItsStatusAndResultReadOnTheOther() throws Exception {
        a = node("a");
        b = node("b");
        RunService.Outcome o = a.submit(question(VIEWER, "sig-v", "v1", new AtomicInteger(), 300, "10"), new RunService.Options(Duration.ZERO, true));
        assertThat(o.inline()).isFalse();
        assertThat(b.records().get(o.run().id())).as("visible on b at once").isPresent();
        Run done = waitFor(b, o.run().id(), RunState.DONE);
        assertThat(b.result(done, "sig-v")).containsEntry("rowCount", 1);
        assertThat(b.records().owned("viewer", null, 10)).extracting(Run::id).containsExactly(o.run().id());
        assertThatThrownBy(() -> b.result(done, "sig-other")).as("never across signatures").isInstanceOf(com.ash.drishti.common.DrishtiException.class);
    }

    @Test
    void aDeadNodesUnfinishedRunIsAdoptedByOneNodeThroughTheStoreLease() throws Exception {
        a = node("a");
        b = node("b");
        RunService c = node("c");
        Instant t = Instant.now().minusSeconds(120);
        Run.Builder rb = Run.planned(RunId.next("a", t, new java.util.SplittableRandom(1)), "viewer", "viewer", "mq", "{\"q\":2}", "a".repeat(64), "sig-v",
                Tier.AD_HOC_HEAVY, "local", "{}", "a", t).moveTo(RunState.QUEUED, null, null, t).moveTo(RunState.RUNNING, null, null, t).toBuilder();
        rb.leaseUntil = t.plusSeconds(30);                                   // a held it, then died: the lease lapsed
        Run orphan = rb.build();
        a.records().save(orphan);
        a.close();
        a = null;

        AtomicInteger calls = new AtomicInteger();
        b.replay(r -> Optional.of(question(VIEWER, "sig-v", null, calls, 10, "1")));
        c.replay(r -> Optional.of(question(VIEWER, "sig-v", null, calls, 10, "1")));
        int byB = b.adoptOrphans(n -> false, k -> "b");
        int byC = c.adoptOrphans(n -> false, k -> "c");
        assertThat(byB + byC).as("exactly one node adopts it").isEqualTo(1);
        Run again = waitFor(c, orphan.id(), RunState.DONE);
        assertThat(again.node()).isEqualTo(byB == 1 ? "b" : "c");
        assertThat(again.attempt()).isEqualTo(orphan.attempt() + 1);
        assertThat(calls.get()).isEqualTo(1);
        c.close();
    }

    @Test
    void aLeaseIsRenewedOnlyByItsHolderAndALateSaveFromTheLoserIsDropped() {
        FileContentStore store = new FileContentStore(dir.resolve("fstore"));
        ContentRunRecords ra = new ContentRunRecords(store, 0);
        ContentRunRecords rb = new ContentRunRecords(store, 0);
        Instant t = Instant.now().minusSeconds(120);
        Run.Builder x = Run.planned("r-a-1", "viewer", "viewer", "mq", "{}", "b".repeat(64), "s", Tier.AD_HOC_HEAVY, "local", "{}", "a", t)
                .moveTo(RunState.QUEUED, null, null, t).toBuilder();
        x.leaseUntil = t;
        Run run = x.build();
        ra.save(run);
        assertThat(rb.renew(run.id(), "b", Instant.now())).as("b does not hold it").isFalse();
        assertThat(rb.adopt(run.id(), "a", "b", Instant.now(), "a stopped")).isTrue();
        assertThat(ra.adopt(run.id(), "a", "a2", Instant.now(), "a stopped")).as("taken already").isFalse();
        assertThat(ra.renew(run.id(), "a", Instant.now().plusSeconds(60))).as("a lost it").isFalse();
        ra.save(run);                                                        // a's late write of its old copy
        assertThat(rb.get(run.id()).orElseThrow().node()).isEqualTo("b");
    }

    @Test
    void aShareMadeOnOneNodeIsSeenOnTheOtherAndASchedulesFiresOnOneNode() {
        FileContentStore store = new FileContentStore(dir.resolve("fstore"));
        ContentRunRecords.Shares sa = new ContentRunRecords.Shares(store);
        ContentRunRecords.Shares sb = new ContentRunRecords.Shares(new FileContentStore(dir.resolve("fstore")));
        sa.save(new RunShares.Share("r-a-1", "bob", "re-masked", null, "ann", Instant.now()));
        assertThat(sb.get("r-a-1", "bob")).get().extracting(RunShares.Share::mode).isEqualTo("re-masked");
        assertThat(sb.to("bob", 10)).extracting(RunShares.Share::runId).containsExactly("r-a-1");
        assertThat(sb.get("r-a-1", "eve")).as("only the recipient").isEmpty();
        assertThat(sb.remove("r-a-1", "bob")).isTrue();
        assertThat(sa.of("r-a-1")).isEmpty();

        ContentRunRecords.Schedules ca = new ContentRunRecords.Schedules(store);
        ContentRunRecords.Schedules cb = new ContentRunRecords.Schedules(new FileContentStore(dir.resolve("fstore")));
        Instant due = Instant.now().minusSeconds(5);
        RunSchedules.Schedule s = new RunSchedules.Schedule("sch-1", "ann", "r-a-1", "Desk", "{}", "{}", "mq", "trading", "every day 08:00", "latest", true,
                null, due, due);
        ca.save(s);
        RunSchedules.Schedule next = s.with(s.schedule(), true, null, due.plus(Duration.ofDays(1)));
        assertThat(cb.claim(s, next)).isTrue();
        assertThat(ca.claim(s, next)).as("fired on b already").isFalse();
    }

    @Test
    void theRowsAndTheResultFolderAreImportedOnce() throws Exception {
        FileContentStore store = new FileContentStore(dir.resolve("fstore"));
        RunRecords.Memory rows = new RunRecords.Memory();
        Instant t = Instant.now();
        Run done = Run.planned("r-old-1", "viewer", "viewer", "mq", "{}", "c".repeat(64), "sig-v", Tier.AD_HOC_HEAVY, "local", "{}", "old", t)
                .moveTo(RunState.QUEUED, null, null, t);
        rows.save(done);
        RunShares.Memory shares = new RunShares.Memory();
        shares.save(new RunShares.Share("r-old-1", "bob", "same", null, "viewer", t));
        RunsProperties p = RunsProperties.defaults(dir.resolve("old"));
        new RunStore(p).write("c".repeat(64), "sig-v", "r-old-1", "viewer", Map.of("rowCount", 1, "rows", List.of(List.of("X", 1))), 1);
        new RunStore(p).promote("desk-pnl", Map.of("answer", Map.of("rowCount", 0)), 5);

        assertThat(RunImport.run(store, rows, shares, new RunSchedules.Memory(), p.store().dir()).failed()).isZero();
        ContentRunStore results = new ContentRunStore(p, store);
        assertThat(new ContentRunRecords(store, 0).get("r-old-1")).isPresent();
        assertThat(new ContentRunRecords.Shares(store).get("r-old-1", "bob")).isPresent();
        assertThat(results.read("c".repeat(64), "sig-v")).get().extracting(m -> m.get("rowCount")).isEqualTo(1);
        assertThat(results.usageOf("viewer")).containsEntry("results", 1);
        assertThat(results.snapshotVersions("desk-pnl")).containsExactly(1);

        new ContentRunRecords(store, 0).save(done.moveTo(RunState.CANCELLED, null, "gone", t));
        store.deleteLatest(ContentRunRecords.key("r-old-1"));
        assertThat(RunImport.run(store, rows, shares, null, p.store().dir())).isEqualTo(new RunImport.Result(0, 0, 0));
        assertThat(new ContentRunRecords(store, 0).get("r-old-1")).as("a deleted run does not come back").isEmpty();
        assertThat(Files.isDirectory(p.store().dir().resolve("results"))).as("the files are kept").isTrue();
    }
}
