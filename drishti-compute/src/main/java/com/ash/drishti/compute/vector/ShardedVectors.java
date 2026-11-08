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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The rows of a business date sharded across Drishti*net members (VAR_SHARDING.md): the date's ordinals cut into contiguous shards (legal
 * entity and desk ranges, a desk never split), each one's rows held here (mapped from its shard hot file) or on its owners. A set is summed
 * shard by shard: each shard's part given once to the rows held here or to one owner ({@link Remote}, the partial endpoint), the partial
 * float64 vectors added in shard order, so the same set gives the same bits whichever owners answered. Exactly one answer per shard asked:
 * a shard with no live owner fails the whole sum (the remote side says why), never a partial vector.
 *
 * <p>A single row or run (a drill below a position, a trade's vector) is read the same way: here when held, else from an owner. Many of
 * them asked together (a contribution table's positions or trades: {@link #batched}) go to each remote shard in one call of up to
 * {@link #BATCH} sets, the shards side by side; each answer is the one the single read would have had, so the parts are the same to the bit.
 */
public final class ShardedVectors implements Vectors, SetSums {

    /** The rows of the shards this server does not hold, summed by an owner. */
    @FunctionalInterface
    public interface Remote {
        /**
         * The sum of shard {@code shard}'s rows {@code rows} (in the shard's own ordinals, from 0), as one owner computed it. Throws when no
         * owner answers (the date's figures are then refused, never partial).
         *
         * @param context what {@link #context()} gave on the thread that asked (the caller, for the owner to apply their rights)
         */
        double[] sum(int shard, OrdinalSet rows, Object context);

        /** The asking thread's context, taken before the owners are asked on other threads (null: none). */
        default Object context() {
            return null;
        }

        /** Shard {@code shard}'s part of a set was summed here, from its rows held here (for the answer's provenance). */
        default void held(int shard, Object context) {
        }

        /**
         * The sums of many row sets of shard {@code shard}, in order, each as {@link #sum} gives it. An owner answers them in one call;
         * this default asks them one by one.
         */
        default List<double[]> sumAll(int shard, List<OrdinalSet> sets, Object context) {
            List<double[]> out = new ArrayList<>(sets.size());
            for (OrdinalSet r : sets) {
                out.add(sum(shard, r, context));
            }
            return out;
        }
    }

    /** The most row sets one call to an owner carries (at 509 scenarios about 4 MB of float64 back). */
    public static final int BATCH = 1024;

    /** One read of a remote shard: its rows {@code [from, to)} in the shard's ordinals, as a single row or as a run. */
    private record Read(int shard, int from, int to, boolean single) {
        OrdinalSet set() {
            return single ? OrdinalSet.of(from) : OrdinalSet.range(from, to);
        }
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
    /** The remote reads fetched ahead by {@link #batched}, for the thread running its work. */
    private final ThreadLocal<Map<Read, double[]>> fetched = new ThreadLocal<>();

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
        OrdinalSet[] slices = new OrdinalSet[n];
        List<Integer> touched = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Part p = parts.get(i);
            OrdinalSet slice = set.slice(p.from(), p.to());
            if (!slice.isEmpty()) {
                slices[i] = slice;
                touched.add(i);
            }
        }
        Object ctx = remote.context();
        int[] asked = touched.stream().mapToInt(Integer::intValue).toArray();
        return com.ash.drishti.compute.shard.ShardGather.gather(asked, i -> parts.get(i).held(), i -> {
            double[] v = sums.sum(parts.get(i).rows(), slices[i]);
            remote.held(parts.get(i).shard(), ctx);
            return v;
        }, i -> remote.sum(parts.get(i).shard(), slices[i], ctx), com.ash.drishti.compute.shard.ShardGather.sum(width), ASK);
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
        return single(p, row)[j];
    }

    @Override
    public void copy(int row, double[] out) {
        Part p = parts.get(partOf(row));
        if (p.held()) {
            p.rows().copy(row - p.from(), out);
            return;
        }
        System.arraycopy(single(p, row), 0, out, 0, width);
    }

    @Override
    public void addInto(double[] acc, int row) {
        Part p = parts.get(partOf(row));
        if (p.held()) {
            p.rows().addInto(acc, row - p.from());
            return;
        }
        add(acc, single(p, row));
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
                add(acc, read(new Read(p.shard(), a - p.from(), b - p.from(), false)));
            }
        }
    }

    private double[] single(Part p, int row) {
        return read(new Read(p.shard(), row - p.from(), row - p.from() + 1, true));
    }

    /** A remote read: fetched ahead by {@link #batched} on this thread, else asked of an owner now. */
    private double[] read(Read r) {
        Map<Read, double[]> ahead = fetched.get();
        double[] v = ahead == null ? null : ahead.get(r);
        return v != null ? v : remote.sum(r.shard(), r.set(), remote.context());
    }

    /**
     * Runs {@code work} with the remote reads it will make fetched ahead: the runs {@code ranges} ({@code [from, to)} of the date's
     * ordinals, read as {@link #addRangeInto} reads them) and the single rows {@code rows} (as {@link #copy} and {@link #addInto} read
     * them). The reads of each remote shard go to one owner in calls of up to {@link #BATCH} sets, every shard's calls side by side under
     * the one deadline the remote side keeps (its {@link Remote#context()}); then {@code work} runs on this thread and finds each answer
     * where it would have asked for it. The rows held here are read as before. A shard nobody answers fails the whole work.
     */
    public <T> T batched(List<int[]> ranges, int[] rows, Supplier<T> work) {
        Map<Integer, LinkedHashSet<Read>> byShard = new TreeMap<>();
        for (int[] r : ranges) {
            if (r[1] <= r[0]) {
                continue;
            }
            for (int i = partOf(r[0]); i < parts.size() && parts.get(i).from() < r[1]; i++) {
                Part p = parts.get(i);
                int a = Math.max(r[0], p.from());
                int b = Math.min(r[1], p.to());
                if (b > a && !p.held()) {
                    byShard.computeIfAbsent(p.shard(), k -> new LinkedHashSet<>()).add(new Read(p.shard(), a - p.from(), b - p.from(), false));
                }
            }
        }
        for (int row : rows) {
            Part p = parts.get(partOf(row));
            if (!p.held()) {
                byShard.computeIfAbsent(p.shard(), k -> new LinkedHashSet<>()).add(new Read(p.shard(), row - p.from(), row - p.from() + 1, true));
            }
        }
        Map<Read, double[]> before = fetched.get();
        if (byShard.isEmpty()) {
            return work.get();
        }
        Object ctx = remote.context();
        List<List<Read>> calls = new ArrayList<>();
        List<CompletableFuture<List<double[]>>> asked = new ArrayList<>();
        for (Map.Entry<Integer, LinkedHashSet<Read>> e : byShard.entrySet()) {
            List<Read> all = new ArrayList<>(e.getValue());
            for (int at = 0; at < all.size(); at += BATCH) {
                List<Read> call = all.subList(at, Math.min(all.size(), at + BATCH));
                List<OrdinalSet> sets = call.stream().map(Read::set).toList();
                int shard = e.getKey();
                calls.add(call);
                asked.add(CompletableFuture.supplyAsync(() -> remote.sumAll(shard, sets, ctx), ASK));
            }
        }
        Map<Read, double[]> got = before == null ? new HashMap<>() : new HashMap<>(before);
        RuntimeException failed = null;
        for (int i = 0; i < asked.size(); i++) {
            try {
                List<double[]> answers = asked.get(i).join();
                List<Read> call = calls.get(i);
                if (answers.size() != call.size()) {
                    throw new IllegalStateException(answers.size() + " sums for the " + call.size() + " row sets asked of shard " + call.get(0).shard());
                }
                for (int k = 0; k < call.size(); k++) {
                    got.put(call.get(k), answers.get(k));
                }
            } catch (CompletionException e) {
                failed = failed != null ? failed : e.getCause() instanceof RuntimeException re ? re
                        : new IllegalStateException("a shard's answer failed: " + e.getCause(), e.getCause());
            } catch (RuntimeException e) {
                failed = failed != null ? failed : e;
            }
        }
        if (failed != null) {
            throw failed;                                               // every call waited for: none left running for this request
        }
        fetched.set(got);
        try {
            return work.get();
        } finally {
            if (before == null) {
                fetched.remove();
            } else {
                fetched.set(before);
            }
        }
    }

    /**
     * The sums of many sets at once (a cube view's cells and totals, docs/lenses/README.md section 4.11): each set cut by shard as {@link #sumSet}
     * cuts it, every remote shard asked <b>once</b> for all the sets' parts it holds (in calls of up to {@link #BATCH} sets, the shards side by
     * side under one deadline), the held shards summed here, then each set's partials added in shard order from zero, exactly as
     * {@link #sumSet} adds them: each answer is the one {@link #sumSet} gives for that set, to the bit. A shard nobody answers fails them all.
     */
    public List<double[]> sumSets(List<OrdinalSet> sets, ParallelSums sums) {
        int n = parts.size();
        OrdinalSet[][] slices = new OrdinalSet[sets.size()][n];
        Map<Integer, List<int[]>> remoteAsks = new TreeMap<>();          // part index -> (set index) asked of its owner
        for (int s = 0; s < sets.size(); s++) {
            for (int i = 0; i < n; i++) {
                Part p = parts.get(i);
                OrdinalSet slice = sets.get(s).slice(p.from(), p.to());
                if (!slice.isEmpty()) {
                    slices[s][i] = slice;
                    if (!p.held()) {
                        remoteAsks.computeIfAbsent(i, k -> new ArrayList<>()).add(new int[] {s});
                    }
                }
            }
        }
        Object ctx = remote.context();
        Map<Long, double[]> got = new HashMap<>();
        List<CompletableFuture<List<double[]>>> asked = new ArrayList<>();
        List<List<int[]>> calls = new ArrayList<>();
        List<Integer> callPart = new ArrayList<>();
        for (Map.Entry<Integer, List<int[]>> e : remoteAsks.entrySet()) {
            int i = e.getKey();
            List<int[]> all = e.getValue();
            for (int at = 0; at < all.size(); at += BATCH) {
                List<int[]> call = all.subList(at, Math.min(all.size(), at + BATCH));
                List<OrdinalSet> rows = call.stream().map(x -> slices[x[0]][i]).toList();
                int shard = parts.get(i).shard();
                calls.add(call);
                callPart.add(i);
                asked.add(CompletableFuture.supplyAsync(() -> remote.sumAll(shard, rows, ctx), ASK));
            }
        }
        // the held shards while the owners answer
        for (int s = 0; s < sets.size(); s++) {
            for (int i = 0; i < n; i++) {
                if (slices[s][i] != null && parts.get(i).held()) {
                    got.put(((long) s << 20) | i, sums.sum(parts.get(i).rows(), slices[s][i]));
                    remote.held(parts.get(i).shard(), ctx);
                }
            }
        }
        RuntimeException failed = null;
        for (int c = 0; c < asked.size(); c++) {
            try {
                List<double[]> answers = asked.get(c).join();
                List<int[]> call = calls.get(c);
                if (answers.size() != call.size()) {
                    throw new IllegalStateException(answers.size() + " sums for the " + call.size() + " row sets asked of shard "
                            + parts.get(callPart.get(c)).shard());
                }
                for (int k = 0; k < call.size(); k++) {
                    got.put(((long) call.get(k)[0] << 20) | callPart.get(c), answers.get(k));
                }
            } catch (CompletionException e) {
                failed = failed != null ? failed : e.getCause() instanceof RuntimeException re ? re
                        : new IllegalStateException("a shard's answer failed: " + e.getCause(), e.getCause());
            } catch (RuntimeException e) {
                failed = failed != null ? failed : e;
            }
        }
        if (failed != null) {
            throw failed;
        }
        com.ash.drishti.compute.shard.ShardGather.Combine<double[]> combine = com.ash.drishti.compute.shard.ShardGather.sum(width);
        List<double[]> out = new ArrayList<>(sets.size());
        for (int s = 0; s < sets.size(); s++) {
            double[] acc = combine.zero();
            for (int i = 0; i < n; i++) {                                // a fixed order: shard 0, 1, 2 ... as sumSet
                if (slices[s][i] != null) {
                    double[] part = got.get(((long) s << 20) | i);
                    if (part == null) {
                        throw new IllegalStateException("shard " + parts.get(i).shard() + " gave no answer");
                    }
                    acc = combine.add(acc, part);
                }
            }
            out.add(acc);
        }
        return out;
    }

    private void add(double[] acc, double[] v) {
        for (int j = 0; j < width; j++) {
            acc[j] += v[j];
        }
    }
}
