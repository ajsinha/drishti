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
package com.ash.drishti.server.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Busy protection's gate (docs/guides/OPERATIONS_GUIDE.md, "Busy protection"): the heap after a collection, the load slots, the open dates' heap,
 * the request handlers and the disk driven to their levels; every refusal a {@code DRS-95xx} with {@code Retry-After}; a crowd gets one
 * decision; the operator's override with a reason, audited, ending by itself.
 */
class AdmissionGateTest {

    static final long GB = 1L << 30;

    /** Readings a test sets. */
    static final class Fake implements Signals {
        final AtomicLong heap = new AtomicLong();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicLong disk = new AtomicLong(500 * GB);

        public long heapAfterGcBytes() {
            return heap.get();
        }

        /** The concurrent cycles asked for. */
        final AtomicInteger cleanups = new AtomicInteger();

        public boolean requestCleanup() {
            cleanups.incrementAndGet();
            return true;
        }

        long max = 4 * GB;                                                // a 4 GB heap: at 85% about 600 MB is left, under memory-free-min-mb

        public long heapMaxBytes() {
            return max;
        }

        public int requestsInFlight() {
            return requests.get();
        }

        public long freeDiskBytes() {
            return disk.get();
        }

        public long totalDiskBytes() {
            return 1000 * GB;
        }

        void memory(double share) {
            heap.set((long) (share * max));
        }
    }

    final Fake sig = new Fake();
    final AtomicLong now = new AtomicLong(1_000_000L);
    final List<String> audited = new ArrayList<>();
    final AuditLog audit = new AuditLog() {
        public void record(String actor, String action, String subject, String detail) {
            audited.add(actor + "|" + action + "|" + subject + "|" + detail);
        }

        public List<Event> recent(int limit, String subject) {
            return List.of();
        }
    };
    String who = "rita";
    final AdmissionGate gate = new AdmissionGate(AdmissionProperties.defaults(), sig, now::get, audit, () -> who);

    private BusyRefusal refused(Runnable r) {
        try {
            r.run();
        } catch (BusyRefusal e) {
            return e;
        }
        return null;
    }

