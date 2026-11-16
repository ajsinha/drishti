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
import java.util.List;

/**
 * The descriptive statistics of one input (FUNCTION_CATALOGUE.md section 4), every one mergeable:
 * <ul>
 *   <li>{@code stderr}, {@code cv}, {@code skewness}, {@code kurtosis}: the central moments to the fourth order (n, mean, M2, M3, M4),
 *       each batch's computed in two passes, merged by Pébay's formulas (Chan's to the second order). The finished figures are Excel's
 *       sample forms ({@code SKEW}, {@code KURT}: excess kurtosis), which SAS PROC MEANS gives with its default {@code VARDEF=DF}.</li>
 *   <li>{@link NMiss} the values that are missing; {@link Range} max − min.</li>
 *   <li>{@link PositiveMean} {@code geomean} (Σ ln x, n) and {@code harmean} (Σ 1/x, n): positive values only, as Excel's
 *       {@code GEOMEAN} and {@code HARMEAN}; a value ≤ 0 leaves the cell without a figure, said in words.</li>
 * </ul>
 * A null (an absent or unparseable value) is skipped by all but {@code nmiss}, which counts them.
 */
public final class Moments implements AggFunction<Object> {

    /** The names this class answers. */
    public static final List<String> NAMES = List.of("stderr", "cv", "skewness", "kurtosis", "skewness_pop", "devsq");

    private final String name;

