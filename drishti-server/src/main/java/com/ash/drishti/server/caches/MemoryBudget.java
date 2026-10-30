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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One cache budget for the heap (RESOURCES_AND_SCALE.md section 5.2, D3, 2.9.7; decision PO-2): a share of {@code -Xmx}
 * ({@code drishti.memory.cache-share}, 0.5) divided into weighted pools. Every Caffeine cache weighed in bytes that joins a pool gets its
 * pool's share as its maximum (split evenly between the pool's members, or by their own limits when those are smaller), set at start and
 * again on a change; when the heap after a collection passes {@code squeeze-at} (0.85 of {@code -Xmx}) the pools with the highest
 * squeeze order are halved for a minute. The open dates' skeletons are not in it (bounded by {@code hot-dates}); hot files and the page
 * cache are outside the heap (the disk budget covers them).
 */
public final class MemoryBudget {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryBudget.class);

    /** A pool: its weight, the order it shrinks in under pressure (higher first) and its members. */
    public record Pool(String name, int weight, int squeezeOrder, List<Member> members) {}

    /** A cache in a pool: its name, its current weighed size, its own limit (0: none) and how to set its maximum. */
    public interface Member {
        String name();

        long weightedSize();

        long ownLimit();

        void maximum(long bytes);
    }

    /** The proposed pools and weights (section 5.2): name, weight, squeeze order. */
    public static final Map<String, int[]> DEFAULT_WEIGHTS = defaults();

    private static Map<String, int[]> defaults() {
        Map<String, int[]> m = new LinkedHashMap<>();
        m.put("connector.idmaps", new int[] {20, 2});
        m.put("connector.columns", new int[] {20, 2});
        m.put("connector.documents", new int[] {6, 3});
        m.put("connector.parts", new int[] {4, 3});
        m.put("engine.answers", new int[] {20, 1});
        m.put("engine.layouts", new int[] {10, 1});
        m.put("small", new int[] {20, 4});
        return m;
    }

    private final double share;
    private final double squeezeAt;
    private final long heapMax;
    private final Map<String, Pool> pools = new LinkedHashMap<>();
    private volatile long squeezedUntil;
    private final java.util.concurrent.atomic.AtomicInteger squeezes = new java.util.concurrent.atomic.AtomicInteger();

    public MemoryBudget(double share, Map<String, Integer> weights, double squeezeAt, long heapMax) {
        this.share = share <= 0 || share > 0.9 ? 0.5 : share;
        this.squeezeAt = squeezeAt <= 0 || squeezeAt >= 1 ? 0.85 : squeezeAt;
        this.heapMax = heapMax;
        DEFAULT_WEIGHTS.forEach((n, w) -> {
            int weight = weights == null || weights.get(n) == null || weights.get(n) < 0 ? w[0] : weights.get(n);
            pools.put(n, new Pool(n, weight, w[1], new CopyOnWriteArrayList<>()));
        });
    }

    /** The whole budget in bytes. */
    public long budgetBytes() {
        return (long) (heapMax * share);
    }

    /** A pool's share of the budget, halved while squeezed when it is among the first to shrink. */
    public long poolBytes(String pool) {
        Pool p = pools.get(pool);
        if (p == null) {
            return 0;
        }
        int total = pools.values().stream().mapToInt(Pool::weight).sum();
        long bytes = total == 0 ? 0 : budgetBytes() * p.weight() / total;
        return System.currentTimeMillis() < squeezedUntil && p.squeezeOrder() >= 2 ? bytes / 2 : bytes;
    }

    /** Joins a cache to a pool and gives every member of it its share now. */
    public void join(String pool, Member m) {
        Pool p = pools.get(pool);
        if (p == null) {
            throw new IllegalArgumentException("no memory pool " + pool + "; the pools: " + pools.keySet());
        }
        p.members().removeIf(x -> x.name().equals(m.name()));
        p.members().add(m);
        apply(p);
    }

    /** Sets each member's maximum: the pool's bytes split evenly, never above a member's own limit. */
    void apply(Pool p) {
        List<Member> ms = p.members();
        if (ms.isEmpty()) {
            return;
        }
        long each = poolBytes(p.name()) / ms.size();
        for (Member m : ms) {
            m.maximum(m.ownLimit() > 0 ? Math.min(m.ownLimit(), each) : each);
        }
    }

    /** The heap after a collection passed {@code squeeze-at}: the lower-priority pools are halved for a minute. */
    public void squeeze() {
        squeezedUntil = System.currentTimeMillis() + 60_000;
        squeezes.incrementAndGet();
        pools.values().forEach(this::apply);
        LOG.warn("memory: the heap after a collection passed {}% of -Xmx: the connectors' and small caches are halved for a minute",
                Math.round(squeezeAt * 100));
    }

    /** Back to the full shares (the minute passed). */
    public void restore() {
        if (squeezedUntil != 0 && System.currentTimeMillis() >= squeezedUntil) {
            squeezedUntil = 0;
            pools.values().forEach(this::apply);
        }
    }

    public double squeezeAt() {
        return squeezeAt;
    }

    /** What Admin, Caches shows above the table: the budget, its use, the heap, each pool. */
    public Map<String, Object> describe() {
        Runtime r = Runtime.getRuntime();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("budgetBytes", budgetBytes());
        long used = pools.values().stream().flatMap(p -> p.members().stream()).mapToLong(Member::weightedSize).sum();
        m.put("usedBytes", used);
        m.put("cacheShare", share);
        m.put("heapMaxBytes", heapMax);
        m.put("heapUsedBytes", r.totalMemory() - r.freeMemory());
        m.put("squeezeAt", squeezeAt);
        m.put("squeezes", squeezes.get());
        m.put("squeezed", System.currentTimeMillis() < squeezedUntil);
        List<Map<String, Object>> ps = new java.util.ArrayList<>();
        for (Pool p : pools.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("pool", p.name());
            x.put("weight", p.weight());
            x.put("limitBytes", poolBytes(p.name()));
            x.put("usedBytes", p.members().stream().mapToLong(Member::weightedSize).sum());
            x.put("members", p.members().stream().map(Member::name).toList());
            ps.add(x);
        }
        m.put("pools", ps);
        return m;
    }

    /** A Caffeine cache weighed in bytes as a pool member. */
    public static Member of(String name, com.github.benmanes.caffeine.cache.Cache<?, ?> c, long ownLimit) {
        return new Member() {
            public String name() {
                return name;
            }

            public long weightedSize() {
                return c.policy().eviction().flatMap(e -> e.weightedSize().isPresent() ? java.util.Optional.of(e.weightedSize().getAsLong())
                        : java.util.Optional.empty()).orElse(0L);
            }

            public long ownLimit() {
                return ownLimit;
            }

            public void maximum(long bytes) {
                c.policy().eviction().ifPresent(e -> e.setMaximum(Math.max(1, bytes)));
            }
        };
    }
}