    @Test
    void memoryAfterACollectionStopsNewHeavyWorkFirstAndSignInsOnlyHigher() {
        sig.memory(0.70);
        assertThatCode(() -> gate.admit(Work.LOAD, "2026-10-01")).doesNotThrowAnyException();
        sig.memory(0.86);
        BusyRefusal e = refused(() -> gate.admit(Work.DOWNLOAD, "Download trade-valuation"));
        assertThat(e).isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.BUSY_DOWNLOAD);
        assertThat(e.errorCode().httpStatus()).isEqualTo(503);
        assertThat(e.retryAfterSeconds()).isEqualTo(30);
        assertThat(e.getMessage()).startsWith("DRS-9503 The download did not start: the server is short of memory: 86% in use after clean-up")
                .contains("Your selection is kept; try again in 30 seconds.");
        assertThatCode(() -> gate.admit(Work.SIGN_IN, "k.mehta", "Sign in")).as("a sign-in waits only higher").doesNotThrowAnyException();
        sig.memory(0.93);
        BusyRefusal s = refused(() -> gate.admit(Work.SIGN_IN, "k.mehta", "Sign in"));
        assertThat(s.errorCode()).isEqualTo(ErrorCode.BUSY_SIGN_IN);
        assertThat(s.retryAfterSeconds()).isEqualTo(45);
        assertThat(s.title()).isEqualTo("The server is very busy right now");
        assertThat(s.getMessage()).contains("Sign-ins are paused for a moment");
    }

    /** Signals over a {@link Signals.HeapAfterGc} fed by hand: pools Eden and Old, a heap of {@code max}. */
    private Signals reportsOf(Signals.HeapAfterGc heap, long max) {
        Fake f = sig;
        return new Signals() {
            public long heapAfterGcBytes() {
                return heap.bytes();
            }

            public boolean requestCleanup() {
                return f.requestCleanup();
            }

            public long heapMaxBytes() {
                return max;
            }

            public int requestsInFlight() {
                return 0;
            }

            public long freeDiskBytes() {
                return 500 * GB;
            }
        };
    }

    private static java.util.Map<String, Long> pools(long eden, long old) {
        return java.util.Map.of("Eden", eden, "Old", old);
    }

    @Test
    void youngOnlyReadingsNeverRefuse() {
        // the heap the gate reads is the one after a clean-up of the old generation; a fresh server has none: nothing is refused for memory
        Signals.HeapAfterGc heap = new Signals.HeapAfterGc(java.util.Set.of("Eden", "Old"), java.util.Set.of("Old"));
        long max = 4 * GB;
        AdmissionGate g = new AdmissionGate(AdmissionProperties.defaults(), reportsOf(heap, max), now::get, audit, () -> who);
        heap.onCollection("end of minor GC", pools(GB, (long) (0.50 * max)), pools(0, (long) (0.97 * max)));   // promoted: old grew
        assertThat(heap.bytes()).as("a young-only collection is no reading").isEqualTo(-1);
        assertThatCode(() -> g.admit(Work.SIGN_IN, "k.mehta", "Sign in")).doesNotThrowAnyException();
        assertThatCode(() -> g.admit(Work.DOWNLOAD, "Download trade-valuation")).doesNotThrowAnyException();
        heap.onCollection("end of minor GC", pools(GB, (long) (0.97 * max)), pools(0, (long) (0.60 * max)));   // mixed: old regions freed
        assertThat(heap.bytes()).isEqualTo((long) (0.60 * max));
        heap.onCollection("end of minor GC", pools(GB, (long) (0.60 * max)), pools(0, (long) (0.98 * max)));   // young again: ignored
        assertThat(heap.bytes()).isEqualTo((long) (0.60 * max));
        assertThatCode(() -> g.admit(Work.SIGN_IN, "k.mehta", "Sign in")).doesNotThrowAnyException();
        assertThat(sig.cleanups.get()).as("nothing asked while the reading is under the levels").isZero();
    }

    @Test
    void aHeapFullOfGarbageIsLetInAndATrulyFullOneRefusedWithoutAFullCollection() {
        Signals.HeapAfterGc heap = new Signals.HeapAfterGc(java.util.Set.of("Eden", "Old"), java.util.Set.of("Old"));
        long max = 4 * GB;
        AdmissionGate g = new AdmissionGate(AdmissionProperties.defaults(), reportsOf(heap, max), now::get, audit, () -> who);
        // the 2.9.11.b Drill A case: 462 MB live of 768 MB, read as 94% after young collections; here 60% live, 95% after a young one
        heap.onCollection("end of minor GC", pools(GB, (long) (0.90 * max)), pools(0, (long) (0.60 * max)));
        heap.onCollection("end of minor GC", pools(GB, (long) (0.60 * max)), pools(0, (long) (0.95 * max)));
        assertThatCode(() -> g.admit(Work.SIGN_IN, "k.mehta", "Sign in")).doesNotThrowAnyException();
        assertThatCode(() -> g.admit(Work.DOWNLOAD, "Download trade-valuation")).doesNotThrowAnyException();
        heap.onCollection("end of major GC", pools(GB, (long) (0.95 * max)), pools(0, (long) (0.94 * max)));   // a full one: really held
        BusyRefusal s = refused(() -> g.admit(Work.SIGN_IN, "k.mehta", "Sign in"));
        assertThat(s.errorCode()).isEqualTo(ErrorCode.BUSY_SIGN_IN);
        assertThat(s.getMessage()).contains("94% in use after clean-up");
        assertThat(sig.cleanups.get()).as("a concurrent cycle asked for (the live JVM: only when it never stops the world)").isEqualTo(1);
    }

    @Test
    void aCrowdGetsOneDecisionWithTheSecondsLeftUntilTheCauseClears() {
        sig.memory(0.90);
        BusyRefusal first = refused(() -> gate.admit(Work.BI_RUN, "BI run"));
        assertThat(first.retryAfterSeconds()).isEqualTo(60);
        now.addAndGet(20_000);
        sig.memory(0.82);                                                 // under the refuse level, over the warn level: still one decision
        BusyRefusal again = refused(() -> gate.admit(Work.BI_RUN, "BI run"));
        assertThat(again).as("remembered").isNotNull();
        assertThat(again.retryAfterSeconds()).isEqualTo(40);
        assertThat(again.getMessage()).contains("try again in 40 seconds");
        sig.memory(0.79);                                                 // under the warn level: lifted at once
        assertThatCode(() -> gate.admit(Work.BI_RUN, "BI run")).doesNotThrowAnyException();
        sig.memory(0.82);
        assertThatCode(() -> gate.admit(Work.BI_RUN, "BI run")).as("decided afresh: under the refuse level").doesNotThrowAnyException();
        sig.memory(0.90);
        refused(() -> gate.admit(Work.BI_RUN, "BI run"));
        now.addAndGet(61_000);                                            // the time is up: decided afresh
        sig.memory(0.83);
        assertThatCode(() -> gate.admit(Work.BI_RUN, "BI run")).doesNotThrowAnyException();
    }

    @Test
    void loadSlotsFullMakeTheNextDateWaitUntilALoadEnds() {
        AdmissionGate.Loads var = gate.loads("var", () -> 2);
        List<AdmissionGate.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 4; i++) {                                     // load-threads 2 running, queued-loads 2 waiting
            slots.add(gate.admitLoad(var, "2026-10-0" + (i + 1)));
        }
        assertThat(var.running()).isEqualTo(4);
        BusyRefusal e = refused(() -> gate.admitLoad(var, "2026-10-05"));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.BUSY_LOAD);
        assertThat(e.getMessage()).startsWith("DRS-9502 2026-10-05 cannot be opened just yet: 4 other dates are being opened right now")
                .contains("The dates already open keep working");
        assertThat(refused(() -> gate.admitLoad(var, "2026-10-06"))).as("one decision for the crowd").isNotNull();
        who = null;                                                       // a load the server starts itself takes a slot without waiting
        try (AdmissionGate.Slot s = gate.admitLoad(var, "2026-10-07")) {
            assertThat(var.running()).isEqualTo(5);
        }
        who = "rita";
        slots.get(0).close();
        slots.get(0).close();                                             // closing twice frees one slot
        assertThat(var.running()).isEqualTo(3);
        assertThatCode(() -> gate.admitLoad(var, "2026-10-05").close()).as("a load ended: lifted").doesNotThrowAnyException();
        slots.forEach(AdmissionGate.Slot::close);
        assertThat(var.running()).isZero();
    }

    @Test
    void theOpenDatesWellOverTheirNetMakeNewDatesWait() {
        AtomicLong used = new AtomicLong(10 * GB);
        gate.openHeap("var", used::get, () -> 10 * GB);
        AdmissionGate.Loads var = gate.loads("var", () -> 2);
        gate.admitLoad(var, "2026-10-01").close();
        used.set(13 * GB);                                                // over 1.2 x the net: the dates in use could not be closed
        BusyRefusal e = refused(() -> gate.admitLoad(var, "2026-10-02"));
        assertThat(e.because()).contains("the dates open now fill the memory set aside for them (13.0 GB of 10.0 GB)");
        assertThatCode(() -> gate.admit(Work.DOWNLOAD, "Download")).as("only date loads").doesNotThrowAnyException();
        used.set(9 * GB);
        assertThatCode(() -> gate.admitLoad(var, "2026-10-02").close()).doesNotThrowAnyException();
    }

    @Test
    void manyRequestsAtOnceAndANearlyFullDiskStopHeavyWorkNotSignIns() {
        sig.requests.set(181);                                            // 0.90 of 200
        assertThat(refused(() -> gate.admit(Work.BULK_LOAD, "Bulk load")).because()).contains("answering many requests at once (181 of 200)");
        assertThatCode(() -> gate.admit(Work.SIGN_IN, "k.mehta", "Sign in")).doesNotThrowAnyException();
        sig.requests.set(100);
        sig.disk.set(5 * GB);
        assertThat(refused(() -> gate.admit(Work.DOWNLOAD, "Download")).because()).contains("the disk is nearly full: 5.0 GB free");
        assertThatCode(() -> gate.admit(Work.BI_RUN, "BI run")).as("a BI run does not write to the local disk").doesNotThrowAnyException();
    }

    @Test
    void theOperatorsOverrideNeedsAReasonIsAuditedAndEndsByItself() {
        assertThatThrownBy(() -> gate.override(AdmissionGate.Mode.HOLD, " ", 30, "ops")).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("needs a reason");
        assertThatThrownBy(() -> gate.override(AdmissionGate.Mode.HOLD, "restart", 241, "ops")).hasMessageContaining("1 to 240 minutes");
        gate.override(AdmissionGate.Mode.of("hold"), "planned restart at 18:00", 30, "ops");
        assertThat(audited).singleElement().satisfies(a -> assertThat(a).startsWith("ops|busy-override|hold|hold for 30 min"));
        assertThat(refused(() -> gate.admit(Work.LOAD, "2026-10-01")).because()).contains("an operator is holding new heavy work")
                .contains("planned restart at 18:00");
        assertThatCode(() -> gate.admit(Work.SIGN_IN, "k.mehta", "Sign in")).doesNotThrowAnyException();
        assertThat(gate.describe().get("state")).isEqualTo("new heavy work waits");
        now.addAndGet(30 * 60_000L);                                      // ends by itself
        assertThatCode(() -> gate.admit(Work.LOAD, "2026-10-01")).doesNotThrowAnyException();
        sig.memory(0.99);
        gate.override(AdmissionGate.Mode.OPEN, "month-end sign-off, CRO approved", 10, "ops");
        assertThatCode(() -> gate.admit(Work.SIGN_IN, "k.mehta", "Sign in")).as("everything let through").doesNotThrowAnyException();
        gate.override(AdmissionGate.Mode.AUTOMATIC, null, 0, "ops");
        assertThat(refused(() -> gate.admit(Work.SIGN_IN, "k.mehta", "Sign in"))).isNotNull();
        assertThat(audited).hasSize(3);
    }

    @Test
    void healthShowsTheReadingsAgainstTheirLevelsAndTheLastRefusals() {
        gate.loads("var", () -> 3);
        sig.memory(0.93);
        refused(() -> gate.admit(Work.SIGN_IN, "k.mehta", "Sign in"));
        refused(() -> gate.admit(Work.DOWNLOAD, "Download trade-valuation 2026-09-30"));
        Map<String, Object> d = gate.describe();
        assertThat(d.get("state")).isEqualTo("new sign-ins wait");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gauges = (List<Map<String, Object>>) d.get("gauges");
        assertThat(gauges).extracting(g -> g.get("id")).containsExactly("memory", "loads", "requests", "disk");
        assertThat(gauges.get(0).get("state")).isEqualTo("wait");
        assertThat(gauges.get(0).get("words")).isEqualTo("new work waits");
        assertThat(gauges.get(3).get("state")).isEqualTo("ok");
        @SuppressWarnings("unchecked")
        Map<String, Object> refusals = (Map<String, Object>) d.get("refusals");
        assertThat(refusals.get("last15Minutes")).isEqualTo(2);
        assertThat(refusals.get("people")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) refusals.get("rows");
        assertThat(rows.get(0)).containsEntry("who", "rita").containsEntry("code", "DRS-9503").containsEntry("retryAfterSeconds", 30L);
        assertThat(rows.get(1)).containsEntry("who", "k.mehta").containsEntry("what", "Sign in");
    }

    @Test
    void offLetsEverythingInButStillReads() {
        AdmissionProperties off = new AdmissionProperties(false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        AdmissionGate g = new AdmissionGate(off, sig, now::get, null, () -> "rita");
        sig.memory(0.99);
        assertThatCode(() -> g.admit(Work.SIGN_IN, "Sign in")).doesNotThrowAnyException();
        assertThat(g.describe().get("state")).isEqualTo("off");
    }

    /**
     * The real heap: memory kept past a collection moves the reading after the collection, and a level just under it refuses new work;
     * released and collected, the reading falls and work is let in again (a small heap is enough: the levels are set from the readings).
     */
    @Test
    void theRealHeapAfterACollectionDrivesTheGate() throws Exception {
        Signals.HeapAfterGc heap = new Signals.HeapAfterGc();
        heap.listen();
        long max = Runtime.getRuntime().maxMemory();
        long before = settle(heap, -1);
        int chunks = (int) Math.max(4, Math.min(256L << 20, max / 4) / (16 << 20));
        List<byte[]> kept = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            kept.add(new byte[16 << 20]);
        }
        long retained = (long) chunks * (16 << 20);
        long after = settle(heap, before + retained / 2);
        assertThat(after).as("the reading after a collection holds what is kept").isGreaterThan(before + retained / 2);
        double level = (before + retained / 4) / (double) max;
        AdmissionProperties p = new AdmissionProperties(true, level / 2, level, Math.min(0.99, level * 2), 1 << 20, null, null, null, null,
                null, null, null, null, null, null, null);              // any free heap counts as short: the share alone decides here
        Signals live = new Signals() {
            public long heapAfterGcBytes() {
                return heap.bytes();
            }

            public long heapMaxBytes() {
                return max;
            }

            public int requestsInFlight() {
                return 0;
            }

            public long freeDiskBytes() {
                return 500 * GB;
            }
        };
        AdmissionGate g = new AdmissionGate(p, live, System::currentTimeMillis, null, () -> "rita");
        assertThat(refused(() -> g.admit(Work.LOAD, "2026-10-01"))).as("refused, not an OutOfMemoryError").isNotNull();
        assertThat(kept).hasSize(chunks);
        kept.clear();
        long released = settle(heap, -2);
        assertThat(released).isLessThan(before + retained / 4);
        AdmissionGate fresh = new AdmissionGate(p, live, System::currentTimeMillis, null, () -> "rita");
        assertThatCode(() -> fresh.admit(Work.LOAD, "2026-10-01")).doesNotThrowAnyException();
    }

    /** Collects and waits for the collector's notification; {@code atLeast} -1: any reading, -2: a lower one. */
    private static long settle(Signals.HeapAfterGc heap, long atLeast) throws InterruptedException {
        long start = heap.bytes();
        for (int i = 0; i < 50; i++) {
            System.gc();
            Thread.sleep(40);
            long b = heap.bytes();
            if (atLeast == -1 && i >= 2 || atLeast >= 0 && b >= atLeast || atLeast == -2 && b < start) {
                return b;
            }
        }
        return heap.bytes();
    }

    @Test
    void aLargeHeapPastTheShareButWithRoomLeftIsNotShortOfMemory() {
        Fake big = new Fake();
        big.max = 64 * GB;
        AdmissionGate g = new AdmissionGate(AdmissionProperties.defaults(), big, now::get, null, () -> "rita");
        big.heap.set((long) (0.90 * 64 * GB));                         // 90% of 64 GB: 6.4 GB left, over the 768 MB floor
        assertThatCode(() -> g.admit(Work.LOAD, "a date")).doesNotThrowAnyException();
        big.heap.set(64 * GB - 500L * 1024 * 1024);                     // 500 MB left: short of memory
        assertThatThrownBy(() -> g.admit(Work.LOAD, "a date")).isInstanceOf(BusyRefusal.class);
    }
}
