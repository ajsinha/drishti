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
package com.ash.drishti.compute.arrow;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;

/**
 * The process's one Arrow root allocator (Apache Arrow Java). Every Arrow buffer of the process comes from a named child of it: the IPC
 * codec ({@link ArrowIpc}) takes a short-lived child per table, Arrow Flight takes one per stream under its own capped child. Busy protection
 * reads {@link #allocated()} and {@link #peak()} so Arrow's off-heap memory is counted with the rest (docs/architecture/ARROW_FLIGHT.md Q8).
 *
 * <p>The cap is {@code drishti.arrow.max-bytes} (a system property, bytes; default: no cap beyond the JVM's direct memory). Arrow's buffers
 * are direct memory: the launchers size both from one place, {@code tools/lake/memory_caps.py} (the exchange's budget,
 * {@code DRISHTI_ARROW_EXCHANGE_MB}, 512, plus Arrow Flight's {@code memory.total-mb} when its engine is on), adding it to
 * {@code -XX:MaxDirectMemorySize} and passing the same figure here, so Arrow never takes more than the limit was raised for. Arrow's memory
 * module needs {@code --add-opens=java.base/java.nio=ALL-UNNAMED}: the server jar's manifest carries it and the test forks pass it.
 */
public final class ArrowMemory {

    private static final RootAllocator ROOT = new RootAllocator(Long.getLong("drishti.arrow.max-bytes", Long.MAX_VALUE));

    private ArrowMemory() {}

    /** The root; take a child of it for any work ({@link #child}), never allocate from the root directly. */
    public static BufferAllocator root() {
        return ROOT;
    }

    /** A child allocator with a name (shown in leak reports) and a byte limit; close it when the work ends. */
    public static BufferAllocator child(String name, long limit) {
        return ROOT.newChildAllocator(name, 0, limit <= 0 ? Long.MAX_VALUE : limit);
    }

    /** Bytes held by every Arrow buffer of the process now. */
    public static long allocated() {
        return ROOT.getAllocatedMemory();
    }

    /** The most bytes held at once since the process started. */
    public static long peak() {
        return ROOT.getPeakMemoryAllocation();
    }
}
