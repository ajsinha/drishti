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
package com.ash.drishti.compute.var;

import com.ash.drishti.compute.vector.Vectors;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * A date's morning rows with its intraday layer on top (VAR_GUIDE.md section 8.4): the morning store unchanged (the hot file is written
 * once), each updated trade's row corrected by its difference ({@code current − morning as stored}), the trades added during the day
 * appended after the morning's ({@code row >= base.rows()}). Immutable: each intraday batch makes a new one, sharing the rows.
 */
final class OverlayVectors implements Vectors {

    private final Vectors base;
    private final NavigableMap<Integer, double[]> delta;
    private final List<double[]> added;

    OverlayVectors(Vectors base, NavigableMap<Integer, double[]> delta, List<double[]> added) {
        this.base = base;
        this.delta = delta;
        this.added = added;
    }

    @Override
    public int width() {
        return base.width();
    }

    @Override
    public int rows() {
        return base.rows() + added.size();
    }

    @Override
    public int chunkCount() {
        return base.chunkCount();
    }

    @Override
    public long bytes() {
        return base.bytes() + (long) (delta.size() + added.size()) * base.width() * Double.BYTES;
    }

    @Override
    public double value(int row, int j) {
        if (row >= base.rows()) {
            return added.get(row - base.rows())[j];
        }
        double[] d = delta.get(row);
        return base.value(row, j) + (d == null ? 0 : d[j]);
    }

    @Override
    public void copy(int row, double[] out) {
        if (row >= base.rows()) {
            System.arraycopy(added.get(row - base.rows()), 0, out, 0, base.width());
            return;
        }
        base.copy(row, out);
        double[] d = delta.get(row);
        if (d != null) {
            for (int j = 0; j < d.length; j++) {
                out[j] += d[j];
            }
        }
    }

    @Override
    public void addInto(double[] acc, int row) {
        if (row >= base.rows()) {
            VarMath.addInto(acc, added.get(row - base.rows()));
            return;
        }
        base.addInto(acc, row);
        double[] d = delta.get(row);
        if (d != null) {
            VarMath.addInto(acc, d);
        }
    }

    @Override
    public void addRangeInto(double[] acc, int from, int to) {
        int t = base.rows();
        int mid = Math.min(to, t);
        if (from < mid) {
            base.addRangeInto(acc, from, mid);
            for (Map.Entry<Integer, double[]> e : delta.subMap(from, true, mid, false).entrySet()) {
                VarMath.addInto(acc, e.getValue());
            }
        }
        for (int r = Math.max(from, t); r < to; r++) {
            VarMath.addInto(acc, added.get(r - t));
        }
    }
}
