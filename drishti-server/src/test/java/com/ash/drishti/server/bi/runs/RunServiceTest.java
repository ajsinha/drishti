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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.QueryContext;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pipeline (docs/architecture/BI_RUNS.md sections 6, 8.3, 9.2, 11, 12, test 19.2 and 19.4): the inline threshold, visibility, reuse and joining
 * under one key only, masks never crossing signatures in either order, cancel, restart recovery. The time is set by a work that sleeps, so the
 * threshold is tested exactly without data.
 */
class RunServiceTest {

    @TempDir
    Path data;
    RunRecords.Memory records;
    RunService runs;
    List<String> told;

    static final Principal VIEWER = new Principal("viewer", List.of("bi-viewer"));
    static final Principal ADMIN = new Principal("ops", List.of("bi-admin"));

    @BeforeEach
    void setUp() {
        RunsProperties p = RunsProperties.defaults(data);
        records = new RunRecords.Memory();
        runs = new RunService(p, records, new RunStore(p), Clock.systemUTC(), "java-2", RunMetrics.NONE);
        told = new CopyOnWriteArrayList<>();
        runs.notifier((u, r, s) -> told.add(u + ": " + s));
    }

    @AfterEach
    void tearDown() {
        runs.close();
    }

    static RunService.Question question(Principal who, String signature, String version, AtomicInteger calls, long sleepMs, String value) {
        return new RunService.Question(who, "mq", "trading", null, "Desk P&L", null, "trading", "{\"q\":1}", null, signature, version, false, null, () -> {
            calls.incrementAndGet();
            sleep(sleepMs);
            return new RunService.Answer(Map.of("rowCount", 1, "rows", List.of(List.of("RATES-NY", value))), null, 1);
        });
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.QUERY_BUSY, "interrupted");
        }
    }

    static RunService.Options inline(long ms) {
        return new RunService.Options(Duration.ofMillis(ms), false);
    }

    @Test
    void aQuestionDoneInsideTheWaitAnswersInlineWritesNoRowAndTellsNobody() {
        AtomicInteger calls = new AtomicInteger();
        RunService.Outcome o = runs.submit(question(VIEWER, "sig-v", "v1", calls, 20, "10"), inline(1500));
        assertThat(o.inline()).isTrue();
        assertThat(o.answer().body()).containsEntry("rowCount", 1);
        assertThat(records.owned("viewer", null, 10)).isEmpty();
        assertThat(told).isEmpty();
    }

    @Test
    void aQuestionPastTheWaitBecomesAVisibleRunFinishesAndIsTold() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RunService.Outcome o = runs.submit(question(VIEWER, "sig-v", "v1", calls, 600, "10"), inline(100));
        assertThat(o.inline()).isFalse();
        assertThat(records.get(o.run().id())).isPresent();
        Run done = waitFor(o.run().id(), RunState.DONE);
        assertThat(done.rowsOut()).isEqualTo(1L);
        assertThat(runs.result(done, "sig-v")).containsEntry("rowCount", 1);
        assertThat(told).singleElement().asString().startsWith("viewer: Run finished: Desk P&L");
        assertThat(told.get(0)).doesNotContain("RATES-NY");                    // names and counts only, never a value
    }

    @Test
    void aBackgroundRunIsVisibleAtOnceAndRunsInTheBackgroundClass() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<QueryContext.WorkClass> seen = new CopyOnWriteArrayList<>();
        RunService.Question q = new RunService.Question(VIEWER, "sql", "trading", null, "Raw trades", null, "trading", "{\"s\":1}", null, "sig-v", "v1",
                false, null, () -> {
                    seen.add(QueryContext.current().workClass());
                    calls.incrementAndGet();
                    return new RunService.Answer(Map.of("rowCount", 0, "rows", List.of()), null, 0);
                });
        RunService.Outcome o = runs.submit(q, new RunService.Options(Duration.ZERO, true));
        assertThat(o.inline()).isFalse();
        waitFor(o.run().id(), RunState.DONE);
        assertThat(seen).containsExactly(QueryContext.WorkClass.BACKGROUND);
        assertThat(o.run().tier()).isEqualTo(Tier.AD_HOC_HEAVY);
    }

    @Test
    void theSameKeyIsReusedAndNothingRunsAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RunService.Outcome first = runs.submit(question(VIEWER, "sig-v", "v1", calls, 300, "10"), inline(50));
        waitFor(first.run().id(), RunState.DONE);
        RunService.Outcome again = runs.submit(question(VIEWER, "sig-v", "v1", calls, 300, "10"), inline(50));
        assertThat(again.inline()).isTrue();
        assertThat(again.run().state()).isEqualTo(RunState.REUSED);
        assertThat(again.run().reuseOf()).isEqualTo(first.run().id());
        assertThat(calls.get()).isEqualTo(1);
        RunService.Outcome newData = runs.submit(question(VIEWER, "sig-v", "v2", calls, 10, "11"), inline(1000));
        assertThat(newData.answer().body().get("rows").toString()).contains("11");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aResultIsNeverServedAcrossSignaturesViewerThenAdmin() throws Exception {
        crossSignature(VIEWER, "sig-viewer", "MASKED", ADMIN, "sig-admin", "42.5");
    }

    @Test
    void aResultIsNeverServedAcrossSignaturesAdminThenViewer() throws Exception {
        crossSignature(ADMIN, "sig-admin", "42.5", VIEWER, "sig-viewer", "MASKED");
    }

    private void crossSignature(Principal a, String sigA, String valueA, Principal b, String sigB, String valueB) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RunService.Outcome first = runs.submit(question(a, sigA, "v1", calls, 300, valueA), inline(50));
        waitFor(first.run().id(), RunState.DONE);
        RunService.Outcome second = runs.submit(question(b, sigB, "v1", calls, 10, valueB), inline(1000));
        assertThat(second.inline()).isTrue();
        assertThat(second.run().state()).isNotEqualTo(RunState.REUSED);
        assertThat(second.answer().body().get("rows").toString()).contains(valueB).doesNotContain(valueA);
        assertThat(calls.get()).isEqualTo(2);
        Run firstDone = records.get(first.run().id()).orElseThrow();
        assertThatThrownBy(() -> runs.result(firstDone, sigB)).isInstanceOf(DrishtiException.class);
        assertThat(runs.store().read(firstDone.fingerprint(), sigB)).isEmpty();
        assertThat(runs.result(firstDone, sigA).get("rows").toString()).contains(valueA);
    }

    @Test
    void aRunningTwinWithTheSameKeyIsJoinedNotRunTwice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RunService.Outcome one = runs.submit(question(VIEWER, "sig-v", "v1", calls, 500, "10"), inline(50));
        RunService.Outcome two = runs.submit(question(VIEWER, "sig-v", "v1", calls, 500, "10"), inline(50));
        assertThat(two.run().reuseOf()).isEqualTo(one.run().id());
        waitFor(one.run().id(), RunState.DONE);
        waitFor(two.run().id(), RunState.REUSED);
        assertThat(calls.get()).isEqualTo(1);
        AtomicInteger other = new AtomicInteger();
        RunService.Outcome three = runs.submit(question(ADMIN, "sig-admin", "v1", other, 10, "x"), inline(1000));
        assertThat(three.run().reuseOf()).isNull();                            // another signature never joins
        assertThat(other.get()).isEqualTo(1);
    }

    @Test
    void cancellingARunningRunStopsItsWorkAtOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        RunService.Question q = new RunService.Question(VIEWER, "mq", "trading", null, "Slow", null, "trading", "{\"slow\":1}", null, "sig-v", "v1", false,
                null, () -> {
                    started.countDown();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        interrupted.incrementAndGet();
                        Thread.currentThread().interrupt();
                        throw new DrishtiException(ErrorCode.QUERY_BUSY, "interrupted");
                    }
                    return new RunService.Answer(Map.of(), null, 0);
                });
        RunService.Outcome o = runs.submit(q, inline(50));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        Run c = runs.cancel(o.run().id(), "viewer", false, null);
        assertThat(c.state()).isEqualTo(RunState.CANCELLED);
        assertThat(c.reason()).isEqualTo("cancelled by you");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (interrupted.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(interrupted.get()).isEqualTo(1);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1000);
        assertThatThrownBy(() -> runs.cancel(o.run().id(), "mallory", false, null)).isInstanceOf(DrishtiException.class);
    }

    @Test
    void anInteractionThatWouldNeedARunIsRefusedWithDrs9089() {
        RunService.Question q = new RunService.Question(VIEWER, "report", "rpt:x", "p/v", null, null, null, "{\"i\":1}", null, "sig-v", null, true, null, () -> {
            throw new DrishtiException(ErrorCode.QUERY_COST, "the query would read about 9000000000 rows");
        });
        assertThatThrownBy(() -> runs.submit(q, inline(500))).isInstanceOf(RunProblem.class)
                .satisfies(e -> assertThat(((RunProblem) e).errorCode()).isEqualTo(ErrorCode.RUN_NEEDED));
    }

    @Test
    void aTooLargeQuestionWithoutInteractionBecomesABackgroundRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RunService.Question q = new RunService.Question(VIEWER, "mq", "trading", null, "Big", null, "trading", "{\"big\":1}", null, "sig-v", "v1", false, null,
                () -> {
                    if (calls.incrementAndGet() == 1 && QueryContext.current().workClass() == QueryContext.WorkClass.INTERACTIVE) {
                        throw new DrishtiException(ErrorCode.QUERY_COST, "too many rows for an interactive answer");
                    }
                    return new RunService.Answer(Map.of("rowCount", 0), null, 0);
                });
        RunService.Outcome o = runs.submit(q, inline(1000));
        assertThat(o.inline()).isFalse();
        assertThat(o.run().tier()).isEqualTo(Tier.AD_HOC_HEAVY);
        waitFor(o.run().id(), RunState.DONE);
    }

    @Test
    void atStartUnfinishedRunsAreStartedAgainOrFailedWithAReason() throws Exception {
        Instant t = Instant.now();
        Run running = Run.planned(RunId.next("java-2", t, new java.util.SplittableRandom(1)), "viewer", "viewer", "mq", "{\"q\":2}", "a".repeat(64), "sig-v",
                Tier.AD_HOC_HEAVY, "local", "{}", "java-2", t).moveTo(RunState.QUEUED, null, null, t).moveTo(RunState.RUNNING, null, null, t);
        Run exhausted = Run.planned(RunId.next("java-2", t, new java.util.SplittableRandom(2)), "viewer", "viewer", "mq", "{\"q\":3}", "b".repeat(64),
                "sig-v", Tier.AD_HOC_HEAVY, "local", "{}", "java-2", t).moveTo(RunState.RUNNING, null, null, t).moveTo(RunState.QUEUED, null, null, t)
                .moveTo(RunState.RUNNING, null, null, t);
        Run queuedOther = Run.planned(RunId.next("java-2", t, new java.util.SplittableRandom(3)), "bob", "bob", "report", "{\"q\":4}", "c".repeat(64), "sig-b",
                Tier.AD_HOC_HEAVY, "local", "{}", "java-2", t).moveTo(RunState.QUEUED, null, null, t);
        records.save(running);
        records.save(exhausted);
        records.save(queuedOther);
        AtomicInteger calls = new AtomicInteger();
        runs.replay(r -> r.id().equals(running.id()) || r.id().equals(exhausted.id())
                ? Optional.of(question(VIEWER, "sig-v", null, calls, 10, "1")) : Optional.empty());
        assertThat(runs.recover()).isEqualTo(3);
        Run again = waitFor(running.id(), RunState.DONE);
        assertThat(again.attempt()).isEqualTo(2);
        Run failed = records.get(exhausted.id()).orElseThrow();
        assertThat(failed.state()).isEqualTo(RunState.FAILED);
        assertThat(failed.code()).isEqualTo("DRS-9085");
        assertThat(failed.reason()).contains("2 times");
        Run notReplayable = records.get(queuedOther.id()).orElseThrow();
        assertThat(notReplayable.state()).isEqualTo(RunState.FAILED);
        assertThat(notReplayable.reason()).contains("re-run it");
        assertThat(records.inStates(List.of(RunState.RUNNING, RunState.QUEUED, RunState.STORING), 10)).isEmpty();
        assertThat(told).anyMatch(s -> s.startsWith("viewer: Your run")).anyMatch(s -> s.startsWith("bob: Run failed"));
    }

    @Test
    void aPersonPastTheirQueueLimitIsRefusedWithDrs9081() {
        for (int i = 0; i < 5; i++) {
            Instant t = Instant.now();
            records.save(Run.planned(RunId.next("java-2", t, new java.util.SplittableRandom(i)), "viewer", "viewer", "mq", "{}", "d".repeat(64), "s",
                    Tier.AD_HOC_HEAVY, "local", "{}", "java-2", t).moveTo(RunState.QUEUED, null, null, t));
        }
        assertThatThrownBy(() -> runs.submit(question(VIEWER, "sig", "v", new AtomicInteger(), 0, "1"), new RunService.Options(Duration.ZERO, true)))
                .isInstanceOf(RunProblem.class).satisfies(e -> assertThat(((RunProblem) e).errorCode()).isEqualTo(ErrorCode.RUN_QUEUE_FULL));
    }

    /** The data version the sources give a planned question: the vector's hash (RunSources.version), the same after a restart for unchanged data. */
    static String dv(String day2) {
        DataVersionVector v = new DataVersionVector().put("trade-endur", "trade", java.time.LocalDate.of(2026, 10, 8), "v1")
                .put("trade-endur", "trade", java.time.LocalDate.of(2026, 10, 9), day2);
        return "dv:" + v.hash() + ":model-hash";
    }

    @Test
    void aStoredResultIsReusedAfterARestartWhileTheDataIsUnchangedAndNeverWhenItChanged() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RunService.Outcome first = runs.submit(question(VIEWER, "sig-v", dv("v1"), calls, 10, "10"), new RunService.Options(Duration.ZERO, true));
        waitFor(first.run().id(), RunState.DONE);
        runs.close();
        // a new process: new in-memory flights, the same result store and records
        RunsProperties p = RunsProperties.defaults(data);
        runs = new RunService(p, records, new RunStore(p), Clock.systemUTC(), "java-2", RunMetrics.NONE);
        RunService.Outcome again = runs.submit(question(VIEWER, "sig-v", dv("v1"), calls, 10, "10"), inline(1000));
        assertThat(again.run().state()).isEqualTo(RunState.REUSED);
        assertThat(calls).hasValue(1);
        // 2026-10-09 restated: another key, so it runs
        RunService.Outcome restated = runs.submit(question(VIEWER, "sig-v", dv("v2"), calls, 10, "11"), inline(1000));
        assertThat(restated.run().state()).isEqualTo(RunState.DONE);
        assertThat(calls).hasValue(2);
    }

    static RunPlanner.Planned heavy(boolean confirm, java.util.Optional<ErrorCode> refusal) {
        RunEstimate e = new RunEstimate(80L, 80L, 2560L, 3L, 1.0, 4.0, RunEstimate.Basis.DELTA_STATS, 2, 2, false, 0, "2 business dates of trades");
        return new RunPlanner.Planned(e, new RunEstimator.Decision(Tier.AD_HOC_HEAVY, confirm, refusal), new DataVersionVector(), List.of(), null);
    }

    static RunService.Question planned(AtomicInteger calls, RunPlanner.Planned plan, boolean interaction) {
        return new RunService.Question(VIEWER, "mq", "trading", null, "Desk P&L", null, "trading", "{\"q\":2}", null, "sig-v", null, interaction, null, () -> {
            calls.incrementAndGet();
            return new RunService.Answer(Map.of("rowCount", 1, "rows", List.of()), null, 1);
        }, plan);
    }

    @Test
    void anAdHocHeavyQuestionAboveTheThresholdAsksFirstAndRunsOnceConfirmed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RunProblem asked = org.assertj.core.api.Assertions.catchThrowableOfType(RunProblem.class,
                () -> runs.submit(planned(calls, heavy(true, java.util.Optional.empty()), false), inline(1000)));
        assertThat(asked.errorCode()).isEqualTo(ErrorCode.RUN_CONFIRM);
        String id = String.valueOf(asked.properties().get("runId"));
        String token = String.valueOf(asked.properties().get("confirmToken"));
        assertThat(((Map<?, ?>) asked.properties().get("run")).get("id")).isEqualTo(id);
        assertThat(((Map<?, ?>) asked.properties().get("estimate")).get("basis")).isEqualTo("delta-stats");
        assertThat(records.get(id).orElseThrow().state()).isEqualTo(RunState.AWAITING_CONFIRM);
        assertThat(runs.confirmToken(id, "viewer")).isEqualTo(token);
        assertThat(runs.confirmToken(id, "someone-else")).isNull();
        assertThat(calls).hasValue(0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runs.confirm(id, "viewer", "wrong")).isInstanceOf(DrishtiException.class);
        // the question asked again with its token confirms the waiting run (no second run)
        Run queued = runs.confirmByToken("viewer", token).orElseThrow();
        assertThat(queued.id()).isEqualTo(id);
        waitFor(id, RunState.DONE);
        assertThat(calls).hasValue(1);
        // confirmed in advance (options.confirm, the re-ask after DRS-9089 with interaction false): 202 at once, no question
        RunService.Outcome o = runs.submit(planned(calls, heavy(true, java.util.Optional.empty()), false), new RunService.Options(Duration.ofSeconds(1), false, true));
        assertThat(o.inline()).isFalse();
        waitFor(o.run().id(), RunState.DONE);
    }

    @Test
    void refusalsComeAtPlanTimeAndNothingRuns() {
        AtomicInteger calls = new AtomicInteger();
        RunProblem big = org.assertj.core.api.Assertions.catchThrowableOfType(RunProblem.class,
                () -> runs.submit(planned(calls, heavy(false, java.util.Optional.of(ErrorCode.RUN_TOO_LARGE)), false), inline(1000)));
        assertThat(big.errorCode()).isEqualTo(ErrorCode.RUN_TOO_LARGE);
        RunProblem needed = org.assertj.core.api.Assertions.catchThrowableOfType(RunProblem.class,
                () -> runs.submit(planned(calls, heavy(false, java.util.Optional.of(ErrorCode.RUN_NEEDED)), true), inline(1000)));
        assertThat(needed.errorCode()).isEqualTo(ErrorCode.RUN_NEEDED);
        assertThat(needed.properties()).containsKey("estimate");
        assertThat(calls).hasValue(0);
    }

    Run waitFor(String id, RunState state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Optional<Run> r = records.get(id);
            if (r.isPresent() && r.get().state() == state) {
                return r.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run " + id + " never reached " + state.wire() + ": " + records.get(id).map(r -> r.state().wire() + " " + r.reason()).orElse("no row"));
    }
}
