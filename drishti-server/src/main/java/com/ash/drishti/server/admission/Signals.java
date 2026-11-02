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
