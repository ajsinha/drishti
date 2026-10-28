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
package com.ash.drishti.compute.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The coordinator's scatter-gather (VAR_SHARDING.md section 6), measure-agnostic: every shard asked answers once (here or remote), the
 * partials combine in shard order whatever order the answers arrive in, one shard failing fails the whole answer; VaR's partials add, and a
 * measure with another unit (a map of netting set to exposure, as exposure might bring) combines its own way.
 */
class ShardGatherTest {

    static final ExecutorService ASK = Executors.newVirtualThreadPerTaskExecutor();

    @Test
    void everyShardAnswersOnceAndPartialsAddInShardOrder() {
        Map<Integer, AtomicInteger> calls = new ConcurrentHashMap<>();
        double[] v = ShardGather.gather(new int[] {0, 2, 3}, s -> s == 2, s -> {
            calls.computeIfAbsent(s, k -> new AtomicInteger()).incrementAndGet();
            return new double[] {s, 1};
        }, s -> {
            calls.computeIfAbsent(s, k -> new AtomicInteger()).incrementAndGet();
            try {
                Thread.sleep(s == 0 ? 50 : 0);                         // shard 0 answers last
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new double[] {s, 1};
        }, ShardGather.sum(2), ASK);
        assertThat(v).containsExactly(5, 3);
        assertThat(calls.keySet()).containsExactly(0, 2, 3);
        calls.values().forEach(n -> assertThat(n.get()).isEqualTo(1));
    }

    @Test
    void aShardWithoutAnAnswerFailsTheWholeGather() {
        assertThatThrownBy(() -> ShardGather.gather(new int[] {0, 1}, s -> false, s -> null, s -> {
            if (s == 1) {
                throw new IllegalStateException("shard 1 has no live owner");
            }
            return new double[] {1};
        }, ShardGather.sum(1), ASK)).hasMessageContaining("no live owner");
        assertThatThrownBy(() -> ShardGather.gather(new int[] {0}, s -> true, s -> null, s -> null, ShardGather.sum(1), ASK))
                .hasMessageContaining("gave no answer");
        assertThatThrownBy(() -> ShardGather.gather(new int[] {0}, s -> true, s -> new double[] {1, 2}, s -> null, ShardGather.sum(1), ASK))
                .hasMessageContaining("partial sum of 2 values");
    }

    @Test
    void anotherMeasureBringsItsOwnCombine() {
        ShardGather.Combine<Map<String, Double>> byNettingSet = new ShardGather.Combine<>() {
            @Override
            public Map<String, Double> zero() {
                return new TreeMap<>();
            }

            @Override
            public Map<String, Double> add(Map<String, Double> acc, Map<String, Double> part) {
                part.forEach((k, x) -> acc.merge(k, x, Double::sum));
                return acc;
            }
        };
        Map<String, Double> out = ShardGather.gather(new int[] {0, 1}, s -> s == 0, s -> Map.of("NS-1", 10.0), s -> Map.of("NS-2", 5.0, "NS-1", 1.0),
                byNettingSet, ASK);
        assertThat(out).containsExactly(Map.entry("NS-1", 11.0), Map.entry("NS-2", 5.0));
    }
}
