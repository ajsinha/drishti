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

import com.ash.drishti.compute.linalg.Blas;
import com.ash.drishti.compute.linalg.LinearAlgebra;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Many sets summed in one pass over the rows (HIGH_PERFORMANCE_NATIVE.md section 6.1, "many VaR scopes at once"): a cube's cells, a
 * report's scopes, a batch of ad-hoc trade sets. Each set's vector is the one {@link ParallelSums#sum} gives.
 *
 * <p><b>Java (the default, and whenever OpenBLAS is not bound)</b>: the rows are walked once in cache-sized tiles and each tile is added
 * into every set it holds while it is in the processor's cache, so memory is read once for all the sets instead of once per set. The order
 * of the additions is exactly {@link ParallelSums#sum}'s (blocks aligned to the absolute ordinal, sized per set; each block summed from
 * zero in row order; the blocks added in block order), so the vectors are <b>equal to the bit</b> to summing each set alone: the work is
 * cut into chunks aligned to the largest block, every chunk's block partials computed on the pool, and folded in block order.
 *
 * <p><b>OpenBLAS bound</b>: a tile of 1,024 rows where the sets are dense enough (memberships over sets times rows at least
 * {@code minDensity}) is one {@code dgemm}: rows (scenarios x rows) times the sets' 0/1 indicator (rows x sets). Sparse tiles keep the Java
 * additions. Each vector is then within a relative 1e-12 of the Java answer (the parity rule, section 6.2), the tiles still folded in a
 * fixed order (the same bits whatever the threads).
 *
 * <p>Stores whose rows can be cut anywhere ({@link VectorStore}, {@link MappedMatrix}, a {@link PermutedVectors} over one) and
 * {@link GroupedVectors} (cut at group boundaries) take this path; any other store (a sharded date, an intraday overlay) is summed set by
 * set as before.
 */
public final class MultiSums {

    /** Rows of a {@code dgemm} tile: 1,024 divides every block (blocks are powers of two of at least 1,024 rows). */
    static final int TILE = 1_024;
    /** Rows of a Java tile: about half a megabyte of 509 float32 scenarios, so it stays in the core's cache across the sets. */
    static final int SUB = 256;
    /** The default density from which a tile is a {@code dgemm} on OpenBLAS (measured: BENCHMARK.md, the many-scope rows). */
    public static final double DEFAULT_MIN_DENSITY = 0.25;
    /**
     * The fewest sets a tile must feed for a {@code dgemm} to pay for turning its rows into float64 (measured: below about 32 the Java
     * additions win, BENCHMARK.md).
     */
    static final int MIN_GEMM_SETS = 32;

    private MultiSums() {}

    private static volatile double minDensity = DEFAULT_MIN_DENSITY;
    private static volatile int minSets = MIN_GEMM_SETS;

    /**
     * The {@code dgemm} thresholds ({@code drishti.native.blas.gemm-min-sets}, {@code drishti.native.blas.gemm-min-density}): tuned on one
     * machine (BENCHMARK.md section 17), so settings; out-of-range values keep the defaults.
     */
    public static void configure(int sets, double density) {
        minSets = sets >= 2 ? sets : MIN_GEMM_SETS;
        minDensity = density > 0 && density <= 1 ? density : DEFAULT_MIN_DENSITY;
    }

    /** Each pool thread's tile of rows for {@code dgemm} (4 MB at 509 scenarios), kept between calls. */
    private static final ThreadLocal<double[]> ROWS = new ThreadLocal<>();

    static double[] buffer(int n) {
        double[] b = ROWS.get();
        if (b == null || b.length < n) {
            b = new double[n];
            ROWS.set(b);
        }
        return b;
    }

    /**
     * {@link #sums(Vectors, List, ParallelSums, LinearAlgebra, double)} with the binding the cluster agreed on ({@link Blas#forClusterSums})
     * and the configured thresholds ({@link #configure}).
     */
    public static List<double[]> sums(Vectors store, List<OrdinalSet> sets, ParallelSums pool) {
        return sums(store, sets, pool, Blas.forClusterSums(), minDensity);
    }

    /**
     * Each set's sum, in the order of {@code sets}.
     *
     * @param la the linear algebra ({@link Blas#get()}); Java gives {@link ParallelSums#sum}'s bits exactly
     * @param minDensity the least share of a tile's (set, row) pairs that are members for the tile to be a {@code dgemm}
     */
    public static List<double[]> sums(Vectors store, List<OrdinalSet> sets, ParallelSums pool, LinearAlgebra la, double minDensity) {
        int k = sets.size();
        double[][] out = new double[k][];
        int kind = kind(store);
        List<Integer> large = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            OrdinalSet s = sets.get(i);
            if (kind == 0 || s.size() < ParallelSums.SMALL) {
                out[i] = pool.sum(store, s);                             // small sets: plain row order, as alone; other stores: as before
            } else {
                large.add(i);
            }
        }
        if (large.size() == 1 && !la.isNative()) {
            out[large.get(0)] = pool.sum(store, sets.get(large.get(0)));
            large.clear();
        }
        if (!large.isEmpty()) {
            new Pass(store, kind == 2 ? (GroupedVectors) store : null, sets, large, pool, la, minDensity).run(out);
        }
        return Arrays.asList(out);
    }

    /** 0: summed set by set; 1: rows that may be cut anywhere; 2: grouped rows, cut at group boundaries. */
    static int kind(Vectors store) {
        if (store instanceof SetSums ss && ss.sumsSets()) {
            return 0;
        }
        if (store instanceof VectorStore || store instanceof MappedMatrix) {
            return 1;
        }
        if (store instanceof PermutedVectors p) {
            return kind(p.source()) == 1 ? 1 : 0;
        }
        if (store instanceof GroupedVectors g) {
            return kind(g.groups()) == 1 && (g.members() == null || kind(g.members()) == 1) ? 2 : 0;
        }
        return 0;
    }

    /** One pass of the large sets over their chunks. */
    private static final class Pass {
        final Vectors store;
        final GroupedVectors grouped;
        final OrdinalSet[] sets;
        final int[] block;
        final int bmax;
        final int width;
        final int[] index;
        final ParallelSums pool;
        final LinearAlgebra la;
        final double minDensity;
        final int minGemmSets;

        Pass(Vectors store, GroupedVectors grouped, List<OrdinalSet> all, List<Integer> large, ParallelSums pool, LinearAlgebra la, double minDensity) {
            this.store = store;
            this.grouped = grouped;
            this.pool = pool;
            this.la = la;
            this.minDensity = minDensity;
            this.minGemmSets = minDensity <= 0 ? 2 : MultiSums.minSets;  // a density of 0 asks for dgemm wherever two sets share a tile (tests)
            this.width = store.width();
            int n = large.size();
            this.sets = new OrdinalSet[n];
            this.block = new int[n];
            this.index = new int[n];
            int bm = TILE;
            for (int i = 0; i < n; i++) {
                index[i] = large.get(i);
                sets[i] = all.get(index[i]);
                block[i] = ParallelSums.blockRows(sets[i].size());
                bm = Math.max(bm, block[i]);
            }
            // the chunk: a power of two, a multiple of every set's block, about four a thread, at most 65,536 rows
            this.bmax = Math.max(bm, Math.min(1 << 16, Integer.highestOneBit(Math.max(1, store.rows() / (Math.max(1, pool.threads()) * 4)))));
        }

        void run(double[][] out) {
            int rows = store.rows();
            int chunks = (rows + bmax - 1) / bmax;
            boolean[] touched = new boolean[chunks];
            for (OrdinalSet s : sets) {
                s.forEachRun((f, t) -> {
                    for (int c = f / bmax; c <= (t - 1) / bmax; c++) {
                        touched[c] = true;
                    }
                });
            }
            List<Integer> todo = new ArrayList<>();
            for (int c = 0; c < chunks; c++) {
                if (touched[c]) {
                    todo.add(c);
                }
            }
            double[][] acc = new double[sets.length][];
            int wave = Math.max(1, pool.threads() * 2);
            for (int w = 0; w < todo.size(); w += wave) {
                List<Integer> part = todo.subList(w, Math.min(todo.size(), w + wave));
                List<double[][][]> got;
                if (part.size() == 1 || pool.threads() == 1) {
                    got = new ArrayList<>();
                    for (int c : part) {
                        got.add(chunk(c));
                    }
                } else {
                    List<Callable<double[][][]>> tasks = new ArrayList<>();
                    for (int c : part) {
                        tasks.add(() -> chunk(c));
                    }
                    got = pool.all(tasks);
                }
                for (double[][][] r : got) {                              // chunk order, then block order: ParallelSums' fold
                    for (int s = 0; s < sets.length; s++) {
                        if (r[s] == null) {
                            continue;
                        }
                        for (double[] p : r[s]) {
                            if (p == null) {
                                continue;
                            }
                            if (acc[s] == null) {
                                acc[s] = p;
                            } else {
                                double[] a = acc[s];
                                for (int j = 0; j < width; j++) {
                                    a[j] += p[j];
                                }
                            }
                        }
                    }
                }
            }
            for (int s = 0; s < sets.length; s++) {
                out[index[s]] = acc[s] == null ? new double[width] : acc[s];
            }
        }

        /** The block partials of chunk {@code c}: [set][block within the chunk] (null where the set has no row). */
        double[][][] chunk(int c) {
            int lo = c * bmax;
            int hi = Math.min(store.rows(), lo + bmax);
            double[][][] parts = new double[sets.length][][];
            if (!la.isNative()) {
                javaRange(lo, hi, lo, hi, parts);
                return parts;
            }
            Gemm g = new Gemm();
            for (int t0 = lo; t0 < hi; t0 += TILE) {
                int t1 = Math.min(hi, t0 + TILE);
                if (!g.tile(t0, t1, lo, parts)) {
                    javaRange(t0, t1, lo, hi, parts);
                }
            }
            return parts;
        }

        /** Rows {@code from..to} added by Java, in cache-sized tiles cut where the store allows; every set's runs clipped to its blocks. */
        void javaRange(int from, int to, int chunkLo, int chunkHi, double[][][] parts) {
            int u = from;
            while (u < to) {
                int v = Math.min(to, u + SUB);
                if (grouped != null && v < to) {
                    v = Math.min(to, Math.max(v, grouped.boundaryAtOrAfter(v)));
                }
                for (int s = 0; s < sets.length; s++) {
                    int b = block[s];
                    for (int blo = u / b * b; blo < v; blo += b) {
                        int a0 = Math.max(u, blo);
                        int a1 = Math.min(v, blo + b);
                        int bi = (blo - chunkLo) / b;
                        int si = s;
                        sets[s].forEachRunIn(a0, a1, (f, t) -> store.addRangeInto(partial(parts, si, bi), f, t));
                    }
                }
                u = v;
            }
        }

        double[] partial(double[][][] parts, int s, int bi) {
            if (parts[s] == null) {
                parts[s] = new double[bmax / block[s]][];
            }
            double[] p = parts[s][bi];
            if (p == null) {
                p = new double[width];
                parts[s][bi] = p;
            }
            return p;
        }

        /** One worker's {@code dgemm} buffers, reused tile after tile. */
        final class Gemm {
            final double[] row = new double[width];
            double[] rowsBuf;
            final int[] at = new int[TILE];
            final int[] active = new int[sets.length];
            final int[] counts = new int[sets.length];
            double[] ind = new double[0];
            double[] res = new double[0];

            /** True when the tile was dense enough and added by {@code dgemm}; false: the caller adds it in Java. */
            boolean tile(int t0, int t1, int chunkLo, double[][][] parts) {
                int ka = 0;
                long members = 0;
                for (int s = 0; s < sets.length; s++) {
                    int[] n = {0};
                    sets[s].forEachRunIn(t0, t1, (f, t) -> n[0] += t - f);
                    if (n[0] > 0) {
                        active[ka] = s;
                        counts[ka++] = n[0];
                        members += n[0];
                    }
                }
                if (ka == 0) {
                    return true;
                }
                int most = 0;
                for (int i = 0; i < ka; i++) {
                    most = Math.max(most, counts[i]);
                }
                if (ka < minGemmSets || members < minDensity * ka * most) {    // few sets, or sparse (the union holds the largest set)
                    return false;
                }
                Arrays.fill(at, 0, t1 - t0, -1);
                int[] u = {0};
                for (int i = 0; i < ka; i++) {
                    sets[active[i]].forEachRunIn(t0, t1, (f, t) -> {
                        for (int r = f; r < t; r++) {
                            if (at[r - t0] < 0) {
                                at[r - t0] = u[0]++;
                            }
                        }
                    });
                }
                int rows = u[0];
                if (members < minDensity * ka * rows) {
                    return false;
                }
                rowsBuf = rowsBuf != null ? rowsBuf : buffer(width * TILE);
                for (int r = t0; r < t1; r++) {                              // the tile's member rows, float64, one column each
                    int c = at[r - t0];
                    if (c >= 0) {
                        store.copy(r, row);
                        System.arraycopy(row, 0, rowsBuf, c * width, width);
                    }
                }
                if (ind.length < rows * ka) {
                    ind = new double[rows * ka];
                }
                Arrays.fill(ind, 0, rows * ka, 0.0);
                for (int i = 0; i < ka; i++) {
                    int col = i * rows;
                    sets[active[i]].forEachRunIn(t0, t1, (f, t) -> {
                        for (int r = f; r < t; r++) {
                            ind[col + at[r - t0]] = 1.0;
                        }
                    });
                }
                if (res.length < width * ka) {
                    res = new double[width * ka];
                }
                la.dgemm(false, false, width, ka, rows, 1.0, rowsBuf, 0, width, ind, 0, rows, 0.0, res, 0, width);
                for (int i = 0; i < ka; i++) {
                    int s = active[i];
                    double[] p = partial(parts, s, (t0 - chunkLo) / block[s]);
                    int off = i * width;
                    for (int j = 0; j < width; j++) {
                        p[j] += res[off + j];
                    }
                }
                return true;
            }
        }
    }
}
