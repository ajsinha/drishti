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
package com.ash.drishti.server.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.common.Extensions;
import com.ash.drishti.kernel.api.explain.ExplainRequest;
import com.ash.drishti.kernel.api.explain.Explainer;
import com.ash.drishti.kernel.api.explain.Explanation;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A module's explainer is never momentarily missing (the command sweep's VAR EXPLAIN): a question asked while the server's modules still
 * load waits for them instead of hearing "no explainer of the kind"; questions asked without a pause through a module swapping versions
 * (its contributions withdrawn and added again) all answer; a kind no module brings is refused at once when nothing is changing; and a
 * Why it moved that the busy protection asked to wait leaves the rest of the explanation standing.
 */
class ExplainThroughModuleChangesTest {

    /** A module's explainer (as the banking module's VaR one), of a kind of its own. */
    record ModuleExplainer(String kind, int version) implements Explainer {
        @Override
        public String title() {
            return "a module's number";
        }

        @Override
        public List<String> measures() {
            return List.of("v");
        }

        @Override
        public Explanation explain(Principal p, ExplainRequest r) {
            return new Explanation(kind, "v", "BANK", "V").value((double) version, "USD", "2026-11-06").formula("V = " + version);
        }
    }

    final NumberExplainService svc = new NumberExplainService(List.of(), NumberExplainServiceTest.emptyProvider(), NumberExplainServiceTest.registry());

    private Map<String, Object> ask(String kind) {
        return svc.explain(null, new ExplainRequest(kind, "v", "all", Map.of("scope", "BANK")));
    }

    @Test
    void aQuestionAskedWhileTheModulesStillLoadWaitsForTheirExplainer() throws Exception {
        Runnable firstLoad = Extensions.changing();                 // the server's web layer answers; its modules are still loading
        Runnable withdraw = () -> { };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var answer = pool.submit(() -> ask("starting-kind"));
            Thread.sleep(300);
            assertThat(answer.isDone()).as("the question waits for the modules, it is not refused").isFalse();
            withdraw = Extensions.add(Explainer.class, "banking", new ModuleExplainer("starting-kind", 1));
            firstLoad.run();                                        // modulesLoaded(): the modules are in
            assertThat(answer.get(10, TimeUnit.SECONDS).get("text")).isEqualTo("1");
        } finally {
            firstLoad.run();
            withdraw.run();
            pool.shutdownNow();
        }
    }

    @Test
    void explainAnswersContinuouslyThroughAModuleSwappingVersions() throws Exception {
        AtomicInteger answers = new AtomicInteger();
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        AtomicBoolean stop = new AtomicBoolean();
        Runnable[] live = {Extensions.add(Explainer.class, "banking", new ModuleExplainer("swap-kind", 0))};
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 6; i++) {
                pool.submit(() -> {
                    while (!stop.get()) {
                        try {
                            ask("swap-kind");
                            answers.incrementAndGet();
                        } catch (RuntimeException e) {
                            failures.add(e.getMessage());
                        }
                    }
                });
            }
            for (int v = 1; v <= 200; v++) {                        // the module node's swap: unloaded, then loaded in the next version
                Runnable change = Extensions.changing();
                try {
                    live[0].run();
                    Thread.sleep(1);
                    live[0] = Extensions.add(Explainer.class, "banking", new ModuleExplainer("swap-kind", v));
                } finally {
                    change.run();
                }
            }
            while (answers.get() < 1000) {
                Thread.sleep(10);
            }
        } finally {
            stop.set(true);
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            live[0].run();
        }
        assertThat(failures).as("every question answered through 200 swaps").isEmpty();
        assertThat(answers.get()).isGreaterThanOrEqualTo(1000);
        assertThat(ask0("swap-kind")).isEqualTo("refused");             // withdrawn for good, nothing changing: refused
    }

    private String ask0(String kind) {
        try {
            ask(kind);
            return "answered";
        } catch (DrishtiException e) {
            return "refused";
        }
    }

    @Test
    void aKindNoModuleBringsIsRefusedAtOnceWhenNothingIsChanging() {
        assertThat(Extensions.settled()).isTrue();
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> ask("no-such-kind")).isInstanceOf(DrishtiException.class).hasMessageContaining("no explainer of the kind no-such-kind");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1000);
    }

    @Test
    void aBusyWhyItMovedLeavesTheRestOfTheExplanationStanding() {
        Explanation x = new Explanation("var", "var", "BANK", "VaR").value(1.0, "USD", "2026-11-06").formula("VaR = q");
        x.whyBusy("DRS-9502", "2026-11-05 cannot be opened just yet", "the server is short of memory: 93% in use after clean-up", 30);
        Map<String, Object> a = x.toMap(new ExplainRequest("var", "var", "all", Map.of("scope", "BANK")));
        assertThat(a).doesNotContainKey("code");                         // the answer is no refusal: the number and its formula stand
        assertThat(a.get("formula").toString()).contains("VaR = q");
        @SuppressWarnings("unchecked")
        Map<String, Object> why = (Map<String, Object>) a.get("why");
        assertThat(why).containsEntry("available", false).containsEntry("busy", true).containsEntry("code", "DRS-9502")
                .containsEntry("retryAfterSeconds", 30L);
        assertThat(why.get("because").toString()).contains("short of memory");
    }
}
