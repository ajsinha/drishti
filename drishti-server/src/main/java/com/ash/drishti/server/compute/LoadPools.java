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
package com.ash.drishti.server.compute;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The engines' date loads run on pools of their own (2.9.7, RESOURCES_AND_SCALE.md section 6.5), never on the common pool: a 10-second
 * load there occupied one of the {@code cores - 1} threads that parallel streams and every other {@code CompletableFuture} share. Each
 * pool is a {@link ForkJoinPool} of {@code threads} workers: bounded, and a load that waits for another (a currency's book waiting for its
 * USD book) is compensated by the pool instead of deadlocking it. Daemon threads named {@code <name>-<n>}.
 */
public final class LoadPools {

    private LoadPools() {}

    /** A pool of {@code threads} named workers. */
    public static ForkJoinPool named(String name, int threads) {
        AtomicInteger n = new AtomicInteger();
        return new ForkJoinPool(Math.max(1, threads), pool -> {
            ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            t.setName(name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, null, false);
    }

    /** What a pool is doing, for Admin, Health: its size, the loads running and queued. */
    public static java.util.Map<String, Object> describe(ForkJoinPool p) {
        return java.util.Map.of("threads", p.getParallelism(), "active", p.getActiveThreadCount(), "queued", p.getQueuedSubmissionCount()
                + p.getQueuedTaskCount());
    }
}
