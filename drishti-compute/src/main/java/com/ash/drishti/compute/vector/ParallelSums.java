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
 * Sums of many vectors on every core, with a bounded pool (VAR_DATA_MODEL.md section 12.3). A set of ordinals is split by chunk of the
 * store; each chunk's rows are summed into a private accumulator on a pool thread, and the accumulators are added in chunk order, so the
 * same set gives the same bits every time whatever the threads did. Memory bandwidth, not arithmetic, is the limit: one sequential
 * pass over the rows of the set.
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

    /** The sum of the vectors of {@code set}: {@code width} doubles. */
    public double[] sum(Vectors store, OrdinalSet set) {
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
        if (parts.size() <= 1) {
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
