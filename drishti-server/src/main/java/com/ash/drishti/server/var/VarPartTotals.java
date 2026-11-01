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
package com.ash.drishti.server.var;

import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.var.VarCalc;
import com.ash.drishti.compute.var.VarLens;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The totals over every position or trade of a scope (VAR_GUIDE.md section 27a): the count of the parts and Σ standalone VaR, over all of
 * them ({@code max-parts} limits only the rows an answer lists). Σ component VaR needs no pass: the parts partition the scope, so it is the
 * scope's own component ({@code VarMath.component} of the scope's vector at its tail scenarios).
 *
 * <p><b>The cost model.</b> A whole position's standalone VaR does not depend on the scope. It is computed the first time a scope holds
 * the position, once per book (a date, a currency, an intraday snapshot) and lens (horizon, method, confidence, rank rule), and kept as one
 * value a position (8 bytes: 1.6 MB at 200,000 positions, 18 MB at 2.3M) in a cache weighed in bytes that joins the one cache budget (the
 * {@code small} pool, {@link #OWN_LIMIT} at most) and holds its books weakly (a retired book's values go with it). Many positions missing
 * at once are computed in parallel on the engine's sum threads. A scope then costs one pass over its positions' runs: a cached value for each
 * position it holds whole, and the position's vector and VaR on the fly only for a position it holds in part. Trades (a position's parts,
 * or {@code by=trade}) are measured on the fly.
 *
 * <p>Each part's vector is made exactly as the listed part's is ({@link VarViews}: a whole position's own vector, else the sum of its trades
 * the scope holds; a trade's vector), through the answer's lens, measured by {@code VarCalc.var} (the full measures' VaR to the bit), and
 * summed in the parts' order: for a scope within {@code max-parts} the totals are the listed parts' sums to the bit.
 */
final class VarPartTotals {

    /** The most bytes the cache of whole positions' standalone VaRs takes before the cache budget sets its share. */
    static final long OWN_LIMIT = 64L << 20;
    /** Missing positions computed on one thread up to this many; more are split across the sum threads. */
    private static final int PARALLEL_FROM = 256;
    private static final long NONE = Double.doubleToRawLongBits(Double.NaN);

    /** The parts counted and Σ standalone VaR. */
    record Totals(int count, double standalone) {}

    /** Per book (held weakly: by identity), per lens key, each position's standalone VaR when whole (raw bits; NaN: not yet). */
    private final com.github.benmanes.caffeine.cache.Cache<VarBook, Map<String, AtomicLongArray>> wholes =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().weakKeys().maximumWeight(OWN_LIMIT)
                    .weigher((VarBook b, Map<String, AtomicLongArray> m) -> weight(m))
                    .expireAfterAccess(java.time.Duration.ofHours(1)).recordStats().build();
    private final java.util.concurrent.atomic.AtomicLong computed = new java.util.concurrent.atomic.AtomicLong();

    private static int weight(Map<String, AtomicLongArray> m) {
        long b = 64;
        for (AtomicLongArray v : m.values()) {
            b += 96L + 8L * v.length();
        }
        return (int) Math.min(Integer.MAX_VALUE, b);
    }

    /** The cache, for Admin, Caches and the cache budget. */
    com.github.benmanes.caffeine.cache.Cache<VarBook, Map<String, AtomicLongArray>> cache() {
        return wholes;
    }

    /** How many whole positions' standalone VaRs were computed (the cache's misses, a test's counter). */
    long computed() {
        return computed.get();
    }

    /**
     * The positions of a scope as runs {@code [position, from, to)} in ordinal order, each position once (its first run): a position's
     * trades added during the day come after the morning's ordinals, and the position is one part all the same.
     */
    static List<int[]> positionRuns(VarBook b, OrdinalSet set) {
        int[] last = {-1};
        List<int[]> runs = new ArrayList<>();
        BitSet seen = new BitSet();
        // run by run, a position's block at a time: the ordinals of one run of the set that fall in one position's morning run are one
        // step (an intraday-added ordinal, outside its position's morning run, is one step of its own), never ordinal by ordinal
        set.forEachRun((from, to) -> {
            int o = from;
            while (o < to) {
                int pi = b.positionOf(o);
                int end = o >= b.positionFrom(pi) && o < b.positionTo(pi) ? Math.min(to, b.positionTo(pi)) : o + 1;
                if (pi == last[0]) {
                    runs.get(runs.size() - 1)[2] = end;
                } else if (!seen.get(pi)) {
                    seen.set(pi);
                    runs.add(new int[] {pi, o, end});
                    last[0] = pi;
                } else {
                    last[0] = -1;                                     // a later run of a position already listed
                }
                o = end;
            }
        });
        return runs;
    }

    /** A position's vector within the scope: its own when the scope holds all of it, else the sum of its trades the scope holds. */
    static double[] positionVector(VarBook b, OrdinalSet set, int pi) {
        if (b.positionWithin(pi, set)) {
            return b.positionVector(pi);
        }
        double[] acc = new double[b.width()];
        b.forEachPositionMemberIn(pi, set, o -> b.store().addInto(acc, o));
        return acc;
    }

    /** What a whole position's standalone VaR depends on besides the book: the currency, the lens and the calculator. */
    static String lensKey(VarService.Scoped s, VarCalc calc, VarLens lens) {
        return s.date().currency() + "|" + lens.days() + "|" + lens.method() + "|" + calc.confidence() + "|" + calc.rule();
    }

    /** A whole position's standalone VaR, through the lens. */
    private static double alone(VarBook b, int pi, VarCalc calc, VarLens lens) {
        double[] v = b.positionVector(pi);
        return calc.var(lens.method() != VarLens.Method.NONE ? lens.apply(v) : v);
    }

    /**
     * The standalone VaRs of the whole positions {@code positions} of the scope's book, through the lens: from the cache, the missing ones
     * computed now (in parallel when many) and kept. Two requests may compute the same position at once; they write the same value.
     */
    AtomicLongArray wholes(VarService.Scoped s, VarCalc calc, VarLens lens, ParallelSums sums, int[] positions) {
        VarBook b = s.date().book();
        String key = lensKey(s, calc, lens);
        Map<String, AtomicLongArray> m = wholes.get(b, k -> new ConcurrentHashMap<>());
        AtomicLongArray have = m.get(key);
        if (have == null) {
            AtomicLongArray fresh = new AtomicLongArray(b.positionCount());
            for (int i = 0; i < fresh.length(); i++) {
                fresh.set(i, NONE);
            }
            have = m.putIfAbsent(key, fresh);
            if (have == null) {
                have = fresh;
                wholes.put(b, m);                                       // put again: weighed again with the new array
            }
        }
        AtomicLongArray values = have;
        int[] missing = java.util.Arrays.stream(positions).filter(pi -> values.get(pi) == NONE).toArray();
        if (missing.length > 0) {
            int tasks = missing.length < PARALLEL_FROM ? 1 : Math.min(missing.length / PARALLEL_FROM + 1, sums.threads() * 4);
            List<java.util.concurrent.Callable<Void>> work = new ArrayList<>();
            for (int t = 0; t < tasks; t++) {
                int from = (int) ((long) missing.length * t / tasks);
                int to = (int) ((long) missing.length * (t + 1) / tasks);
                work.add(() -> {
                    for (int i = from; i < to; i++) {
                        values.set(missing[i], Double.doubleToRawLongBits(alone(b, missing[i], calc, lens)));
                    }
                    return null;
                });
            }
            if (tasks == 1) {
                try {
                    work.get(0).call();
                } catch (Exception e) {
                    throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
                }
            } else {
                sums.all(work);
            }
            computed.addAndGet(missing.length);
        }
        return values;
    }

    /**
     * The count and Σ standalone VaR of the scope's parts of {@code mode} ({@code position} or {@code trade}), in the parts' order.
     *
     * @param calc the answer's calculator (its confidence and rank rule)
     * @param lens the answer's lens (each part's vector goes through it, as the listed parts' do)
     */
    Totals of(VarService.Scoped s, String mode, VarCalc calc, VarLens lens, ParallelSums sums) {
        VarBook b = s.date().book();
        boolean apply = lens.method() != VarLens.Method.NONE;
        if (mode.equals("trade")) {
            double[] acc = {0};
            int[] k = {0};
            s.set().forEach(o -> {
                double[] v = b.tradeVector(o);
                acc[0] += calc.var(apply ? lens.apply(v) : v);
                k[0]++;
            });
            return new Totals(k[0], acc[0]);
        }
        List<int[]> runs = positionRuns(b, s.set());
        boolean[] whole = new boolean[runs.size()];
        int[] wholeIds = new int[runs.size()];
        int w = 0;
        for (int i = 0; i < runs.size(); i++) {
            whole[i] = b.positionWithin(runs.get(i)[0], s.set());     // its own run only: a binary search over runs, a short scan of bits
            if (whole[i]) {
                wholeIds[w++] = runs.get(i)[0];
            }
        }
        AtomicLongArray cached = w == 0 ? null : wholes(s, calc, lens, sums, java.util.Arrays.copyOf(wholeIds, w));
        double sum = 0;
        for (int i = 0; i < runs.size(); i++) {
            int pi = runs.get(i)[0];
            if (whole[i]) {
                sum += Double.longBitsToDouble(cached.get(pi));
            } else {                                                  // held in part: its trades in the scope, measured now
                double[] v = positionVector(b, s.set(), pi);
                sum += calc.var(apply ? lens.apply(v) : v);
            }
        }
        return new Totals(runs.size(), sum);
    }
}
