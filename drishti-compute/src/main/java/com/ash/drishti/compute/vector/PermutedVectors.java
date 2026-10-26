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
 * Another order of the same rows: row {@code i} is row {@code order[i]} of the source. A load reads the lake's rows in the lake's order
 * and serves them in the hierarchy's: this view lets the node aggregates and the hot file be summed in hierarchy order straight from
 * the load's spill, without a second copy of the rows.
 */
public final class PermutedVectors implements Vectors {

    private final Vectors source;
    private final int[] order;

    public PermutedVectors(Vectors source, int[] order) {
        if (order.length != source.rows()) {
            throw new IllegalArgumentException("the order names " + order.length + " rows, the source has " + source.rows());
        }
        this.source = source;
        this.order = order;
    }

    @Override
    public int width() {
        return source.width();
    }

    @Override
    public int rows() {
        return order.length;
    }

    @Override
    public int chunkCount() {
        return source.chunkCount();
    }

    @Override
    public long bytes() {
        return source.bytes();
    }

    @Override
    public double value(int row, int j) {
        return source.value(order[row], j);
    }

    @Override
    public void copy(int row, double[] out) {
        source.copy(order[row], out);
    }

    @Override
    public void addInto(double[] acc, int row) {
        source.addInto(acc, order[row]);
    }

    @Override
    public void addRangeInto(double[] acc, int from, int to) {
        int r = from;
        while (r < to) {                                               // rows the source keeps together are read as one scan
            int s = order[r];
            int n = 1;
            while (r + n < to && order[r + n] == s + n) {
                n++;
            }
            source.addRangeInto(acc, s, s + n);
            r += n;
        }
    }
}
