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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A page's records asked at once read their file's vector columns once, each gets its own vectors, a failure reaches every asker. */
class VectorBatchesTest {

    private static Map<String, Map<String, double[]>> vectors(Set<String> ids) {
        Map<String, Map<String, double[]>> out = new HashMap<>();
        ids.stream().filter(id -> !id.startsWith("missing")).forEach(id -> out.put(id, Map.of("PV", new double[] {id.hashCode()})));
        return out;
    }

    @Test
    void aPageAskedAtOnceIsOneReadAndEachGetsItsOwn() throws Exception {
        VectorBatches b = new VectorBatches();
        AtomicInteger reads = new AtomicInteger();
        Set<Set<String>> asked = ConcurrentHashMap.newKeySet();
        CountDownLatch go = new CountDownLatch(1);
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, double[]>>> got = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String id = "SPV-" + i;
                got.add(ex.submit(() -> {
                    go.await();
                    return b.get("scenario-pv|1|2026-11-06|0|[PV]", id, ids -> {
                        reads.incrementAndGet();
                        asked.add(ids);
                        return vectors(ids);
                    });
                }));
            }
            go.countDown();
            for (int i = 0; i < 100; i++) {
                assertThat(got.get(i).get()).containsOnlyKeys("PV");
                assertThat(got.get(i).get().get("PV")[0]).isEqualTo(("SPV-" + i).hashCode());
            }
        }
        assertThat(reads.get()).as("one read for the page, at most a few when the window splits it").isLessThanOrEqualTo(3);
        assertThat(asked.stream().mapToInt(Set::size).sum()).isEqualTo(100);
    }

    @Test
    void anIdTheFileDoesNotHoldHasNoVectorsAndOtherFilesAreReadApart() {
        VectorBatches b = new VectorBatches();
        AtomicInteger reads = new AtomicInteger();
        assertThat(b.get("k|1|d|0|[PV]", "missing-1", ids -> {
            reads.incrementAndGet();
            return vectors(ids);
        })).isEmpty();
        assertThat(b.get("k|1|d|1|[PV]", "SPV-1", ids -> {
            reads.incrementAndGet();
            return vectors(ids);
        })).containsOnlyKeys("PV");
        assertThat(reads.get()).isEqualTo(2);
    }

    @Test
    void aFailedReadFailsItsAskersAndTheNextAskReadsAgain() {
        VectorBatches b = new VectorBatches();
        assertThatThrownBy(() -> b.get("k", "a", ids -> {
            throw new IllegalStateException("the file is gone");
        })).hasMessageContaining("the file is gone");
        assertThat(b.get("k", "a", VectorBatchesTest::vectors)).containsOnlyKeys("PV");
    }
}
