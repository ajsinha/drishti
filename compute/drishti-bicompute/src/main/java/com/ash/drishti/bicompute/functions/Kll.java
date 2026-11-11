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
package com.ash.drishti.bicompute.functions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A KLL quantile sketch (Karnin, Lang and Liberty, 2016; HOW_IT_FITS.md section A26.6), written in the module: Apache DataSketches (decision 2)
 * is not resolvable offline in this build (only the 2017 {@code com.yahoo.datasketches} 0.9.0, which has no KLL), so the same algorithm is
 * here, about 300 lines, with the same parameter {@code k} and the same stated error (a normalised rank error of about 1.65% at k = 200,
 * DataSketches' published figure, measured by {@code SketchErrorTest} on seeded data).
 *
 * <p>Levels of retained values: a value at level h stands for 2^h values. Level h holds at most about {@code k·(2/3)^(H−1−h)} (at least 8);
 * a full level is sorted and every other value (offset 0 or 1) promoted to the next level. The coin is a hash of the sketch's own count and
 * level, never a random generator: the same values in the same order make the same bytes, so merges in a fixed order repeat to the digit.
 * Weighted values (a weight w goes to the levels of w's set bits) let a summary of quantile points from SQL become a sketch.
 */
public final class Kll {

    static final byte TAG = 'K';
    private static final int MIN_CAP = 8;

    final int k;
    double[][] levels;
    int[] sizes;
    long n;
    double min = Double.NaN;
    double max = Double.NaN;

    public Kll(int k) {
        if (k < 8 || k > 65_535) {
            throw new IllegalArgumentException("KLL k is 8 to 65,535, not " + k);
        }
        this.k = k;
        this.levels = new double[][] {new double[k]};
        this.sizes = new int[1];
    }

    public int k() {
        return k;
    }

    /** The state as the playground shows it: the sketch's accuracy parameter and the values summarised. */
    @Override
    public String toString() {
        return "kll k=" + k() + " n=" + n;
    }

    public long n() {
        return n;
    }

    /** The normalised rank error stated for a k: 1.65% at k = 200, scaling as 1/k (DataSketches' table, rounded up). */
    public static double rankError(int k) {
        return 0.0165 * 200.0 / k;
    }

    private int capacity(int h) {
        int depth = levels.length - 1 - h;
        return Math.max(MIN_CAP, (int) Math.ceil(k * Math.pow(2.0 / 3.0, depth)));
    }

    private int totalCapacity() {
        int c = 0;
        for (int h = 0; h < levels.length; h++) {
            c += capacity(h);
        }
        return c;
    }

    private int retained() {
        int r = 0;
        for (int s : sizes) {
            r += s;
        }
        return r;
    }

    private void put(int h, double x) {
        while (h >= levels.length) {
            levels = Arrays.copyOf(levels, levels.length + 1);
            levels[levels.length - 1] = new double[MIN_CAP];
            sizes = Arrays.copyOf(sizes, sizes.length + 1);
        }
        if (sizes[h] == levels[h].length) {
            levels[h] = Arrays.copyOf(levels[h], Math.max(MIN_CAP, levels[h].length * 2));
        }
        levels[h][sizes[h]++] = x;
    }

    private void bounds(double x) {
        min = Double.isNaN(min) ? x : Math.min(min, x);
        max = Double.isNaN(max) ? x : Math.max(max, x);
    }

    /** One value added (NaN is ignored). */
    public void update(double x) {
        if (Double.isNaN(x)) {
            return;
        }
        put(0, x);
        n++;
        bounds(x);
        compress();
    }

    /** A value standing for {@code w} values: one copy at each level of w's set bits. */
    public void update(double x, long w) {
        if (Double.isNaN(x) || w <= 0) {
            return;
        }
        for (int h = 0; h < 63; h++) {
            if ((w & (1L << h)) != 0) {
                put(h, x);
            }
        }
        n += w;
        bounds(x);
        compress();
    }

    private void compress() {
        while (retained() > totalCapacity()) {
            boolean done = false;
            for (int h = 0; h < levels.length; h++) {
                if (sizes[h] >= capacity(h)) {
                    compact(h);
                    done = true;
                    break;
                }
            }
            if (!done) {
                compact(lowestNonEmpty());
            }
        }
    }

    private int lowestNonEmpty() {
        for (int h = 0; h < sizes.length; h++) {
            if (sizes[h] > 1) {
                return h;
            }
        }
        return 0;
    }

    /** Level h sorted; an odd value left behind; every other value of the rest promoted (offset by the coin). */
    private void compact(int h) {
        int s = sizes[h];
        if (s < 2) {
            return;
        }
        double[] a = Arrays.copyOf(levels[h], s);
        Arrays.sort(a);
        int start = s % 2;                       // the smallest value stays when the count is odd
        int coin = coin(h);
        double[] up = new double[(s - start) / 2];
        for (int i = 0; i < up.length; i++) {
            up[i] = a[start + 2 * i + coin];
        }
        sizes[h] = 0;
        if (start == 1) {
            levels[h][sizes[h]++] = a[0];
        }
        for (double x : up) {
            put(h + 1, x);
        }
    }

    private int coin(int h) {
        long z = n * 0x9E3779B97F4A7C15L + h * 0xBF58476D1CE4E5B9L + retained();
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (int) ((z ^ (z >>> 31)) & 1);
    }

    /** Two sketches as one (a new sketch): their levels side by side, then compacted. The k is the smaller of the two. */
    public Kll merge(Kll o) {
        Kll out = new Kll(Math.min(k, o.k));
        for (Kll s : List.of(this, o)) {
            for (int h = 0; h < s.levels.length; h++) {
                for (int i = 0; i < s.sizes[h]; i++) {
                    out.put(h, s.levels[h][i]);
                }
            }
        }
        out.n = n + o.n;
        out.min = Double.isNaN(min) ? o.min : Double.isNaN(o.min) ? min : Math.min(min, o.min);
        out.max = Double.isNaN(max) ? o.max : Double.isNaN(o.max) ? max : Math.max(max, o.max);
        out.compress();
        return out;
    }

    /** The retained values sorted with their weights, as (values, cumulative weights). */
    private Object[] sorted() {
        int r = retained();
        double[] v = new double[r];
        long[] w = new long[r];
        int i = 0;
        for (int h = 0; h < levels.length; h++) {
            for (int j = 0; j < sizes[h]; j++) {
                v[i] = levels[h][j];
                w[i] = 1L << h;
                i++;
            }
        }
        Integer[] idx = new Integer[r];
        for (int j = 0; j < r; j++) {
            idx[j] = j;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(v[a], v[b]));
        double[] sv = new double[r];
        long[] cw = new long[r];
        long c = 0;
        for (int j = 0; j < r; j++) {
            sv[j] = v[idx[j]];
            c += w[idx[j]];
            cw[j] = c;
        }
        return new Object[] {sv, cw};
    }

    /** The q-quantile (0 ≤ q ≤ 1): the smallest retained value whose cumulative weight reaches q·n; q = 0 the minimum, q = 1 the maximum. */
    public Double quantile(double q) {
        if (n == 0) {
            return null;
        }
        if (q <= 0) {
            return min;
        }
        if (q >= 1) {
            return max;
        }
        Object[] s = sorted();
        double[] v = (double[]) s[0];
        long[] cw = (long[]) s[1];
        long total = cw[cw.length - 1];
        double target = q * total;
        for (int j = 0; j < v.length; j++) {
            if (cw[j] >= target) {
                return v[j];
            }
        }
        return max;
    }

    /** The share of values at or below x. */
    public double rank(double x) {
        if (n == 0) {
            return Double.NaN;
        }
        Object[] s = sorted();
        double[] v = (double[]) s[0];
        long[] cw = (long[]) s[1];
        long below = 0;
        for (int j = 0; j < v.length && v[j] <= x; j++) {
            below = cw[j];
        }
        return (double) below / cw[cw.length - 1];
    }

    /** The bytes the state holds. */
    public long bytes() {
        return 64 + 8L * retained() + 4L * sizes.length;
    }

    public byte[] encode() {
        int r = retained();
        ByteBuffer bb = ByteBuffer.allocate(1 + 4 + 8 + 16 + 4 + 4 * sizes.length + 8 * r).put(TAG).putInt(k).putLong(n).putDouble(min)
                .putDouble(max).putInt(sizes.length);
        for (int s : sizes) {
            bb.putInt(s);
        }
        for (int h = 0; h < levels.length; h++) {
            for (int j = 0; j < sizes[h]; j++) {
                bb.putDouble(levels[h][j]);
            }
        }
        return bb.array();
    }

    public static Kll decode(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        if (bb.get() != TAG) {
            throw new IllegalArgumentException("not a quantile sketch");
        }
        Kll s = new Kll(bb.getInt());
        s.n = bb.getLong();
        s.min = bb.getDouble();
        s.max = bb.getDouble();
        int nl = bb.getInt();
        int[] sz = new int[nl];
        for (int h = 0; h < nl; h++) {
            sz[h] = bb.getInt();
        }
        s.levels = new double[nl][];
        s.sizes = new int[nl];
        for (int h = 0; h < nl; h++) {
            s.levels[h] = new double[Math.max(MIN_CAP, sz[h])];
            for (int j = 0; j < sz[h]; j++) {
                s.levels[h][j] = bb.getDouble();
            }
            s.sizes[h] = sz[h];
        }
        return s;
    }

    /**
     * A sketch from the quantile points SQL computed for a cell ({@code quantile_disc(x, [0, 1/K, ..., 1])} and {@code count(x)}): point j
     * stands for the values whose rank falls in ((j − ½)/K, (j + ½)/K], its weight the whole number of them, so the weights add up to n
     * exactly. The representation adds at most 1/(2K) to the rank error.
     */
    public static Kll fromPoints(int k, List<Double> points, long n) {
        Kll s = new Kll(k);
        if (points == null || points.isEmpty() || n <= 0) {
            return s;
        }
        int kk = points.size() - 1;
        if (kk == 0) {
            s.update(points.get(0), n);
            return s;
        }
        long given = 0;
        for (int j = 0; j <= kk; j++) {
            long hi = j == kk ? n : Math.round(n * (j + 0.5) / kk);
            long w = Math.max(0, hi - given);
            given += w;
            Double x = points.get(j);
            if (x != null && w > 0) {
                s.update(x, w);
            }
        }
        return s;
    }

    /** The fractions 0, 1/K, ..., 1 as a SQL list literal (K points + 1). */
    public static String fractions(int kk) {
        List<String> f = new ArrayList<>(kk + 1);
        for (int j = 0; j <= kk; j++) {
            f.add(Double.toString((double) j / kk));
        }
        return "[" + String.join(", ", f) + "]";
    }
}
