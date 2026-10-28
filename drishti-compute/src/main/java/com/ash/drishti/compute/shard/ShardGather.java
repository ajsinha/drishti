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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * A coordinator's scatter-gather over a sharded date, for any measure (VAR_SHARDING.md section 6): each shard asked is answered exactly once,
 * here (its rows held on this member) or by one owner (asked side by side on {@code ask}), and the partials are combined in shard order, so the
 * same question gives the same bits whoever answered. A shard that gives no answer fails the whole gather (the remote side says why): never a
 * figure from the other shards alone. What a partial is and how two combine is the measure's: VaR's is a {@code double[]} of scenario P&amp;L
 * added element by element ({@link #sum(int)}); another measure brings its own {@link Combine}.
 */
public final class ShardGather {

    /** How a measure's partials combine. */
    public interface Combine<P> {
        /** The combination of no partial. */
        P zero();

        /** {@code acc} with {@code part} added ({@code acc} may be changed and returned). */
        P add(P acc, P part);
    }

    /** VaR's partials: scenario P&amp;L vectors of one width, added element by element in float64. */
    public static Combine<double[]> sum(int width) {
        return new Combine<>() {
            @Override
            public double[] zero() {
                return new double[width];
            }

            @Override
            public double[] add(double[] acc, double[] part) {
                if (part == null || part.length != width) {
                    throw new IllegalStateException("a partial sum of " + (part == null ? 0 : part.length) + " values; " + width + " were asked");
                }
                for (int j = 0; j < width; j++) {
                    acc[j] += part[j];
                }
                return acc;
            }
        };
    }

    private ShardGather() {}

    /**
     * Asks every shard of {@code shards} (ascending) once: those {@code here} accepts on this thread through {@code local}, the others side by
     * side through {@code remote} on {@code ask}; then combines the answers in shard order.
     *
     * @throws IllegalStateException when a shard gave no answer (a remote failure is rethrown as it was)
     */
    public static <P> P gather(int[] shards, IntPredicate here, IntFunction<P> local, IntFunction<P> remote, Combine<P> combine, Executor ask) {
        List<CompletableFuture<P>> asked = new ArrayList<>(shards.length);
        for (int s : shards) {
            asked.add(here.test(s) ? null : CompletableFuture.supplyAsync(() -> remote.apply(s), ask));
        }
        List<P> got = new ArrayList<>(shards.length);
        for (int i = 0; i < shards.length; i++) {
            got.add(here.test(shards[i]) ? local.apply(shards[i]) : null);
        }
        for (int i = 0; i < shards.length; i++) {
            if (asked.get(i) != null) {
                got.set(i, join(asked.get(i)));
            }
        }
        P acc = combine.zero();
        int answered = 0;
        for (int i = 0; i < shards.length; i++) {                  // a fixed order: shard 0, 1, 2 ...
            if (got.get(i) == null) {
                throw new IllegalStateException("shard " + shards[i] + " gave no answer");
            }
            acc = combine.add(acc, got.get(i));
            answered++;
        }
        if (answered != shards.length) {
            throw new IllegalStateException(answered + " answers for " + shards.length + " shards asked");
        }
        return acc;
    }

    private static <P> P join(CompletableFuture<P> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;                                           // the remote side's reason (no live owner, a stale version)
            }
            throw new IllegalStateException("a shard's answer failed: " + e.getCause(), e.getCause());
        }
    }
}
