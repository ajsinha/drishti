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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.compute.linalg.Blas;
import com.ash.drishti.compute.linalg.JavaBlas;
import com.ash.drishti.compute.linalg.LinearAlgebra;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MultiSums}: on Java every set's vector equals {@link ParallelSums#sum} to the bit (heap rows, mapped float32 rows, grouped rows,
 * one thread and four); on OpenBLAS every figure is within a relative 1e-12 of it (skipped where OpenBLAS does not load).
 */
class MultiSumsTest {

    static final int W = 37;
    static final int N = 21_000;

    static double[][] rows(Random r, int n) {
        double[][] v = new double[n][W];
        for (double[] row : v) {
            for (int j = 0; j < W; j++) {
                row[j] = (r.nextDouble() - 0.5) * Math.pow(10, r.nextInt(7)) + 1e-3 * r.nextGaussian();
            }
        }
        return v;
    }

    static VectorStore heap(double[][] rows) {
        VectorStore.Builder b = new VectorStore.Builder(W, 1_000, Long.MAX_VALUE);
        for (double[] row : rows) {
            b.add(row, W);
        }
        return b.build();
    }

    static List<OrdinalSet> sets(Random r, int n) {
        List<OrdinalSet> out = new ArrayList<>();
        out.add(OrdinalSet.range(0, n));                                  // everything
        out.add(OrdinalSet.range(1_500, 17_333));                         // one long run
        out.add(OrdinalSet.empty());
        out.add(OrdinalSet.range(4_000, 4_500));                          // small: plain row order
        for (double d : new double[] {0.6, 0.3, 0.05, 0.01}) {            // scattered, of several densities
            OrdinalSet.Builder b = new OrdinalSet.Builder();
            for (int i = 0; i < n; i++) {
                if (r.nextDouble() < d) {
                    b.add(i);
                }
            }
            out.add(b.build());
        }
        OrdinalSet.Builder runs = new OrdinalSet.Builder();               // runs of many lengths
        for (int at = 7; at < n; at += 50 + r.nextInt(900)) {
            runs.addRange(at, Math.min(n, at + 1 + r.nextInt(300)));
        }
        out.add(runs.build());
        out.add(out.get(5).or(OrdinalSet.range(10_000, 12_000)));        // overlapping the others
        return out;
    }

    private static void sameBits(Vectors store, List<OrdinalSet> sets) {
        for (int threads : new int[] {1, 4}) {
            try (ParallelSums pool = new ParallelSums(threads, 64)) {
                List<double[]> got = MultiSums.sums(store, sets, pool, JavaBlas.INSTANCE, MultiSums.DEFAULT_MIN_DENSITY);
                for (int i = 0; i < sets.size(); i++) {
                    assertThat(got.get(i)).as("set " + i + ", " + threads + " threads").containsExactly(pool.sum(store, sets.get(i)));
                }
            }
        }
    }

    @Test
    void heapRowsEqualSummingEachSetAlone() {
        Random r = new Random(1);
        VectorStore s = heap(rows(r, N));
        assertThat(MultiSums.kind(s)).isEqualTo(1);
        sameBits(s, sets(r, N));
    }

    @Test
    void mappedFloat32RowsEqualSummingEachSetAlone(@TempDir Path dir) throws IOException {
        Random r = new Random(2);
        double[][] rows = rows(r, N);
        try (MappedMatrix m = MappedMatrix.open(write(dir.resolve("m"), rows), 0, N, W, MappedMatrix.Type.FLOAT32, 3_000)) {
            sameBits(m, sets(r, N));
        }
    }

    @Test
    void groupedRowsEqualSummingEachSetAloneWhereGroupsCrossTiles() {
        Random r = new Random(3);
        double[][] members = rows(r, N);
        List<int[]> groups = new ArrayList<>();
        int[] groupOf = new int[N];
        for (int at = 0; at < N;) {
            int len = r.nextInt(4) == 0 ? 1 + r.nextInt(700) : 1 + r.nextInt(3);   // some groups far longer than a tile
            int end = Math.min(N, at + len);
            for (int i = at; i < end; i++) {
                groupOf[i] = groups.size();
            }
            groups.add(new int[] {at, end});
            at = end;
        }
        int g = groups.size();
        int[] from = new int[g];
        int[] to = new int[g];
        double[][] groupRows = new double[g][W];
        int[] memberRow = new int[N];
        List<double[]> own = new ArrayList<>();
        for (int k = 0; k < g; k++) {
            from[k] = groups.get(k)[0];
            to[k] = groups.get(k)[1];
            for (int i = from[k]; i < to[k]; i++) {
                for (int j = 0; j < W; j++) {
                    groupRows[k][j] += members[i][j];
                }
                memberRow[i] = to[k] - from[k] > 1 ? own.size() : -1;
                if (to[k] - from[k] > 1) {
                    own.add(members[i]);
                }
            }
        }
        GroupedVectors v = new GroupedVectors(heap(groupRows), heap(own.toArray(new double[0][])), groupOf, from, to, memberRow);
        assertThat(MultiSums.kind(v)).isEqualTo(2);
        sameBits(v, sets(r, N));
    }

