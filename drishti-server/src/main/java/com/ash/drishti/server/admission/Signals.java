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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * What busy protection reads (docs/admin/OPERATIONS.md, "Busy protection"): the heap after the last clean-up, the requests being
 * answered, the free disk. The loads running and the open dates' heap are told to the gate by the engines themselves.
 */
public interface Signals {

    /**
     * The heap in use after the last collection that cleaned the old generation (a full collection, a mixed collection, or the end of a
     * concurrent cycle that freed old regions), in bytes; -1 while there has been none (a fresh server: nothing is refused for memory).
     * A young-only collection leaves the old generation's garbage in place, so its reading is never used: a heap mostly garbage would read
     * as nearly full.
     */
    long heapAfterGcBytes();

    /**
     * Asks the JVM, without waiting, for a clean-up of the old generation that never stops the world: a concurrent cycle, only when
     * {@code System.gc()} starts one ({@code -XX:+ExplicitGCInvokesConcurrent}), at most once every {@link #CLEANUP_EVERY_MS}; else
     * nothing. True when one was asked. A full collection is never forced (on a large heap it pauses the server for seconds).
     */
    default boolean requestCleanup() {
        return false;
    }

    /** At most one concurrent cycle asked for this often (only while a reading is over a refusal level). */
    long CLEANUP_EVERY_MS = 10_000L;

    /** {@code -Xmx}. */
    long heapMaxBytes();

    /** The requests being answered now. */
    int requestsInFlight();

    /** The free bytes on the disk of the data folder. */
    long freeDiskBytes();

    /** The size of the disk of the data folder (0: not known). */
    default long totalDiskBytes() {
        return 0;
    }

    /** The JVM's own readings: a listener on every collector's notifications, the in-flight counter, the disk of {@code dataDir}. */
    static Signals live(AtomicInteger inFlight, Path dataDir) {
        HeapAfterGc heap = new HeapAfterGc();
        heap.listen();
        long max = Runtime.getRuntime().maxMemory();
        LongSupplier disk = () -> com.ash.drishti.server.disk.DiskBudget.usable(dataDir);
        return new Signals() {
            public long heapAfterGcBytes() {
                return heap.bytes();
            }

            public boolean requestCleanup() {
                return heap.requestConcurrentCycle();
            }

            public long heapMaxBytes() {
                return max;
            }

            public int requestsInFlight() {
                return inFlight.get();
            }

            public long freeDiskBytes() {
                return disk.getAsLong();
            }

            public long totalDiskBytes() {
                return com.ash.drishti.server.disk.DiskBudget.total(dataDir);
            }
        };
    }

    /**
     * The heap after the last clean-up of the old generation: each collector's notification carries every pool's use before and after it;
     * a collection counts when it is a full one ({@code end of major GC}) or the old generation's pool is smaller after it than before (a
     * G1 mixed collection, a concurrent cycle's clean-up). A collector with no old-generation pool (single-generation ZGC, Shenandoah)
     * collects the whole heap every cycle: each cycle counts.
     */
    final class HeapAfterGc {

        private final AtomicLong after = new AtomicLong(-1);
        private final Set<String> heapPools = new HashSet<>();
        private final Set<String> oldPools = new HashSet<>();
        private final AtomicLong lastRequest = new AtomicLong(Long.MIN_VALUE / 2);
        private final boolean concurrentExplicit;

        /** The JVM's own pools (call {@link #listen()} to follow its collections). */
        HeapAfterGc() {
            for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == MemoryType.HEAP) {
                    heapPools.add(p.getName());
                    if (isOld(p.getName())) {
                        oldPools.add(p.getName());
                    }
                }
            }
            this.concurrentExplicit = explicitGcIsConcurrent();
        }

        /** Given pools (a test's): no listener, no cycle ever asked. */
        HeapAfterGc(Set<String> heap, Set<String> old) {
            heapPools.addAll(heap);
            oldPools.addAll(old);
            this.concurrentExplicit = false;
        }

        static boolean isOld(String pool) {
            return pool.contains("Old") || pool.contains("Tenured");
        }

        long bytes() {
            return after.get();
        }

        /** One collection's report: kept when it cleaned the old generation (see the class). */
        void onCollection(String gcAction, Map<String, Long> before, Map<String, Long> afterUse) {
            if (!cleansOld(gcAction, before, afterUse)) {
                return;                                                  // young only: the old generation's garbage is still counted
            }
            long sum = 0;
            for (Map.Entry<String, Long> e : afterUse.entrySet()) {
                if (heapPools.contains(e.getKey())) {
                    sum += e.getValue();
                }
            }
            after.set(sum);
        }

        private boolean cleansOld(String gcAction, Map<String, Long> before, Map<String, Long> afterUse) {
            if ("end of major GC".equals(gcAction) || oldPools.isEmpty()) {
                return true;
            }
            for (String p : oldPools) {
                Long b = before.get(p);
                Long a = afterUse.get(p);
                if (b != null && a != null && a < b) {
                    return true;
                }
            }
            return false;
        }

        void listen() {
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc instanceof NotificationEmitter em) {
                    em.addNotificationListener((n, h) -> {
                        if (!"com.sun.management.gc.notification".equals(n.getType()) || !(n.getUserData() instanceof CompositeData cd)) {
                            return;
                        }
                        com.sun.management.GarbageCollectionNotificationInfo info = com.sun.management.GarbageCollectionNotificationInfo.from(cd);
                        onCollection(info.getGcAction(), used(info.getGcInfo().getMemoryUsageBeforeGc()), used(info.getGcInfo().getMemoryUsageAfterGc()));
                    }, null, null);
                }
            }
        }

        private static Map<String, Long> used(Map<String, MemoryUsage> m) {
            Map<String, Long> out = new HashMap<>();
            m.forEach((k, v) -> out.put(k, v.getUsed()));
            return out;
        }

        /** A concurrent cycle asked for in the background (see {@link Signals#requestCleanup()}). */
        boolean requestConcurrentCycle() {
            if (!concurrentExplicit) {
                return false;
            }
            long now = System.currentTimeMillis();
            long last = lastRequest.get();
            if (now - last < CLEANUP_EVERY_MS || !lastRequest.compareAndSet(last, now)) {
                return false;
            }
            Thread.ofVirtual().name("drishti-gc-request").start(System::gc);   // with ExplicitGCInvokesConcurrent: starts a concurrent cycle
            return true;
        }

        /** True when {@code System.gc()} starts a concurrent cycle rather than a stop-the-world full collection. */
        static boolean explicitGcIsConcurrent() {
            try {
                com.sun.management.HotSpotDiagnosticMXBean hs = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
                return hs != null && "true".equals(hs.getVMOption("ExplicitGCInvokesConcurrent").getValue())
                        && !"true".equals(hs.getVMOption("DisableExplicitGC").getValue());
            } catch (RuntimeException e) {
                return false;                                            // another JVM: never ask
            }
        }
    }
}
