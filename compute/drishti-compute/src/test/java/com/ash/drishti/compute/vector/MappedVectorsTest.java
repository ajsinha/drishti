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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mapped tiers of the vector store (VAR_GUIDE.md section 26): a float32 and a float64 matrix in a file read back like the heap store
 * (several mappings when told to), rows grouped into positions served from their group rows, a permuted view, the load's spill, and the
 * guard that unmaps only when no reader is inside: readers racing a close never crash, they finish or are told the date was unloaded.
 */
class MappedVectorsTest {

    private static final int W = 37;

    private static double[][] rows(int n, long seed) {
        Random r = new Random(seed);
        double[][] out = new double[n][W];
        for (double[] row : out) {
            for (int j = 0; j < W; j++) {
                row[j] = Math.rint(r.nextGaussian() * 10_000);
            }
        }
        return out;
    }

    private static Path write(Path dir, double[][] rows, MappedMatrix.Type type, int lead) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(lead + rows.length * W * type.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        b.position(lead);
        for (double[] row : rows) {
            for (double v : row) {
                if (type == MappedMatrix.Type.FLOAT32) {
                    b.putFloat((float) v);
                } else {
                    b.putDouble(v);
                }
            }
        }
        Path f = dir.resolve(type + ".bin");
        Files.write(f, b.array());
        return f;
    }

    private static double[] brute(double[][] rows, int from, int to) {
        double[] acc = new double[W];
        for (int r = from; r < to; r++) {
            for (int j = 0; j < W; j++) {
                acc[j] += rows[r][j];
            }
        }
        return acc;
    }

    @Test
    void aMappedMatrixSumsLikeTheRowsAcrossSeveralMappings(@TempDir Path dir) throws IOException {
        double[][] rows = rows(250, 1);
        for (MappedMatrix.Type type : MappedMatrix.Type.values()) {
            try (MappedMatrix m = MappedMatrix.open(write(dir, rows, type, 128), 128, rows.length, W, type, 16)) {
                assertThat(m.chunkCount()).isEqualTo(16);                       // 250 rows, 16 a mapping
                Random r = new Random(2);
                for (int k = 0; k < 50; k++) {
                    int a = r.nextInt(250);
                    int b = a + r.nextInt(250 - a + 1);
                    double[] acc = new double[W];
                    m.addRangeInto(acc, a, b);
                    assertThat(acc).containsExactly(brute(rows, a, b));          // whole numbers below 2^24: exact in float32 too
                }
                double[] one = new double[W];
                m.copy(17, one);
                assertThat(one).containsExactly(rows[17]);
                assertThat(m.value(249, W - 1)).isEqualTo(rows[249][W - 1]);
                assertThat(m.bytes()).isEqualTo(250L * W * type.bytes());
            }
        }
    }

    @Test
    void parallelSumsOverAMappedMatrixEqualTheHeapStore(@TempDir Path dir) throws IOException {
        double[][] rows = rows(3000, 3);
        VectorStore.Builder b = new VectorStore.Builder(W, 512, Long.MAX_VALUE);
        for (double[] row : rows) {
            b.add(row, W);
        }
        VectorStore heap = b.build();
        try (MappedMatrix m = MappedMatrix.open(write(dir, rows, MappedMatrix.Type.FLOAT32, 0), 0, rows.length, W, MappedMatrix.Type.FLOAT32, 700);
                ParallelSums sums = new ParallelSums(4, 64)) {
            OrdinalSet set = new OrdinalSet.Builder().addRange(5, 2900).build().andNot(OrdinalSet.of(10, 11, 400, 2000));
            assertThat(sums.sum(m, set)).containsExactly(sums.sum(heap, set));
        }
    }

    @Test
    void groupedRowsServeWholeGroupsFromTheirSumAndPartsFromTheMembers(@TempDir Path dir) throws IOException {
        // members 0..99 in groups of 1 to 4; a group's row is its members' sum; members of groups of one have no row of their own
        double[][] members = rows(100, 4);
        Random r = new Random(5);
        int[] groupOf = new int[100];
        java.util.List<int[]> runs = new java.util.ArrayList<>();
        for (int m = 0; m < 100; ) {
            int n = Math.min(100 - m, 1 + r.nextInt(4));
            runs.add(new int[] {m, m + n});
            for (int i = m; i < m + n; i++) {
                groupOf[i] = runs.size() - 1;
            }
            m += n;
        }
        int g = runs.size();
        int[] from = new int[g];
        int[] to = new int[g];
        double[][] groupRows = new double[g][];
        int[] memberRow = new int[100];
        java.util.List<double[]> own = new java.util.ArrayList<>();
        for (int k = 0; k < g; k++) {
            from[k] = runs.get(k)[0];
            to[k] = runs.get(k)[1];
            groupRows[k] = brute(members, from[k], to[k]);
            for (int i = from[k]; i < to[k]; i++) {
                memberRow[i] = to[k] - from[k] > 1 ? own.size() : -1;
                if (to[k] - from[k] > 1) {
                    own.add(members[i]);
                }
            }
        }
        Path gf = dir.resolve("groups");
        Files.createDirectories(gf);
        Path mf = dir.resolve("members");
        Files.createDirectories(mf);
        try (MappedMatrix gm = MappedMatrix.open(write(gf, groupRows, MappedMatrix.Type.FLOAT32, 0), 0, g, W, MappedMatrix.Type.FLOAT32, 0);
                MappedMatrix mm = MappedMatrix.open(write(mf, own.toArray(new double[0][]), MappedMatrix.Type.FLOAT32, 0), 0, own.size(), W,
                        MappedMatrix.Type.FLOAT32, 0)) {
            GroupedVectors v = new GroupedVectors(gm, mm, groupOf, from, to, memberRow);
            assertThat(v.rows()).isEqualTo(100);
            for (int a = 0; a < 100; a += 3) {
                for (int b = a; b <= 100; b += 7) {
                    double[] acc = new double[W];
                    v.addRangeInto(acc, a, b);
                    assertThat(acc).as(a + ".." + b).containsExactly(brute(members, a, b));
                }
                double[] one = new double[W];
                v.copy(a, one);
                assertThat(one).containsExactly(members[a]);
            }
        }
    }

