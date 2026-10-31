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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The engines' load pools (2.9.7): named, bounded, off the common pool, and a load waiting for another on the same pool (a currency's
 * book waiting for its USD book) completes instead of deadlocking a pool of one.
 */
class LoadPoolsTest {

    @Test
    void namedBoundedAndANestedWaitCompletes() throws Exception {
        ForkJoinPool p = LoadPools.named("drishti-test-load", 1);
        try {
            CompletableFuture<String> outer = CompletableFuture.supplyAsync(() -> {
                CompletableFuture<String> inner = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), p);
                return Thread.currentThread().getName() + "|" + inner.join();
            }, p);
            String names = outer.get(10, TimeUnit.SECONDS);
            assertThat(names).startsWith("drishti-test-load-").contains("|drishti-test-load-");
            assertThat(p.getParallelism()).isEqualTo(1);
            assertThat(LoadPools.describe(p)).containsEntry("threads", 1);
            assertThat(names).doesNotContain("commonPool");
        } finally {
            p.shutdownNow();
        }
    }
}
