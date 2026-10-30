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
package com.ash.drishti.compute.vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sums of many vectors on every core, with a bounded pool (VAR_GUIDE.md section 25). Since 2.9.7 in a fixed reduction order ({@link #sum}):
 * blocks aligned to the absolute ordinal, sized from the set alone, each summed from zero, added in block order, so the same set gives the
 * same bits whatever the threads or the core count. Memory bandwidth, not arithmetic, is the limit: one sequential pass over the rows of
 * the set.
 *
 * <p>Small sums (below {@code parallelRows} rows) run on the caller's thread: a desk of a few thousand rows is faster summed in place
 * than handed to a pool.
 */
public final class ParallelSums implements AutoCloseable {

    private final ExecutorService pool;
    private final int threads;
    private final int parallelRows;

    /**
     * @param threads the pool's threads (the cores to use; at least 1)
     * @param parallelRows the fewest rows worth splitting across threads
     */
    public ParallelSums(int threads, int parallelRows) {
        this.threads = Math.max(1, threads);
        this.parallelRows = Math.max(1, parallelRows);
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "drishti-vector-sum-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = Executors.newFixedThreadPool(this.threads, tf);
    }

    public int threads() {
        return threads;
    }

    /**
     * The sum of the vectors of {@code set}: {@code width} doubles, in a <b>fixed reduction order</b> (2.9.7, RESOURCES_AND_SCALE.md section
     * 6.7, decision PO-7): the ordinals are cut into blocks aligned to the absolute ordinal (block k holds {@code [k * B, (k + 1) * B)}),
     * B chosen from the set's size alone ({@link #blockRows}: about 64 blocks, a power of two, at least 1,024 rows; a set under 1,024 rows
     * is summed in plain row order); each block's rows are
     * added in row order from zero, and the blocks' partials are added in block order. The bits so depend only on the set, never on the
     * threads or the core count: two servers with different cores give the same bits for any scope. The threads only decide which blocks
     * are summed at once.
     */
    public double[] sum(Vectors store, OrdinalSet set) {
        if (store instanceof SetSums s && s.sumsSets() && !set.isEmpty()) {
            return s.sumSet(set, this);                                // a sharded date: each shard's part summed by an owner
        }
        return FIXED_ORDER ? sumFixed(store, set) : sumSliced(store, set);
    }

    /** True: {@link #sum} in the fixed order (2.9.7); false only to measure the earlier order ({@code -Ddrishti.var.fixed-order=false}). */
    static final boolean FIXED_ORDER = !"false".equals(System.getProperty("drishti.var.fixed-order"));

    /** The fixed-order sum (see {@link #sum}). */
    double[] sumFixed(Vectors store, OrdinalSet set) {
        int w = store.width();
        double[] acc = new double[w];
        if (set.isEmpty()) {
            return acc;
        }
        if (set.size() < SMALL) {                                      // a small set: its rows in row order, one accumulator (any thread)
            set.forEachRun((f, t) -> store.addRangeInto(acc, f, t));
            return acc;
        }
        int b = blockRows(set.size());
        // the blocks the set touches, in order (from its runs: a small set costs its runs, not the store's size)
        int[][] found = {new int[16]};
        int[] count = {0};
        set.forEachRun((f, t) -> {
            for (int k = f / b; k <= (t - 1) / b; k++) {
                if (count[0] > 0 && found[0][count[0] - 1] == k) {
                    continue;
                }
                if (count[0] == found[0].length) {
                    found[0] = java.util.Arrays.copyOf(found[0], count[0] * 2);
                }
                found[0][count[0]++] = k;
            }
        });
        int[] blocks = found[0];
        int n = count[0];
        if (n == 1 || threads == 1 || set.size() < parallelRows || store.chunkCount() == 1 && set.size() < 4 * parallelRows) {
            double[] part = n > 1 ? new double[w] : null;
            for (int i = 0; i < n; i++) {
                int lo = blocks[i] * b;
                int hi = Math.min(store.rows(), lo + b);
                if (i == 0) {
                    set.forEachRunIn(lo, hi, (f, t) -> store.addRangeInto(acc, f, t));   // 0 + the first block's partial: the partial itself
                    continue;
                }
                java.util.Arrays.fill(part, 0);
                set.forEachRunIn(lo, hi, (f, t) -> store.addRangeInto(part, f, t));
                for (int j = 0; j < w; j++) {
                    acc[j] += part[j];
                }
            }
            return acc;
        }
        // the blocks summed on the pool, in contiguous groups (about 2 a thread); every block's partial kept, then added in block order
        double[][] partials = new double[n][];
        int groups = Math.min(n, threads);                              // one round: every thread a contiguous run of blocks
        int per = (n + groups - 1) / groups;
        List<Future<?>> fs = new ArrayList<>();
        int[] bl = java.util.Arrays.copyOf(blocks, n);
        for (int g = 0; g < n; g += per) {
            int from = g;
            int to = Math.min(n, g + per);
            fs.add(pool.submit(() -> {
                for (int i = from; i < to; i++) {
                    int lo = bl[i] * b;
                    double[] p = new double[w];
                    set.forEachRunIn(lo, Math.min(store.rows(), lo + b), (f, t) -> store.addRangeInto(p, f, t));
                    partials[i] = p;
                }
            }));
        }
        for (Future<?> f : fs) {
            join(f);
        }
        System.arraycopy(partials[0], 0, acc, 0, w);
        for (int i = 1; i < n; i++) {
            double[] p = partials[i];
            for (int j = 0; j < w; j++) {
                acc[j] += p[j];
            }
        }
        return acc;
    }

    /** Below this many rows a set is summed in plain row order (the order depends on the set's size alone, never the threads). */
    static final int SMALL = 1_024;

    /** A sum's block: about 64 blocks of the set's size, a power of two of at least 1,024 rows (the set alone decides it). */
    static int blockRows(int setSize) {
        return Math.max(1_024, Integer.highestOneBit(Math.max(1, setSize / 64)));
    }

    /** The order before 2.9.7: slices of the range by thread count (the bits depended on {@code threads}); kept to measure against. */
    double[] sumSliced(Vectors store, OrdinalSet set) {
        double[] acc = new double[store.width()];
        if (set.isEmpty()) {
            return acc;
        }
        if (set.size() < parallelRows || threads == 1 || store.chunkCount() == 1 && set.size() < 4 * parallelRows) {
            set.forEachRun((from, to) -> store.addRangeInto(acc, from, to));
            return acc;
        }
        // split the set into about threads * 2 slices of the ordinal range, aligned to nothing: each slice sums its runs
        int first = set.first();
        int last = store.rows();
        int slices = Math.min(threads * 2, Math.max(1, set.size() / parallelRows));
        int step = Math.max(1, (last - first + slices - 1) / slices);
        List<Future<double[]>> parts = new ArrayList<>();
        for (int s = first; s < last; s += step) {
            int from = s;
            int to = Math.min(last, s + step);
            parts.add(pool.submit(() -> {
                double[] a = new double[store.width()];
                set.forEachRunIn(from, to, (f, t) -> store.addRangeInto(a, f, t));
                return a;
            }));
        }
        for (Future<double[]> f : parts) {
            double[] a = join(f);
            for (int j = 0; j < acc.length; j++) {
                acc[j] += a[j];
            }
        }
        return acc;
    }

    /** The sum of the rows {@code from..to} (a node of the primary hierarchy). */
    public double[] sumRange(Vectors store, int from, int to) {
        return sum(store, OrdinalSet.range(from, to));
    }

    /** Each part's sum (the children of a scope), the parts summed on the pool at once; part {@code i}'s sum at index {@code i}. */
    public double[][] sums(Vectors store, List<OrdinalSet> parts) {
        double[][] out = new double[parts.size()][];
        if (parts.size() <= 1 || store instanceof SetSums s && s.sumsSets()) {
            for (int i = 0; i < parts.size(); i++) {
                out[i] = sum(store, parts.get(i));
            }
            return out;
        }
        List<Future<double[]>> fs = new ArrayList<>();
        for (OrdinalSet p : parts) {
            fs.add(pool.submit(() -> {
                double[] a = new double[store.width()];
                p.forEachRun((f, t) -> store.addRangeInto(a, f, t));
                return a;
            }));
        }
        for (int i = 0; i < fs.size(); i++) {
            out[i] = join(fs.get(i));
        }
        return out;
    }

    /**
     * Runs tasks on the pool and returns their results in task order (the load's parallel steps: a hot file's rows written a batch at a
     * time). A task that fails fails the whole call with its cause.
     */
    public <T> List<T> all(List<? extends java.util.concurrent.Callable<T>> tasks) {
        List<Future<T>> fs = new ArrayList<>();
        for (java.util.concurrent.Callable<T> t : tasks) {
            fs.add(pool.submit(t));
        }
        List<T> out = new ArrayList<>(fs.size());
        for (Future<T> f : fs) {
            out.add(join(f));
        }
        return out;
    }

    private static <T> T join(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while summing vectors", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;                                              // a closed matrix, a full disk: said as it is
            }
            throw new IllegalStateException("a vector sum failed: " + e.getCause(), e.getCause());
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