    @Test
    void aPermutedViewReadsTheSourceInAnotherOrder() {
        double[][] rows = rows(40, 6);
        VectorStore.Builder b = new VectorStore.Builder(W, 16, Long.MAX_VALUE);
        for (double[] row : rows) {
            b.add(row, W);
        }
        int[] order = new int[40];
        for (int i = 0; i < 40; i++) {
            order[i] = i < 20 ? 39 - i : i - 20;                         // reversed, then a run kept together
        }
        PermutedVectors p = new PermutedVectors(b.build(), order);
        double[] acc = new double[W];
        p.addRangeInto(acc, 0, 40);
        assertThat(acc).containsExactly(brute(rows, 0, 40));
        double[] one = new double[W];
        p.copy(3, one);
        assertThat(one).containsExactly(rows[36]);
    }

    @Test
    void theSpillKeepsRowsInTheirOrderAndIsDeletedOnClose(@TempDir Path dir) throws IOException {
        double[][] rows = rows(10, 7);
        Path f = Files.createFile(dir.resolve("spill.f64"));
        RowSpill s = new RowSpill(f, W, Long.MAX_VALUE, W * 4L);
        for (double[] row : rows) {
            s.add(row, W);
        }
        s.add(new double[] {1, 2}, 2);                                   // a short row is padded with NaN
        MappedMatrix m = s.finish();
        double[] one = new double[W];
        m.copy(4, one);
        assertThat(one).containsExactly(rows[4]);
        assertThat(m.value(10, 1)).isEqualTo(2.0);
        assertThat(m.value(10, 2)).isNaN();
        s.close();
        assertThat(Files.exists(f)).isFalse();
        Path g = Files.createFile(dir.resolve("small.f64"));
        RowSpill tight = new RowSpill(g, W, W * 4L * 3, W * 4L);
        tight.add(rows[0], W);
        tight.add(rows[1], W);
        tight.add(rows[2], W);
        assertThatThrownBy(() -> tight.add(rows[3], W)).isInstanceOf(VectorStore.BudgetExceeded.class);
        tight.close();
        assertThat(Files.exists(g)).isFalse();
    }

    @Test
    void readersRacingACloseFinishOrAreToldNeverCrash(@TempDir Path dir) throws Exception {
        double[][] rows = rows(2000, 8);
        Path f = write(dir, rows, MappedMatrix.Type.FLOAT32, 0);
        for (int round = 0; round < 20; round++) {
            MappedMatrix m = MappedMatrix.open(f, 0, rows.length, W, MappedMatrix.Type.FLOAT32, 0);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch go = new CountDownLatch(1);
            java.util.List<Future<Integer>> fs = new java.util.ArrayList<>();
            for (int t = 0; t < 4; t++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    int done = 0;
                    for (int i = 0; i < 200; i++) {
                        try {
                            double[] acc = new double[W];
                            m.addRangeInto(acc, 0, 2000);
                            done++;
                        } catch (MappedMatrix.Closed e) {
                            return done;
                        }
                    }
                    return done;
                }));
            }
            go.countDown();
            Thread.sleep(round % 3);
            m.close();
            for (Future<Integer> x : fs) {
                assertThat(x.get()).isGreaterThanOrEqualTo(0);
            }
            pool.shutdown();
            assertThat(m.unmapped()).isTrue();
        }
        boolean[] ran = new boolean[1];
        MappedMatrix m = MappedMatrix.open(f, 0, rows.length, W, MappedMatrix.Type.FLOAT32, 0);
        m.close();
        m.closeThen(() -> ran[0] = true);                                  // closed before: the action runs at once, once
        assertThat(ran[0]).isTrue();
        assertThat(MappedMatrix.mappingMethod()).contains("unmapped on close");
    }
}