    @Test
    void openBlasTilesAreWithinTheParityBound() {
        LinearAlgebra nb = Blas.nativeOrNull();
        assumeTrue(nb != null, "the bundled OpenBLAS does not load here");
        Random r = new Random(4);
        VectorStore s = heap(rows(r, N));
        List<OrdinalSet> sets = sets(r, N);
        try (ParallelSums pool = new ParallelSums(4, 64)) {
            for (double density : new double[] {0.0, MultiSums.DEFAULT_MIN_DENSITY}) {
                java.util.concurrent.atomic.AtomicInteger gemms = new java.util.concurrent.atomic.AtomicInteger();
                LinearAlgebra counted = new LinearAlgebra() {
                    public String name() {
                        return nb.name();
                    }

                    public boolean isNative() {
                        return true;
                    }

                    public void dgemm(boolean ta, boolean tb, int m, int n, int k, double al, double[] a, int ao, int lda, double[] b, int bo, int ldb,
                            double be, double[] c, int co, int ldc) {
                        gemms.incrementAndGet();
                        nb.dgemm(ta, tb, m, n, k, al, a, ao, lda, b, bo, ldb, be, c, co, ldc);
                    }

                    public void dgemv(boolean t, int m, int n, double al, double[] a, int ao, int lda, double[] x, int xo, double be, double[] y, int yo) {
                        nb.dgemv(t, m, n, al, a, ao, lda, x, xo, be, y, yo);
                    }

                    public double ddot(int n, double[] x, int xo, double[] y, int yo) {
                        return nb.ddot(n, x, xo, y, yo);
                    }

                    public void daxpy(int n, double al, double[] x, int xo, double[] y, int yo) {
                        nb.daxpy(n, al, x, xo, y, yo);
                    }
                };
                List<double[]> got = MultiSums.sums(s, sets, pool, counted, density);
                if (density == 0) {
                    assertThat(gemms.get()).as("tiles multiplied by OpenBLAS").isPositive();
                }
                double worst = 0;
                for (int i = 0; i < sets.size(); i++) {
                    double[] want = pool.sum(s, sets.get(i));
                    double scale = 0;
                    for (double w : want) {
                        scale = Math.max(scale, Math.abs(w));
                    }
                    for (int j = 0; j < W; j++) {
                        double d = Math.abs(got.get(i)[j] - want[j]);
                        worst = Math.max(worst, scale == 0 ? d : d / scale);
                    }
                }
                System.out.println("MultiSums on OpenBLAS, min density " + density + ": " + gemms.get() + " dgemm tiles, largest relative difference " + worst);
                assertThat(worst).isLessThanOrEqualTo(1e-12);
            }
        }
    }

    @Test
    void aClusterAgreedOnJavaSumsInJavaWhateverThisMemberBound() {
        Random r = new Random(6);
        VectorStore s = heap(rows(r, N));
        List<OrdinalSet> sets = sets(r, N);
        try (ParallelSums pool = new ParallelSums(4, 64)) {
            Blas.clusterOnJava("member node-c runs BLAS on Java");
            assertThat(Blas.forClusterSums()).isSameAs(JavaBlas.INSTANCE);
            List<double[]> got = MultiSums.sums(s, sets, pool);
            for (int i = 0; i < sets.size(); i++) {
                assertThat(got.get(i)).containsExactly(pool.sum(s, sets.get(i)));
            }
        } finally {
            Blas.clusterOnJava(null);
        }
    }

    @Test
    void otherStoresAreSummedSetBySet() {
        Vectors odd = new Vectors() {
            final VectorStore s = heap(MultiSumsTest.rows(new Random(5), 3_000));

            public int width() {
                return W;
            }

            public int rows() {
                return s.rows();
            }

            public int chunkCount() {
                return 1;
            }

            public long bytes() {
                return 0;
            }

            public double value(int row, int j) {
                return s.value(row, j);
            }

            public void copy(int row, double[] out) {
                s.copy(row, out);
            }

            public void addInto(double[] acc, int row) {
                s.addInto(acc, row);
            }

            public void addRangeInto(double[] acc, int from, int to) {
                s.addRangeInto(acc, from, to);
            }
        };
        assertThat(MultiSums.kind(odd)).isZero();
        sameBits(odd, List.of(OrdinalSet.range(0, 3_000), OrdinalSet.range(100, 2_500)));
    }

    static Path write(Path f, double[][] rows) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(rows.length * W * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (double[] row : rows) {
            for (double v : row) {
                b.putFloat((float) v);
            }
        }
        Files.write(f, b.array());
        return f;
    }
}
