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
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * What busy protection reads (docs/admin/OPERATIONS.md, "Busy protection"): the heap after the last collection, the requests being
 * answered, the free disk. The loads running and the open dates' heap are told to the gate by the engines themselves.
 */
public interface Signals {

    /** The heap in use after the last collection, in bytes (before the first collection: the heap in use now). */
    long heapAfterGcBytes();

    /**
     * The heap in use after a real clean-up, read before a refusal for memory (OPERATIONS.md, "Busy protection": the levels are "after
     * clean-up"). The reading after the last collection of any kind counts what a young collection leaves in the old generation, garbage
     * included, so a heap mostly garbage reads as nearly full; this collects the whole heap first (the live JVM's at most once every
     * {@link #CLEANUP_EVERY_MS}, the last clean-up's result in between) and reads what is left. By default (a test's readings) the last
     * reading.
     */
    default long heapAfterCleanupBytes() {
        return heapAfterGcBytes();
    }

    /** At most one forced clean-up this often (only while a reading is over a refusal level); a decision in between reads its result. */
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

            public long heapAfterCleanupBytes() {
                return heap.cleanedUp();
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
     * The heap after the last collection: each collector's notification carries every pool's use after it; the heap pools' sum is kept.
     * Reading it costs nothing (a field), so every admission may read it.
     */
    final class HeapAfterGc {

        private final AtomicLong after = new AtomicLong(-1);
        private final Set<String> heapPools = new HashSet<>();

        long bytes() {
            long a = after.get();
            if (a >= 0) {
                return a;
            }
            Runtime r = Runtime.getRuntime();
            return r.totalMemory() - r.freeMemory();
        }

        private long cleanedAt = Long.MIN_VALUE / 2;
        private long cleaned = -1;

        /**
         * What a full collection leaves (what is live): one now, or the last one's when it is under {@link Signals#CLEANUP_EVERY_MS} old
         * (never the readings after young collections in between: those count the old generation's garbage again). A JVM that ignores
         * {@code System.gc()} gives the last reading.
         */
        synchronized long cleanedUp() {
            long now = System.currentTimeMillis();
            if (cleaned >= 0 && now - cleanedAt < CLEANUP_EVERY_MS) {
                return cleaned;
            }
            long before = collections();
            System.gc();                                                 // G1: a full collection (unless the JVM is told to ignore it)
            if (collections() <= before) {
                return bytes();
            }
            long used = 0;
            for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == MemoryType.HEAP) {
                    used += p.getUsage().getUsed();
                }
            }
            cleaned = used;
            cleanedAt = now;
            after.set(used);
            return used;
        }

        private static long collections() {
            long n = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                n += Math.max(0, gc.getCollectionCount());
            }
            return n;
        }

        void listen() {
            for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == MemoryType.HEAP) {
                    heapPools.add(p.getName());
                }
            }
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc instanceof NotificationEmitter em) {
                    em.addNotificationListener((n, h) -> {
                        if (!"com.sun.management.gc.notification".equals(n.getType()) || !(n.getUserData() instanceof CompositeData cd)) {
                            return;
                        }
                        com.sun.management.GarbageCollectionNotificationInfo info = com.sun.management.GarbageCollectionNotificationInfo.from(cd);
                        long sum = 0;
                        for (var e : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
                            if (heapPools.contains(e.getKey())) {
                                sum += e.getValue().getUsed();
                            }
                        }
                        after.set(sum);
                    }, null, null);
                }
            }
        }
    }
}
