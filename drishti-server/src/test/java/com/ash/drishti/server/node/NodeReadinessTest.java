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
package com.ash.drishti.server.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/**
 * Readiness that means ready (CD1) and the drain before a stop (CD2) on their own: the gates and their switches, OUT_OF_SERVICE before DOWN,
 * the newest date opened by the check and not waited for when it cannot open, and a node safe to stop only when every part is.
 */
class NodeReadinessTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    /** A part whose hand-over the test finishes by hand. */
    static final class Part implements Drainable {
        final String name;
        volatile boolean draining;
        volatile boolean handedOver;

        Part(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public void drain(boolean on) {
            draining = on;
            handedOver = false;
        }

        public boolean draining() {
            return draining;
        }

        public boolean safeToStop() {
            return draining && handedOver;
        }

        public String detail() {
            return handedOver ? "nothing left" : "handing over";
        }
    }

    static ReadinessGate gate(String name, String kind, AtomicReference<List<String>> waits) {
        return new ReadinessGate() {
            public String name() {
                return name;
            }

            public String kind() {
                return kind;
            }

            public List<String> waitingFor() {
                return waits.get();
            }
        };
    }

    @Test
    void readinessIsDownWhileAGateWaitsOutOfServiceWhileDrainedAndEachGateCanBeSwitchedOff() {
        AtomicReference<List<String>> joining = new AtomicReference<>(List.of("net: joining"));
        AtomicReference<List<String>> shards = new AtomicReference<>(List.of());
        AtomicReference<NodeProperties.Readiness> settings = new AtomicReference<>(new NodeProperties.Readiness(null, null, null, null));
        Part var = new Part("var-shards");
        NodeDrain drain = new NodeDrain(() -> List.of(var));
        NodeReadiness r = new NodeReadiness(settings::get, () -> List.of(gate("net", "joining", joining), gate("var-shards", "shards", shards)), drain);

        assertThat(r.status()).isEqualTo(Status.DOWN);
        assertThat(r.health().getDetails().get("waitingFor")).isEqualTo(List.of("net: joining"));
        settings.set(new NodeProperties.Readiness(true, false, true, true));
        assertThat(r.status()).as("joining switched off").isEqualTo(Status.UP);
        shards.set(List.of("var-shards: S3 of 2026-09-30 is owned here and not ready yet"));
        assertThat(r.status()).isEqualTo(Status.DOWN);
        assertThat(r.waiting()).containsOnlyKeys("var-shards");

        drain.drain(true);
        assertThat(r.status()).as("drained comes before waiting").isEqualTo(Status.OUT_OF_SERVICE);
        drain.drain(false);
        var.draining = true;                                            // drained by the part's own endpoint (var shards --drain)
        assertThat(r.status()).isEqualTo(Status.OUT_OF_SERVICE);
        var.draining = false;

        settings.set(new NodeProperties.Readiness(false, true, true, true));
        assertThat(r.status()).as("readiness switched off: Spring's alone").isEqualTo(Status.UP);
        assertThat(r.describe()).containsEntry("status", "UP").containsEntry("enabled", false);
    }

    @Test
    void aNodeIsSafeToStopOnlyWhenDrainedAndEveryPartHasHandedItsWorkOver() {
        Part net = new Part("net");
        Part var = new Part("var-shards");
        NodeDrain drain = new NodeDrain(() -> List.of(net, var));
        assertThat(drain.safeToStop()).as("not drained").isFalse();
        Map<String, Object> s = drain.drain(true);
        assertThat(s).containsEntry("drained", true).containsEntry("safeToStop", false);
        assertThat(net.draining && var.draining).as("every part drained at once").isTrue();
        net.handedOver = true;
        assertThat(drain.safeToStop()).isFalse();
        var.handedOver = true;
        assertThat(drain.safeToStop()).isTrue();
        assertThat(drain.status()).containsEntry("safeToStop", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) drain.status().get("parts");
        assertThat(parts).extracting(m -> m.get("name")).containsExactly("net", "var-shards");
        drain.drain(false);
        assertThat(net.draining || var.draining || drain.drained()).isFalse();
        assertThat(drain.status()).containsEntry("safeToStop", false).containsEntry("since", null);
    }

    @Test
    void aPartThatFailsNeitherStopsTheOthersNorCountsAsSafe() {
        Part ok = new Part("net");
        Drainable broken = new Drainable() {
            public String name() {
                return "broken";
            }

            public void drain(boolean on) {
                throw new IllegalStateException("boom");
            }

            public boolean draining() {
                throw new IllegalStateException("boom");
            }

            public boolean safeToStop() {
                throw new IllegalStateException("boom");
            }

            public String detail() {
                throw new IllegalStateException("boom");
            }
        };
        NodeDrain drain = new NodeDrain(() -> List.of(broken, ok));
        drain.drain(true);
        ok.handedOver = true;
        assertThat(ok.draining).isTrue();
        assertThat(drain.safeToStop()).isFalse();
        assertThat(String.valueOf(drain.status().get("parts"))).contains("cannot say");
    }

    @Test
    void theNewestDateIsOpenedByTheCheckAndNotWaitedForWhenItCannotOpen() throws Exception {
        AtomicBoolean open = new AtomicBoolean();
        AtomicBoolean fail = new AtomicBoolean();
        AtomicInteger opened = new AtomicInteger();
        AtomicReference<String> version = new AtomicReference<>("v1");
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<LocalDate> asked = new ArrayList<>();
        NewestDate g = new NewestDate("var", NewestDate.engine(() -> true, () -> DAY, d -> open.get(), d -> false, d -> version.get(), d -> {
            opened.incrementAndGet();
            asked.add(d);
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (fail.get()) {
                throw new IllegalStateException("its inputs cannot be read");
            }
            open.set(true);
        }));
        assertThat(g.waitingFor()).containsExactly("var: the newest business date 2026-09-30 is opening");
        assertThat(g.waitingFor()).as("still opening: not started twice").hasSize(1);
        go.countDown();
        long end = System.currentTimeMillis() + 5_000;
        while (!g.waitingFor().isEmpty() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertThat(g.waitingFor()).isEmpty();
        assertThat(opened.get()).isEqualTo(1);
        assertThat(asked).containsExactly(DAY);

        open.set(false);                                                // a new version, and this time its opening fails
        version.set("v2");
        fail.set(true);
        end = System.currentTimeMillis() + 5_000;
        while (!g.waitingFor().isEmpty() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertThat(g.waitingFor()).as("failed at v2: not waited for").isEmpty();
        assertThat(opened.get()).isEqualTo(2);
        version.set("v3");
        fail.set(false);
        assertThat(g.waitingFor()).as("new data: opened again").hasSize(1);

        NewestDate off = new NewestDate("exposure", NewestDate.engine(() -> false, () -> DAY, d -> false, d -> false, d -> "v", d -> { }));
        assertThat(off.waitingFor()).as("engine off").isEmpty();
        NewestDate none = new NewestDate("exposure", NewestDate.engine(() -> true, () -> null, d -> false, d -> false, d -> "v", d -> { }));
        assertThat(none.waitingFor()).as("no date has data").isEmpty();
        NewestDate refused = new NewestDate("var", NewestDate.engine(() -> true, () -> DAY, d -> false, d -> true, d -> "v", d -> { }));
        assertThat(refused.waitingFor()).as("refused: the date before answers").isEmpty();
    }
}
