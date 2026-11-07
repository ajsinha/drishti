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
package com.ash.drishti.benchmarks;

import com.ash.drishti.nativ.linalg.Blas;
import com.ash.drishti.nativ.linalg.JavaBlas;
import com.ash.drishti.nativ.linalg.LinearAlgebra;
import com.ash.drishti.compute.vector.MappedMatrix;
import com.ash.drishti.compute.vector.MultiSums;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Many VaR scopes at once (BENCHMARK.md, the many-scope rows; HIGH_PERFORMANCE_NATIVE.md section 6.4): a hot file's float32 position rows
 * (P positions x 509 scenarios, memory-mapped as the server maps them), K scope sets, summed three ways: one set at a time as before
 * ({@link ParallelSums#sum}), {@link MultiSums} on Java (the same bits) and {@link MultiSums} on OpenBLAS. Plain timing, best of
 * {@code repeats}; light enough for a developer machine at 1M positions with {@code -Xmx2g} (the rows are off the heap).
 *
 * <pre>
 *   java -Xmx2g -cp ... com.ash.drishti.benchmarks.ManyScopeBenchmark &lt;folder&gt; [positions] [threads] [repeats]
 * </pre>
 *
 * Shapes: {@code cube} (K cells, each row in one cell at random: a cube view's cells, 1/K dense), {@code dense} (K sets, each row in each
 * set with probability 1/2: overlapping portfolios and ad-hoc sets), {@code desks} (K contiguous desks: the hierarchy's own shape).
 */
public final class ManyScopeBenchmark {

    static final int S = 509;

    private ManyScopeBenchmark() {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        int p = args.length > 1 ? Integer.parseInt(args[1]) : 200_000;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int repeats = args.length > 3 ? Integer.parseInt(args[3]) : 3;
        Files.createDirectories(dir);
        Path f = dir.resolve("rows-" + p + ".f32");
        if (!Files.exists(f) || Files.size(f) != 4L * p * S) {
            write(f, p);
        }
        LinearAlgebra nb = Blas.get().isNative() ? Blas.get() : null;
        System.out.println("positions " + p + " x " + S + " scenarios (float32, mapped), threads " + threads + ", " + Blas.status().line());
        System.out.printf("%-6s %5s %12s %12s %12s %9s %9s %14s%n", "shape", "K", "one-by-one", "multi-java", "multi-blas", "java x", "blas x",
                "max rel diff");
        try (MappedMatrix m = MappedMatrix.open(f, 0, p, S, MappedMatrix.Type.FLOAT32, 0); ParallelSums pool = new ParallelSums(threads, 4_096)) {
            for (String shape : List.of("cube", "dense", "desks")) {
                for (int k : shape.equals("dense") ? new int[] {10, 100} : new int[] {10, 100, 1_000}) {
                    List<OrdinalSet> sets = sets(shape, k, p, new Random(k));
                    double[][] base = new double[k][];
                    double t0 = best(repeats, () -> {
                        for (int i = 0; i < sets.size(); i++) {
                            base[i] = pool.sum(m, sets.get(i));
                        }
                    });
                    List<double[]>[] jv = new List[1];
                    double t1 = best(repeats, () -> jv[0] = MultiSums.sums(m, sets, pool, JavaBlas.INSTANCE, MultiSums.DEFAULT_MIN_DENSITY));
                    for (int i = 0; i < k; i++) {
                        if (!java.util.Arrays.equals(base[i], jv[0].get(i))) {
                            throw new IllegalStateException("MultiSums on Java differs from one-by-one at set " + i);
                        }
                    }
                    double t2 = Double.NaN;
                    double diff = Double.NaN;
                    if (nb != null) {
                        List<double[]>[] nv = new List[1];
                        t2 = best(repeats, () -> nv[0] = MultiSums.sums(m, sets, pool, nb, MultiSums.DEFAULT_MIN_DENSITY));
                        diff = maxRel(base, nv[0]);
                    }
                    System.out.printf("%-6s %5d %10.1f ms %10.1f ms %10.1f ms %8.2fx %8.2fx %14.2e%n", shape, k, t0, t1, t2, t0 / t1, t0 / t2, diff);
                }
            }
        }
    }

    static List<OrdinalSet> sets(String shape, int k, int p, Random r) {
        List<OrdinalSet> out = new ArrayList<>();
        switch (shape) {
            case "cube" -> {
                OrdinalSet.Builder[] b = new OrdinalSet.Builder[k];
                for (int i = 0; i < k; i++) {
                    b[i] = new OrdinalSet.Builder();
                }
                for (int row = 0; row < p; row++) {
                    b[r.nextInt(k)].add(row);
                }
                for (OrdinalSet.Builder x : b) {
                    out.add(x.build());
                }
            }
            case "dense" -> {
                for (int i = 0; i < k; i++) {
                    OrdinalSet.Builder b = new OrdinalSet.Builder();
                    for (int row = 0; row < p; row++) {
                        if (r.nextBoolean()) {
                            b.add(row);
                        }
                    }
                    out.add(b.build());
                }
            }
            default -> {
                int step = p / k;
                for (int i = 0; i < k; i++) {
                    out.add(OrdinalSet.range(i * step, i == k - 1 ? p : (i + 1) * step));
                }
            }
        }
        return out;
    }

    static double best(int repeats, Runnable r) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < repeats; i++) {
            long t = System.nanoTime();
            r.run();
            best = Math.min(best, (System.nanoTime() - t) / 1e6);
        }
        return best;
    }

    static double maxRel(double[][] want, List<double[]> got) {
        double worst = 0;
        for (int i = 0; i < want.length; i++) {
            double scale = 0;
            for (double w : want[i]) {
                scale = Math.max(scale, Math.abs(w));
            }
            for (int j = 0; j < want[i].length; j++) {
                double d = Math.abs(got.get(i)[j] - want[i][j]);
                worst = Math.max(worst, scale == 0 ? d : d / scale);
            }
        }
        return worst;
    }

    /** P rows of synthetic scenario P&amp;L (float32, little-endian), written in pieces so the heap stays small. */
    static void write(Path f, int p) throws IOException {
        Random r = new Random(42);
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer b = ByteBuffer.allocate(4 * S * 1_024).order(ByteOrder.LITTLE_ENDIAN);
            for (int row = 0; row < p; row++) {
                double scale = Math.pow(10, 2 + r.nextInt(5));
                for (int j = 0; j < S; j++) {
                    b.putFloat((float) (r.nextGaussian() * scale));
                }
                if (!b.hasRemaining() || row == p - 1) {
                    b.flip();
                    while (b.hasRemaining()) {
                        ch.write(b);
                    }
                    b.clear();
                }
            }
        }
    }
}
