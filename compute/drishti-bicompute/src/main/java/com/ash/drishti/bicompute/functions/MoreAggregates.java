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

import com.ash.drishti.bicompute.functions.Builtins.KSum;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The aggregates that complete Excel's and SAS's everyday set (FUNCTION_CATALOGUE.md section 4, "Excel and SAS coverage"):
 * <ul>
 *   <li>mergeable: {@link SumProduct} (Excel's SUMPRODUCT of two ranges), {@link SumSq} (SUMSQ, SAS's USS), {@link Product} (PRODUCT,
 *       kept as a mantissa and a binary exponent so a long product neither overflows nor underflows on the way);</li>
 *   <li>holistic, from the cell's own values: {@link AveDev} (AVEDEV), {@link PercentileExc} (PERCENTILE.EXC; QUARTILE.EXC is it at
 *       0.25, 0.5, 0.75), {@link RankAvg} (RANK.AVG of a value among the cell's), {@link PercentRankOf} (PERCENTRANK.INC of a value) and
 *       {@link ModeMulti} (MODE.MULT: every most frequent value, bounded).</li>
 * </ul>
 * A null is skipped by all of them (a pair with a null by {@code sumproduct}, as Excel's SUMPRODUCT reads an empty cell as nothing to add).
 */
public final class MoreAggregates {

    private MoreAggregates() {}

    /** Base of the compensated sums: the state a {@link KSum}, merged exactly, SQL's {@code sum(...)} its parts. */
    abstract static class SumOf implements AggFunction<Object> {
        @Override
        public Kind kind() {
            return Kind.MERGEABLE;
        }

        @Override
        public Object start() {
            return new KSum(0, 0, 0);
        }

        @Override
        public Object merge(Object a, Object b) {
            return ((KSum) a).plus((KSum) b);
        }

        @Override
        public Double finish(Object s) {
            return ((KSum) s).value();
        }

        @Override
        public boolean plainSum() {
            return true;
        }

        @Override
        public byte[] encode(Object s) {
            return ((KSum) s).encode();
        }

        @Override
        public Object decode(byte[] b) {
            return KSum.decode(b);
        }

        @Override
        public long bytes(Object s) {
            return 40;
        }

        @Override
        public Object fromSql(Object[] p) {
            double v = Builtins.num(p[0]);
            return new KSum(Double.isNaN(v) ? 0 : v, 0, Builtins.lng(p[1]));
        }
    }

    /** {@code sumproduct(x, y)}: Σ x·y over the rows where both are present (compensated); 0 for none, as Excel's. */
    public static final class SumProduct extends SumOf {
        @Override
        public String name() {
            return "sumproduct";
        }

        @Override
        public int inputs() {
            return 2;
        }

        @Override
        public String resultUnit() {
            return "";
        }

        @Override
        public Object add(Object s, Batch b) {
            KSum k = (KSum) s;
            double[] x = b.num(0);
            double[] y = b.num(1);
            for (int i = 0; i < b.rows(); i++) {
                if (!Double.isNaN(x[i]) && !Double.isNaN(y[i])) {
                    k.add(x[i] * y[i]);
                }
            }
            return k;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of("sum((" + in.get(0) + ") * (" + in.get(1) + "))", "count((" + in.get(0) + ") * (" + in.get(1) + "))");
        }
    }

    /** {@code sumsq(x)}: Σ x² (compensated); 0 for none, as Excel's SUMSQ; SAS's USS. */
    public static final class SumSq extends SumOf {
        @Override
        public String name() {
            return "sumsq";
        }

        @Override
        public int inputs() {
            return 1;
        }

        @Override
        public String resultUnit() {
            return "";
        }

        @Override
        public Object add(Object s, Batch b) {
            KSum k = (KSum) s;
            double[] x = b.num(0);
            for (int i = 0; i < b.rows(); i++) {
                if (!Double.isNaN(x[i])) {
                    k.add(x[i] * x[i]);
                }
            }
            return k;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of("sum((" + in.get(0) + ") * (" + in.get(0) + "))", "count(" + in.get(0) + ")");
        }
    }

    /**
     * {@code product(x)}: Π x, kept as {mantissa in [1, 2), binary exponent, count, zeros, negatives}, so the running product never
     * overflows or underflows; the merge multiplies mantissas and adds exponents. Empty for no values; past a double's range, no figure,
     * said in words.
     */
    public static final class Product implements AggFunction<Object> {
        @Override
        public String name() {
            return "product";
        }

        @Override
        public Kind kind() {
            return Kind.MERGEABLE;
        }

        @Override
        public int inputs() {
            return 1;
        }

        @Override
        public String resultUnit() {
            return "";
        }

        /** {mantissa, exponent, n, zeros, negatives}. */
        @Override
        public Object start() {
            return new double[] {1, 0, 0, 0, 0};
        }

        private static void normalise(double[] st) {
            int e = Math.getExponent(st[0]);
            st[0] = Math.scalb(st[0], -e);
            st[1] += e;
        }

        @Override
        public Object add(Object s, Batch b) {
            double[] st = (double[]) s;
            double[] x = b.num(0);
            for (int i = 0; i < b.rows(); i++) {
                double v = x[i];
                if (Double.isNaN(v)) {
                    continue;
                }
                st[2]++;
                if (v == 0) {
                    st[3]++;
                    continue;
                }
                if (v < 0) {
                    st[4]++;
                }
                double av = Math.abs(v);
                int e = Math.getExponent(av);
                if (e < Double.MIN_EXPONENT) {                    // a subnormal: scale it up into the normal range first
                    av = Math.scalb(av, 64);
                    st[1] -= 64;
                    e = Math.getExponent(av);
                }
                st[0] *= Math.scalb(av, -e);
                st[1] += e;
                normalise(st);
            }
            return st;
        }

        @Override
        public Object merge(Object a, Object b) {
            double[] x = (double[]) a;
            double[] y = (double[]) b;
            double[] out = {x[0] * y[0], x[1] + y[1], x[2] + y[2], x[3] + y[3], x[4] + y[4]};
            normalise(out);
            return out;
        }

        @Override
        public Double finish(Object s) {
            double[] st = (double[]) s;
            if (st[2] == 0) {
                return null;
            }
            if (st[3] > 0) {
                return 0.0;
            }
            if (st[1] > Double.MAX_EXPONENT + 1 || st[1] < Double.MIN_EXPONENT - 60) {
                return null;
            }
            double v = Math.scalb(st[0], (int) st[1]);
            if (Double.isInfinite(v)) {
                return null;
            }
            return ((long) st[4]) % 2 == 1 ? -v : v;
        }

        @Override
        public String emptyNote() {
            return "no values in the cell, or a product beyond a number's range";
        }

        @Override
        public byte[] encode(Object s) {
            ByteBuffer bb = ByteBuffer.allocate(40);
            for (double d : (double[]) s) {
                bb.putDouble(d);
            }
            return bb.array();
        }

        @Override
        public Object decode(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            double[] st = new double[5];
            for (int i = 0; i < 5; i++) {
                st[i] = bb.getDouble();
            }
            return st;
        }

        @Override
        public long bytes(Object s) {
            return 56;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of();
        }

        @Override
        public Object fromSql(Object[] p) {
            throw new UnsupportedOperationException("product is computed from the cell's rows");
        }
    }

    /** {@code avedev(x)}: the mean absolute deviation from the mean, Excel's AVEDEV. */
    public static final class AveDev extends Holistics.OfValues {
        @Override
        public String name() {
            return "avedev";
        }

        @Override
        Double of(double[] x) {
            KSum s = new KSum(0, 0, 0);
            for (double v : x) {
                s.add(v);
            }
            double mean = s.value() / x.length;
            KSum d = new KSum(0, 0, 0);
            for (double v : x) {
                d.add(Math.abs(v - mean));
            }
            return d.value() / x.length;
        }
    }

    /**
     * {@code percentile_exc(x, p)}: Excel's PERCENTILE.EXC, at rank p·(n + 1) interpolated; none when that rank is below 1 or above n
     * (Excel's #NUM!). QUARTILE.EXC(x, k) is {@code percentile_exc(x, k / 4)}.
     */
    public static final class PercentileExc extends Holistics.OfValues {
        private final double p;

        public PercentileExc(double p) {
            if (!(p > 0 && p < 1)) {
                throw new IllegalArgumentException("percentile_exc takes p between 0 and 1, exclusive (0.25), not " + p);
            }
            this.p = p;
        }

        @Override
        public String name() {
            return "percentile_exc";
        }

        @Override
        Double of(double[] x) {
            double h = p * (x.length + 1);
            if (h < 1 || h > x.length) {
                return null;
            }
            int lo = (int) Math.floor(h);
            return lo >= x.length ? x[x.length - 1] : x[lo - 1] + (h - lo) * (x[lo] - x[lo - 1]);
        }

        @Override
        public String emptyNote() {
            return "too few values in the cell for p = " + p + " (rank p·(n + 1) outside 1..n: Excel's #NUM!)";
        }
    }

    /**
     * {@code rank_avg(x, value, [ascending])}: the rank of a value among the cell's values, ties at their average rank, Excel's RANK.AVG:
     * descending (the largest 1) unless ascending is 1; none when the value is not among them (Excel's #N/A).
     */
    public static final class RankAvg extends Holistics.OfValues {
        private final double value;
        private final boolean ascending;

        public RankAvg(double value, double ascending) {
            if (Double.isNaN(value)) {
                throw new IllegalArgumentException("rank_avg takes the value to rank (rank_avg(PNL, 1000))");
            }
            this.value = value;
            this.ascending = ascending != 0 && !Double.isNaN(ascending);
        }

        @Override
        public String name() {
            return "rank_avg";
        }

        @Override
        public String resultUnit() {
            return "";
        }

        @Override
        Double of(double[] x) {
            int below = 0;
            int equal = 0;
            for (double v : x) {
                if (v < value) {
                    below++;
                } else if (v == value) {
                    equal++;
                }
            }
            if (equal == 0) {
                return null;
            }
            int before = ascending ? below : x.length - below - equal;
            return before + (equal + 1) / 2.0;
        }

        @Override
        public String emptyNote() {
            return "the value is not among the cell's values (Excel's #N/A)";
        }
    }

    /**
     * {@code percent_rank_of(x, value, [digits])}: the value's rank among the cell's as a fraction from 0 to 1, Excel's PERCENTRANK.INC:
     * (values below) / (n − 1), interpolated between neighbours for a value not among them, cut down to {@code digits} significant digits
     * (3 by default, as Excel's); none outside the values' range. ({@code percent_rank} ranks a pivot cell among its siblings.)
     */
    public static final class PercentRankOf extends Holistics.OfValues {
        private final double value;
        private final int digits;

        public PercentRankOf(double value, double digits) {
            if (Double.isNaN(value)) {
                throw new IllegalArgumentException("percent_rank_of takes the value to rank (percent_rank_of(PNL, 1000))");
            }
            double d = Double.isNaN(digits) ? 3 : digits;
            if (!(d >= 1 && d <= 15 && d == Math.rint(d))) {
                throw new IllegalArgumentException("percent_rank_of keeps 1 to 15 significant digits, not " + digits);
            }
            this.value = value;
            this.digits = (int) d;
        }

        @Override
        public String name() {
            return "percent_rank_of";
        }

        @Override
        public String resultUnit() {
            return "";
        }

        private static int below(double[] x, double v) {
            int n = 0;
            while (n < x.length && x[n] < v) {
                n++;
            }
            return n;
        }

        @Override
        Double of(double[] x) {
            if (x.length < 2 || value < x[0] || value > x[x.length - 1]) {
                return x.length == 1 && value == x[0] ? 1.0 : null;
            }
            int b = below(x, value);
            double r;
            if (x[b] == value) {
                r = (double) b / (x.length - 1);
            } else {
                double lo = x[b - 1];
                double hi = x[b];
                double rLo = (double) below(x, lo) / (x.length - 1);
                double rHi = (double) b / (x.length - 1);
                r = rLo + (value - lo) / (hi - lo) * (rHi - rLo);
            }
            double scale = Math.pow(10, digits);
            return Math.floor(r * scale + 1e-9) / scale;
        }

        @Override
        public String emptyNote() {
            return "the value is outside the cell's values (Excel's #N/A)";
        }
    }

    /** The most distinct values {@code mode_multi} keeps; past it the cell has no figure, said in words. */
    public static final int MODE_MULTI_MAX = 10_000;

    /**
     * {@code mode_multi(x)}: every value that occurs most often (at least twice), ascending, Excel's MODE.MULT; numbers when every value is
     * one, else names. Written in a cell as the values joined by ", "; a Rachana list gets them as a list ({@link #values}). Bounded at
     * {@value #MODE_MULTI_MAX} distinct values.
     */
    public static final class ModeMulti extends Holistics.OfTexts {
        @Override
        public String name() {
            return "mode_multi";
        }

        @Override
        int cap() {
            return MODE_MULTI_MAX + 1;
        }

        @Override
        public String resultUnit() {
            return "";
        }

        @Override
        public Double finish(Object s) {
            List<Object> v = values(s);
            return v != null && v.size() == 1 && v.get(0) instanceof Double d ? d : null;
        }

        /** The most frequent values, ascending (numbers as numbers); null when none repeats or past the bound. */
        @SuppressWarnings("unchecked")
        public List<Object> values(Object s) {
            TreeMap<String, Long> m = (TreeMap<String, Long>) s;
            if (m.isEmpty() || m.size() > MODE_MULTI_MAX) {
                return null;
            }
            TreeMap<Double, Long> nums = new TreeMap<>();
            for (Map.Entry<String, Long> e : m.entrySet()) {
                Double d = Holistics.number(e.getKey());
                if (d == null) {
                    nums = null;
                    break;
                }
                nums.merge(d == 0 ? 0.0 : d, e.getValue(), Long::sum);
            }
            Map<?, Long> counts = nums != null ? nums : m;
            long most = 0;
            for (Long c : counts.values()) {
                most = Math.max(most, c);
            }
            if (most < 2) {
                return null;
            }
            List<Object> out = new ArrayList<>();
            for (Map.Entry<?, Long> e : counts.entrySet()) {
                if (e.getValue() == most) {
                    out.add(e.getKey());
                }
            }
            return out;
        }

        @Override
        public Object result(Object s) {
            List<Object> v = values(s);
            if (v == null) {
                return null;
            }
            StringBuilder b = new StringBuilder();
            for (Object o : v) {
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append(o instanceof Double d ? Holistics.plain(Double.toString(d)) : o);
            }
            return b.toString();
        }

        @Override
        public String emptyNote() {
            return "no values in the cell, no value repeats (Excel's #N/A), or more than " + MODE_MULTI_MAX + " different values";
        }
    }
}
