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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The rows of a business date sharded across Drishti*net members (VAR_SHARDING.md): the date's ordinals cut into contiguous shards (legal
 * entity and desk ranges, a desk never split), each one's rows held here (mapped from its shard hot file) or on its owners. A set is summed
 * shard by shard: each shard's part given once to the rows held here or to one owner ({@link Remote}, the partial endpoint), the partial
 * float64 vectors added in shard order, so the same set gives the same bits whichever owners answered. Exactly one answer per shard asked:
 * a shard with no live owner fails the whole sum (the remote side says why), never a partial vector.
 *
 * <p>A single row or run (a drill below a position, a trade's vector) is read the same way: here when held, else from an owner.
 */
public final class ShardedVectors implements Vectors, SetSums {

    /** The rows of the shards this server does not hold, summed by an owner. */
    @FunctionalInterface
    public interface Remote {
        /**
         * The sum of shard {@code shard}'s rows {@code rows} (in the shard's own ordinals, from 0), as one owner computed it. Throws when no
         * owner answers (the date's figures are then refused, never partial).
         */
        double[] sum(int shard, OrdinalSet rows);
    }

    /**
     * One shard: its run {@code [from, to)} of the date's ordinals and its rows when this server holds them (null: on other members).
     *
     * @param shard the shard's index in the date's shard map
     */
    public record Part(int shard, int from, int to, Vectors rows) {
        public boolean held() {
            return rows != null;
        }
    }

    private static final ExecutorService ASK = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("drishti-var-shard-ask-", 0).factory());

    private final int width;
    private final List<Part> parts;
    private final int[] starts;
    private final Remote remote;

    /**
     * @param width the vector width (the date's scenarios)
     * @param parts the shards in order, contiguous from ordinal 0
     * @param remote how a shard held elsewhere is summed
     */
    public ShardedVectors(int width, List<Part> parts, Remote remote) {
        this.width = width;
        this.parts = List.copyOf(parts);
        this.remote = remote;
        this.starts = new int[this.parts.size()];
        int at = 0;
        for (int i = 0; i < this.parts.size(); i++) {
            Part p = this.parts.get(i);
            if (p.from() != at || p.to() < p.from()) {
                throw new IllegalArgumentException("the shards' runs are not contiguous at " + at + ": " + p);
            }
            if (p.rows() != null && p.rows().rows() != p.to() - p.from()) {
                throw new IllegalArgumentException("shard " + p.shard() + " holds " + p.rows().rows() + " rows for a run of " + (p.to() - p.from()));
            }
            starts[i] = p.from();
            at = p.to();
        }
    }

    public List<Part> parts() {
        return parts;
    }

    @Override
    public boolean sumsSets() {
        return true;
    }

    @Override
    public double[] sumSet(OrdinalSet set, ParallelSums sums) {
        int n = parts.size();
        double[][] got = new double[n][];
        List<CompletableFuture<double[]>> asked = new ArrayList<>(n);
        OrdinalSet[] slices = new OrdinalSet[n];
        int wanted = 0;
        for (int i = 0; i < n; i++) {
            Part p = parts.get(i);
            OrdinalSet slice = set.slice(p.from(), p.to());
            asked.add(null);
            if (slice.isEmpty()) {
                continue;
            }
            wanted++;
            slices[i] = slice;
            if (!p.held()) {
                asked.set(i, CompletableFuture.supplyAsync(() -> remote.sum(p.shard(), slice), ASK));     // the owners asked side by side
            }
        }
        for (int i = 0; i < n; i++) {
            if (slices[i] != null && parts.get(i).held()) {
                got[i] = sums.sum(parts.get(i).rows(), slices[i]);
            }
        }
        for (int i = 0; i < n; i++) {
            if (asked.get(i) != null) {
                got[i] = join(asked.get(i));
            }
        }
        double[] acc = new double[width];
        int answered = 0;
        for (int i = 0; i < n; i++) {                                       // a fixed order: shard 0, 1, 2 ...
            if (slices[i] == null) {
                continue;
            }
            double[] v = got[i];
            if (v == null || v.length != width) {
                throw new IllegalStateException("shard " + parts.get(i).shard() + " gave no partial sum of " + width + " values");
            }
            answered++;
            for (int j = 0; j < width; j++) {
                acc[j] += v[j];
            }
        }
        if (answered != wanted) {
            throw new IllegalStateException(answered + " partial sums for " + wanted + " shards asked");
        }
        return acc;
    }

    private static double[] join(CompletableFuture<double[]> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;                                                   // the remote side's reason (no live owner, a stale version)
            }
            throw new IllegalStateException("a shard's partial sum failed: " + e.getCause(), e.getCause());
        }
    }

    /** The index in {@link #parts} of the shard holding {@code row}. */
    private int partOf(int row) {
        int i = java.util.Arrays.binarySearch(starts, row);
        if (i < 0) {
            i = -i - 2;
        }
        while (i > 0 && parts.get(i).to() == parts.get(i).from()) {
            i--;                                                            // an empty shard shares its start with the next
        }
        while (i < parts.size() - 1 && row >= parts.get(i).to()) {
            i++;
        }
        if (i < 0 || row < parts.get(i).from() || row >= parts.get(i).to()) {
            throw new IndexOutOfBoundsException("row " + row + " of " + rows());
        }
        return i;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int rows() {
        return parts.isEmpty() ? 0 : parts.get(parts.size() - 1).to();
    }

    @Override
    public int chunkCount() {
        return Math.max(1, parts.size());
    }

    /** The bytes of the rows held here (the other shards' rows are on their owners). */
    @Override
    public long bytes() {
        long b = 0;
        for (Part p : parts) {
            b += p.held() ? p.rows().bytes() : 0;
        }
        return b;
    }

    @Override
    public double value(int row, int j) {
        Part p = parts.get(partOf(row));
        if (p.held()) {
            return p.rows().value(row - p.from(), j);
        }
        return remote.sum(p.shard(), OrdinalSet.of(row - p.from()))[j];
    }

    @Override
    public void copy(int row, double[] out) {
        Part p = parts.get(partOf(row));
        if (p.held()) {
            p.rows().copy(row - p.from(), out);
            return;
        }
        System.arraycopy(remote.sum(p.shard(), OrdinalSet.of(row - p.from())), 0, out, 0, width);
    }

    @Override
    public void addInto(double[] acc, int row) {
        Part p = parts.get(partOf(row));
        if (p.held()) {
            p.rows().addInto(acc, row - p.from());
            return;
        }
        add(acc, remote.sum(p.shard(), OrdinalSet.of(row - p.from())));
    }

    @Override
    public void addRangeInto(double[] acc, int from, int to) {
        if (to <= from) {
            return;
        }
        for (int i = partOf(from); i < parts.size() && parts.get(i).from() < to; i++) {
            Part p = parts.get(i);
            int a = Math.max(from, p.from());
            int b = Math.min(to, p.to());
            if (b <= a) {
                continue;
            }
            if (p.held()) {
                p.rows().addRangeInto(acc, a - p.from(), b - p.from());
            } else {
                add(acc, remote.sum(p.shard(), OrdinalSet.range(a - p.from(), b - p.from())));
            }
        }
    }

    private void add(double[] acc, double[] v) {
        for (int j = 0; j < width; j++) {
            acc[j] += v[j];
        }
    }
}
