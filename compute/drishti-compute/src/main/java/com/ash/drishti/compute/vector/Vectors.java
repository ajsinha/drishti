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

/**
 * Rows of one width (509 scenario P&amp;Ls) addressed by ordinal: what a sum reads. Three tiers implement it: {@link VectorStore} (float64
 * on the heap), {@link MappedMatrix} (a float32 or float64 matrix of a memory-mapped file) and {@link GroupedVectors} (trade rows served
 * from their positions' rows where a run covers a whole position). Every sum is accumulated in {@code double}, whatever a row is stored
 * as; implementations are safe for any number of concurrent readers.
 */
public interface Vectors {

    /** Every row's length. */
    int width();

    /** The rows. */
    int rows();

    /** Chunks the rows are kept in (a sum splits across threads only when it is worth it: {@link ParallelSums}). */
    int chunkCount();

    /** Bytes the rows take where they are kept (heap, or the mapped file's pages). */
    long bytes();

    /** Value {@code j} of row {@code row}. */
    double value(int row, int j);

    /** Row {@code row} copied into {@code out} (length {@code >= width}). */
    void copy(int row, double[] out);

    /** Row {@code row} added into {@code acc}. */
    void addInto(double[] acc, int row);

    /** Rows {@code from} (included) to {@code to} (excluded) added into {@code acc}: one sequential scan. */
    void addRangeInto(double[] acc, int from, int to);
}