    /**
     * {@code name}: stderr, cv, skewness, kurtosis, skewness_pop (the population skewness, Excel's SKEW.P) or devsq (Σ(x − mean)², Excel's
     * DEVSQ, SAS's CSS).
     */
    public Moments(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("no moment statistic " + name);
        }
        this.name = name;
    }

    @Override
    public String name() {
        return name;
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
        return "stderr".equals(name) || "devsq".equals(name) ? null : "";
    }

    /** The state: {n, mean, M2, M3, M4}. */
    @Override
    public Object start() {
        return new double[5];
    }

    @Override
    public Object add(Object s, Batch b) {
        double[] x = b.num(0);
        int n = 0;
        KSum sum = new KSum(0, 0, 0);
        for (int i = 0; i < b.rows(); i++) {
            if (!Double.isNaN(x[i])) {
                sum.add(x[i]);
                n++;
            }
        }
        if (n == 0) {
            return s;
        }
        double mean = sum.value() / n;
        double m2 = 0;
        double m3 = 0;
        double m4 = 0;
        for (int i = 0; i < b.rows(); i++) {
            if (!Double.isNaN(x[i])) {
                double d = x[i] - mean;
                double d2 = d * d;
                m2 += d2;
                m3 += d2 * d;
                m4 += d2 * d2;
            }
        }
        return combine((double[]) s, new double[] {n, mean, m2, m3, m4});
    }

    @Override
    public Object merge(Object a, Object b) {
        return combine((double[]) a, (double[]) b);
    }

    /** Pébay's pairwise update of the central moments to the fourth order (2008), Chan's for the first two. */
    static double[] combine(double[] a, double[] b) {
        if (a[0] == 0) {
            return b.clone();
        }
        if (b[0] == 0) {
            return a.clone();
        }
        double na = a[0];
        double nb = b[0];
        double n = na + nb;
        double d = b[1] - a[1];
        double dn = d / n;
        double mean = a[1] + dn * nb;
        double m2 = a[2] + b[2] + d * dn * na * nb;
        double m3 = a[3] + b[3] + d * dn * dn * na * nb * (na - nb) + 3 * dn * (na * b[2] - nb * a[2]);
        double m4 = a[4] + b[4] + d * dn * dn * dn * na * nb * (na * na - na * nb + nb * nb)
                + 6 * dn * dn * (na * na * b[2] + nb * nb * a[2]) + 4 * dn * (na * b[3] - nb * a[3]);
        return new double[] {n, mean, m2, m3, m4};
    }

    @Override
    public Double finish(Object s) {
        double[] st = (double[]) s;
        double n = st[0];
        double m2 = Math.max(0, st[2]);
        if ("devsq".equals(name)) {
            return n < 1 ? null : m2;
        }
        if ("skewness_pop".equals(name)) {
            return n < 1 || m2 == 0 ? null : Math.sqrt(n) * st[3] / Math.pow(m2, 1.5);
        }
        if (n < 2) {
            return null;
        }
        double sd = Math.sqrt(m2 / (n - 1));
        return switch (name) {
            case "stderr" -> sd / Math.sqrt(n);
            case "cv" -> st[1] == 0 ? null : sd / st[1];
            case "skewness" -> n < 3 || m2 == 0 ? null : n / ((n - 1) * (n - 2)) * st[3] / (sd * sd * sd);
            default -> n < 4 || m2 == 0 ? null
                    : n * (n + 1) / ((n - 1) * (n - 2) * (n - 3)) * st[4] / (sd * sd * sd * sd) - 3 * (n - 1) * (n - 1) / ((n - 2) * (n - 3));
        };
    }

    @Override
    public String emptyNote() {
        return switch (name) {
            case "devsq" -> "no values in the cell";
            case "skewness_pop" -> "no values in the cell, or no spread (Excel's #DIV/0!)";
            case "stderr" -> "fewer than two values in the cell";
            case "cv" -> "fewer than two values in the cell, or a mean of 0";
            case "skewness" -> "fewer than three values in the cell, or no spread (Excel's #DIV/0!)";
            default -> "fewer than four values in the cell, or no spread (Excel's #DIV/0!)";
        };
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

    /** {@code stderr} and {@code cv} need the second moment only, which SQL gives exactly; the third and fourth are computed from rows. */
    @Override
    public List<String> sqlState(List<String> in) {
        if ("skewness".equals(name) || "kurtosis".equals(name) || "skewness_pop".equals(name)) {
            return List.of();
        }
        return List.of("count(" + in.get(0) + ")", "avg(" + in.get(0) + ")", "var_pop(" + in.get(0) + ")");
    }

    @Override
    public Object fromSql(Object[] p) {
        double n = Builtins.lng(p[0]);
        double mean = Builtins.num(p[1]);
        double vp = Builtins.num(p[2]);
        return new double[] {n, Double.isNaN(mean) ? 0 : mean, n == 0 || Double.isNaN(vp) ? 0 : vp * n, 0, 0};
    }

    /** {@code nmiss(x)}: the rows whose x is missing (null, absent); mergeable, a total the sum of its cells. */
    public static final class NMiss implements AggFunction<Object> {
        @Override
        public String name() {
            return "nmiss";
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
        public boolean numeric() {
            return false;
        }

        @Override
        public String resultUnit() {
            return "count";
        }

        @Override
        public Object start() {
            return new long[1];
        }

        @Override
        public Object add(Object s, Batch b) {
            long[] n = (long[]) s;
            Object c = b.columns()[0];
            for (int i = 0; i < b.rows(); i++) {
                if (c instanceof double[] d ? Double.isNaN(d[i]) : ((String[]) c)[i] == null) {
                    n[0]++;
                }
            }
            return n;
        }

        @Override
        public Object merge(Object a, Object b) {
            return new long[] {((long[]) a)[0] + ((long[]) b)[0]};
        }

        @Override
        public Double finish(Object s) {
            return (double) ((long[]) s)[0];
        }

        @Override
        public byte[] encode(Object s) {
            return ByteBuffer.allocate(8).putLong(((long[]) s)[0]).array();
        }

        @Override
        public Object decode(byte[] b) {
            return new long[] {ByteBuffer.wrap(b).getLong()};
        }

        @Override
        public long bytes(Object s) {
            return 24;
        }

        @Override
        public boolean plainSum() {
            return true;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of("count(*) - count(" + in.get(0) + ")");
        }

        @Override
        public Object fromSql(Object[] p) {
            return new long[] {Builtins.lng(p[0])};
        }
    }

    /** {@code range(x)}: max − min, from the two extremes (mergeable); null when the cell has no value. */
    public static final class Range implements AggFunction<Object> {
        @Override
        public String name() {
            return "range";
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
        public Object start() {
            return new double[] {Double.NaN, Double.NaN};
        }

        @Override
        public Object add(Object s, Batch b) {
            double[] v = (double[]) s;
            double[] x = b.num(0);
            for (int i = 0; i < b.rows(); i++) {
                if (!Double.isNaN(x[i])) {
                    v[0] = Double.isNaN(v[0]) ? x[i] : Math.min(v[0], x[i]);
                    v[1] = Double.isNaN(v[1]) ? x[i] : Math.max(v[1], x[i]);
                }
            }
            return v;
        }

        @Override
        public Object merge(Object a, Object b) {
            double[] x = (double[]) a;
            double[] y = (double[]) b;
            return new double[] {Double.isNaN(x[0]) ? y[0] : Double.isNaN(y[0]) ? x[0] : Math.min(x[0], y[0]),
                    Double.isNaN(x[1]) ? y[1] : Double.isNaN(y[1]) ? x[1] : Math.max(x[1], y[1])};
        }

        @Override
        public Double finish(Object s) {
            double[] v = (double[]) s;
            return Double.isNaN(v[0]) ? null : v[1] - v[0];
        }

        @Override
        public byte[] encode(Object s) {
            double[] v = (double[]) s;
            return ByteBuffer.allocate(16).putDouble(v[0]).putDouble(v[1]).array();
        }

        @Override
        public Object decode(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new double[] {bb.getDouble(), bb.getDouble()};
        }

        @Override
        public long bytes(Object s) {
            return 32;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of("min(" + in.get(0) + ")", "max(" + in.get(0) + ")");
        }

        @Override
        public Object fromSql(Object[] p) {
            return new double[] {Builtins.num(p[0]), Builtins.num(p[1])};
        }
    }

    /**
     * {@code geomean(x)} = exp(Σ ln x / n) and {@code harmean(x)} = n / Σ(1/x): (the compensated sum, n, the values ≤ 0). As Excel's, every
     * value must be positive: a cell with a value ≤ 0 has no figure, said in words.
     */
    public static final class PositiveMean implements AggFunction<Object> {
        private final boolean geometric;

        /** {@code name}: geomean or harmean. */
        public PositiveMean(String name) {
            if (!"geomean".equals(name) && !"harmean".equals(name)) {
                throw new IllegalArgumentException("no positive mean " + name);
            }
            this.geometric = "geomean".equals(name);
        }

        @Override
        public String name() {
            return geometric ? "geomean" : "harmean";
        }

        @Override
        public Kind kind() {
            return Kind.MERGEABLE;
        }

        @Override
        public int inputs() {
            return 1;
        }

        /** The state: the sum (of ln x or 1/x) and n in a {@link KSum}, and the values ≤ 0 met. */
        record State(KSum sum, long[] bad) {}

        @Override
        public Object start() {
            return new State(new KSum(0, 0, 0), new long[1]);
        }

        @Override
        public Object add(Object s, Batch b) {
            State st = (State) s;
            double[] x = b.num(0);
            for (int i = 0; i < b.rows(); i++) {
                if (Double.isNaN(x[i])) {
                    continue;
                }
                if (x[i] <= 0) {
                    st.bad[0]++;
                } else {
                    st.sum.add(geometric ? Math.log(x[i]) : 1 / x[i]);
                }
            }
            return st;
        }

        @Override
        public Object merge(Object a, Object b) {
            State x = (State) a;
            State y = (State) b;
            return new State(x.sum.plus(y.sum), new long[] {x.bad[0] + y.bad[0]});
        }

        @Override
        public Double finish(Object s) {
            State st = (State) s;
            if (st.sum.n == 0 || st.bad[0] > 0) {
                return null;
            }
            return geometric ? Math.exp(st.sum.value() / st.sum.n) : st.sum.n / st.sum.value();
        }

        @Override
        public String emptyNote() {
            return "no values in the cell, or a value ≤ 0 (" + name() + " needs positive values: Excel's #NUM!)";
        }

        @Override
        public byte[] encode(Object s) {
            State st = (State) s;
            return ByteBuffer.allocate(32).put(st.sum.encode()).putLong(st.bad[0]).array();
        }

        @Override
        public Object decode(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            KSum k = new KSum(bb.getDouble(), bb.getDouble(), bb.getLong());
            return new State(k, new long[] {bb.getLong()});
        }

        @Override
        public long bytes(Object s) {
            return 72;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            String x = in.get(0);
            return List.of("fsum(CASE WHEN " + x + " > 0 THEN " + (geometric ? "ln(" + x + ")" : "1.0 / " + x) + " END)",
                    "count(CASE WHEN " + x + " > 0 THEN 1 END)", "count(CASE WHEN " + x + " <= 0 THEN 1 END)");
        }

        @Override
        public Object fromSql(Object[] p) {
            double s = Builtins.num(p[0]);
            return new State(new KSum(Double.isNaN(s) ? 0 : s, 0, Builtins.lng(p[1])), new long[] {Builtins.lng(p[2])});
        }
    }
}
