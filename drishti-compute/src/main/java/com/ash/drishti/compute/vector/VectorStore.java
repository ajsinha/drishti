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

/**
 * Vectors of one width (509 scenario P&amp;Ls) addressed by ordinal, in memory, row-major in {@code double[]} chunks
 * (VAR_DATA_MODEL.md section 12): row {@code i} is {@code width} contiguous doubles, so summing rows walks contiguous memory and the
 * JIT turns the inner loop into SIMD additions. A chunk holds {@code rowsPerChunk} rows (65,536 by default: 267 MB of 509-wide rows),
 * far below Java's array limit; there is no object per row and nothing is boxed.
 *
 * <p>Immutable once built ({@link Builder}), so any number of readers sum at once without locks. Knows vectors, not VaR.
 */
public final class VectorStore {

    private final int width;
    private final int rows;
    private final int rowsPerChunk;
    private final double[][] chunks;

    private VectorStore(int width, int rows, int rowsPerChunk, double[][] chunks) {
        this.width = width;
        this.rows = rows;
        this.rowsPerChunk = rowsPerChunk;
        this.chunks = chunks;
    }

    public int width() {
        return width;
    }

    public int rows() {
        return rows;
    }

    public int rowsPerChunk() {
        return rowsPerChunk;
    }

    public int chunkCount() {
        return chunks.length;
    }

    /** Bytes the vectors hold in memory (the budget's measure). */
    public long bytes() {
        return (long) rows * width * Double.BYTES;
    }

    /** Value {@code j} of row {@code row}. */
    public double value(int row, int j) {
        return chunks[row / rowsPerChunk][(row % rowsPerChunk) * width + j];
    }

    /** Row {@code row} copied into {@code out} (length {@code >= width}). */
    public void copy(int row, double[] out) {
        System.arraycopy(chunks[row / rowsPerChunk], (row % rowsPerChunk) * width, out, 0, width);
    }

    /** Row {@code row} added into {@code acc}: the inner loop of every sum. */
    public void addInto(double[] acc, int row) {
        double[] c = chunks[row / rowsPerChunk];
        int off = (row % rowsPerChunk) * width;
        for (int j = 0; j < width; j++) {
            acc[j] += c[off + j];
        }
    }

    /** Row {@code row} times {@code factor} added into {@code acc} (a part of a linear position, a scaled hypothetical). */
    public void addScaledInto(double[] acc, int row, double factor) {
        double[] c = chunks[row / rowsPerChunk];
        int off = (row % rowsPerChunk) * width;
        for (int j = 0; j < width; j++) {
            acc[j] += factor * c[off + j];
        }
    }

    /**
     * Rows {@code from} (included) to {@code to} (excluded) added into {@code acc}: a node of the primary hierarchy is such a run of
     * ordinals, summed as one sequential scan per chunk.
     */
    public void addRangeInto(double[] acc, int from, int to) {
        int r = from;
        while (r < to) {
            int ci = r / rowsPerChunk;
            double[] c = chunks[ci];
            int end = Math.min(to, (ci + 1) * rowsPerChunk);
            int off = (r % rowsPerChunk) * width;
            int stop = off + (end - r) * width;
            for (int o = off; o < stop; o += width) {
                for (int j = 0; j < width; j++) {
                    acc[j] += c[o + j];
                }
            }
            r = end;
        }
    }

    /**
     * Value {@code j} of each of the rows {@code from..to} added up: a strided read, for one scenario across many rows (a contribution
     * at one scenario date) without a whole sum.
     */
    public double sumColumn(int from, int to, int j) {
        double s = 0;
        for (int r = from; r < to; r++) {
            s += chunks[r / rowsPerChunk][(r % rowsPerChunk) * width + j];
        }
        return s;
    }

    /** Builds a store row by row, within a memory budget; the chunks are allocated as rows arrive (no pre-sizing needed). */
    public static final class Builder {
        private final int width;
        private final int rowsPerChunk;
        private final long budgetBytes;
        private final List<double[]> chunks = new ArrayList<>();
        private int rows;

        /**
         * @param width every vector's length
         * @param rowsPerChunk rows per chunk (a chunk is {@code rowsPerChunk * width} doubles)
         * @param budgetBytes the most bytes of vectors this store may hold; a row past it is refused ({@link BudgetExceeded})
         */
        public Builder(int width, int rowsPerChunk, long budgetBytes) {
            if (width <= 0 || rowsPerChunk <= 0) {
                throw new IllegalArgumentException("width and rows per chunk must be positive");
            }
            if ((long) width * rowsPerChunk > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("a chunk of " + rowsPerChunk + " rows of " + width + " doubles is larger than a Java array");
            }
            this.width = width;
            this.rowsPerChunk = rowsPerChunk;
            this.budgetBytes = budgetBytes;
        }

        public int width() {
            return width;
        }

        public int rows() {
            return rows;
        }

        /** Adds a row ({@code values[0..width)}; a shorter vector is padded with NaN); returns its ordinal. */
        public int add(double[] values, int length) {
            long need = (long) (rows + 1) * width * Double.BYTES;
            if (need > budgetBytes) {
                throw new BudgetExceeded(rows + 1, width, budgetBytes);
            }
            int ci = rows / rowsPerChunk;
            if (ci == chunks.size()) {
                // the last chunk is cut to the rows it needs at build(); a full-size one is allocated while rows arrive
                chunks.add(new double[rowsPerChunk * width]);
            }
            double[] c = chunks.get(ci);
            int off = (rows % rowsPerChunk) * width;
            int n = Math.min(length, width);
            System.arraycopy(values, 0, c, off, Math.max(0, n));
            for (int j = Math.max(0, n); j < width; j++) {
                c[off + j] = Double.NaN;
            }
            return rows++;
        }

        public VectorStore build() {
            double[][] out = chunks.toArray(new double[0][]);
            int last = rows % rowsPerChunk;
            if (out.length > 0 && last != 0) {
                out[out.length - 1] = java.util.Arrays.copyOf(out[out.length - 1], last * width);
            }
            return new VectorStore(width, rows, rowsPerChunk, out);
        }

        /**
         * A store whose row {@code i} is this builder's row {@code order[i]}: the rows put in the hierarchy's order after they were read
         * in the lake's (each node then a run of ordinals). Holds both copies while it runs.
         */
        public VectorStore permuted(int[] order) {
            if (order.length != rows) {
                throw new IllegalArgumentException("the order names " + order.length + " rows, the store has " + rows);
            }
            VectorStore src = build();
            Builder b = new Builder(width, rowsPerChunk, Long.MAX_VALUE);
            double[] row = new double[width];
            for (int o : order) {
                src.copy(o, row);
                b.add(row, width);
            }
            chunks.clear();
            return b.build();
        }
    }

    /** A load that would take more memory than the configured budget ({@code drishti.var.memory-budget-mb}). */
    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded(int rows, int width, long budget) {
            super(String.format("%,d vectors of %d values need %,d MB; the vector store's budget is %,d MB (drishti.var.memory-budget-mb)",
                    rows, width, (long) rows * width * Double.BYTES / (1024 * 1024), budget / (1024 * 1024)));
        }
    }
}
