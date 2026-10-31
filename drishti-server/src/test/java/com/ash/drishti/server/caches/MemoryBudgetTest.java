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
package com.ash.drishti.server.caches;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One cache budget (2.9.7, RESOURCES_AND_SCALE.md section 5.2, D3, PO-2): every pool filled to its limit at once stays within the share of
 * a 2 GB heap; a pool's members split its share (each held to its own limit when smaller); a squeeze halves the connectors' and small pools
 * for a minute and the answers' pool keeps its share.
 */
class MemoryBudgetTest {

    static final long HEAP = 2L << 30;

    private static Cache<Integer, byte[]> cache() {
        return Caffeine.newBuilder().executor(Runnable::run).maximumWeight(Long.MAX_VALUE / 4).weigher((Integer k, byte[] v) -> 1 << 20).build();
    }

    @Test
    void everyPoolFullStaysWithinTheShare() {
        MemoryBudget b = new MemoryBudget(0.5, Map.of(), 0.85, HEAP);
        assertThat(b.budgetBytes()).isEqualTo(HEAP / 2);
        List<Cache<Integer, byte[]>> all = new ArrayList<>();
        for (String pool : MemoryBudget.DEFAULT_WEIGHTS.keySet()) {
            for (int m = 0; m < 2; m++) {
                Cache<Integer, byte[]> c = cache();
                b.join(pool, MemoryBudget.of(pool + "." + m, c, 0));
                all.add(c);
            }
        }
        for (Cache<Integer, byte[]> c : all) {
            for (int i = 0; i < 2_000; i++) {
                c.put(i, new byte[0]);                                  // weighed 1 MB each: 2 GB a cache if nothing bounded it
            }
            c.cleanUp();
        }
        long total = all.stream().mapToLong(c -> c.policy().eviction().orElseThrow().weightedSize().orElseThrow()).sum();
        assertThat(total).isLessThanOrEqualTo(b.budgetBytes());
        assertThat(b.describe()).containsKeys("budgetBytes", "usedBytes", "pools", "heapMaxBytes");
        // the weights of section 5.2: the answers' pool 20 of 100
        assertThat(b.poolBytes("engine.answers")).isEqualTo(b.budgetBytes() * 20 / 100);
    }

    @Test
    void aMemberKeepsItsOwnLimitAndASqueezeHalvesTheLowerPools() {
        MemoryBudget b = new MemoryBudget(0.5, Map.of("engine.answers", 40), 0.85, HEAP);
        Cache<Integer, byte[]> small = cache();
        Cache<Integer, byte[]> big = cache();
        Cache<Integer, byte[]> cols = cache();
        b.join("engine.answers", MemoryBudget.of("small", small, 8L << 20));
        b.join("engine.answers", MemoryBudget.of("big", big, 0));
        b.join("connector.columns", MemoryBudget.of("cols", cols, 0));
        assertThat(small.policy().eviction().orElseThrow().getMaximum()).isEqualTo(8L << 20);
        long answers = b.poolBytes("engine.answers");
        assertThat(big.policy().eviction().orElseThrow().getMaximum()).isEqualTo(answers / 2);
        long columns = cols.policy().eviction().orElseThrow().getMaximum();
        b.squeeze();
        assertThat(cols.policy().eviction().orElseThrow().getMaximum()).isEqualTo(columns / 2);
        assertThat(big.policy().eviction().orElseThrow().getMaximum()).as("the answers are kept longest").isEqualTo(answers / 2);
        assertThat((Integer) b.describe().get("squeezes")).isEqualTo(1);
        assertThat(new MemoryProperties(null, null, null).cacheShare()).isEqualTo(0.5);
        assertThat(new MemoryProperties(null, null, null).squeezeAt()).isEqualTo(0.85);
    }
}
