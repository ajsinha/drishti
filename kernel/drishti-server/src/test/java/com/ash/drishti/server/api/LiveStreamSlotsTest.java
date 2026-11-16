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
package com.ash.drishti.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.live.LiveProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class LiveStreamSlotsTest {

    @Test
    void concurrentRequestsNeverExceedTheCapAndSlotsReleaseOnce() throws Exception {
        LiveStreamSlots slots = new LiveStreamSlots(new LiveProperties(Duration.ofMillis(50), null, 10, null));
        CountDownLatch go = new CountDownLatch(1);
        List<Future<LiveStreamSlots.Slot>> tries = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 200; i++) {
                tries.add(pool.submit(() -> {
                    go.await();
                    return slots.tryAcquire();
                }));
            }
            go.countDown();
        }
        List<LiveStreamSlots.Slot> held = new ArrayList<>();
        for (Future<LiveStreamSlots.Slot> f : tries) {
            if (f.get() != null) {
                held.add(f.get());
            }
        }
        assertThat(held).hasSize(10);
        assertThat(slots.open()).isEqualTo(10);
        held.forEach(s -> {
            s.release();
            s.release();                      // idempotent
        });
        assertThat(slots.open()).isZero();
    }
}
