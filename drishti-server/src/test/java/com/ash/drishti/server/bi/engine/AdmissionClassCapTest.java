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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The class caps of admission (docs/architecture/BI_RUNS.md section 10.1, test 19.1 AdmissionClassCapTest): eight background waiters never hold more
 * than two of eight slots, an interactive question is admitted at once while they wait, standing work has its own cap, and an interrupted waiter (a
 * cancelled run) leaves the line at once.
 */
class AdmissionClassCapTest {

    @Test
    void eightBackgroundWaitersNeverTakeMoreThanTwoSlotsAndInteractiveWorkIsAdmittedAtOnce() throws Exception {
        Admission a = new Admission(8, 3, 1.0, 0.25, 0.25);
        assertThat(a.classCap(QueryContext.WorkClass.BACKGROUND)).isEqualTo(2);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> bg = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            bg.add(pool.submit(() -> {
                try (Admission.Ticket t = a.enter("runner", "m" + System.nanoTime(), QueryContext.WorkClass.BACKGROUND, Duration.ofSeconds(30))) {
                    most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    release.await(10, TimeUnit.SECONDS);
                    inside.decrementAndGet();
                }
                return null;
            }));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (inside.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(50);
        assertThat(inside.get()).isEqualTo(2);
        long t0 = System.nanoTime();
        for (int i = 0; i < 6; i++) {                                         // the six slots background work may not take
            Admission.Ticket t = a.enter("probe" + i, "probe-model-" + i, QueryContext.WorkClass.INTERACTIVE, Duration.ofMillis(100));
            t.close();
        }
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(100);
        @SuppressWarnings("unchecked")
        Map<String, Object> bgStats = (Map<String, Object>) ((Map<String, Object>) a.stats().get("byClass")).get("background");
        assertThat(bgStats).containsEntry("running", 2).containsEntry("cap", 2).containsEntry("waiting", 6L);
        release.countDown();
        for (Future<?> f : bg) {
            f.get(20, TimeUnit.SECONDS);
        }
        assertThat(most.get()).isEqualTo(2);
        pool.shutdownNow();
    }

    @Test
    void standingWorkHasItsOwnCapAndTheBusyReasonNamesIt() {
        Admission a = new Admission(8, 3, 1.0, 0.25, 0.25);
        Admission.Ticket s1 = a.enter("u", "m1", QueryContext.WorkClass.STANDING, Duration.ofMillis(10));
        Admission.Ticket s2 = a.enter("u", "m2", QueryContext.WorkClass.STANDING, Duration.ofMillis(10));
        assertThatThrownBy(() -> a.enter("u", "m3", QueryContext.WorkClass.STANDING, Duration.ofMillis(20)))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("standing-share");
        Admission.Ticket b1 = a.enter("u", "m4", QueryContext.WorkClass.BACKGROUND, Duration.ofMillis(10));
        b1.close();
        s1.close();
        s2.close();
    }

    @Test
    void withoutSharesEveryClassMayTakeEverySlotAsBefore() {
        Admission a = new Admission(2, 3, 1.0);
        Admission.Ticket b1 = a.enter("u", "m", QueryContext.WorkClass.BACKGROUND, Duration.ofMillis(10));
        Admission.Ticket b2 = a.enter("u", "m", QueryContext.WorkClass.BACKGROUND, Duration.ofMillis(10));
        assertThat(a.stats()).containsEntry("running", 2);
        b1.close();
        b2.close();
    }

    @Test
    void anInterruptedWaiterLeavesTheLineAtOnce() throws Exception {
        Admission a = new Admission(1, 3, 1.0, 1.0, 1.0);
        Admission.Ticket held = a.enter("u", "m", QueryContext.WorkClass.BACKGROUND, Duration.ofMillis(10));
        AtomicInteger refused = new AtomicInteger();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                a.enter("v", "m2", QueryContext.WorkClass.BACKGROUND, Duration.ofSeconds(30)).close();
            } catch (DrishtiException e) {
                refused.incrementAndGet();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (((Number) a.stats().get("waiting")).intValue() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        long t0 = System.nanoTime();
        waiter.interrupt();
        waiter.join(2000);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(100);
        assertThat(refused.get()).isEqualTo(1);
        assertThat(a.stats()).containsEntry("waiting", 0);
        held.close();
    }

    @Test
    void theInteractiveWaitP95IsMeasuredOverTheWindowAndBackgroundWaitsDoNotCount() {
        Admission a = new Admission(8, 3, 1.0, 0.25, 0.25);
        assertThat(a.interactiveWaitP95Ms(Duration.ofMinutes(5))).isNull();          // none admitted yet
        a.enter("bg", "m", QueryContext.WorkClass.BACKGROUND, Duration.ofSeconds(1)).close();
        assertThat(a.interactiveWaitP95Ms(Duration.ofMinutes(5))).isNull();
        for (int i = 0; i < 20; i++) {
            a.enter("u" + i, "m" + i, QueryContext.WorkClass.INTERACTIVE, Duration.ofSeconds(1)).close();
        }
        assertThat(a.interactiveWaitP95Ms(Duration.ofMinutes(5))).isNotNull().isGreaterThanOrEqualTo(0.0).isLessThan(1000.0);
    }
}
