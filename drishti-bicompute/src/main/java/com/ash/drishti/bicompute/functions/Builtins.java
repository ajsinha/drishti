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

import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.bicompute.spi.Kind;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The built-in functions (PIVOT_ENGINE.md section 5.2), each with its state, its exact merge and its SQL state form. Mergeable: {@link Sum}
 * (compensated), {@link Count}, {@link MinMax}, {@link Avg} (sum and count), {@link WeightedAvg} (Σwx and Σw), {@link Welford} (count,
 * mean and M2, merged by Chan's formula: variance and standard deviation, sample and population). Holistic and exact: {@link Quantile}
 * (median, percentile; the values, linear interpolation at q·(n−1) as DuckDB's {@code quantile_cont}) and {@link Distinct} (an exact
 * distinct count). The sketches (HyperLogLog, KLL, Space-Saving) are {@link Sketches}.
 */
public final class Builtins {

    private Builtins() {}

    /** A compensated sum (Neumaier) and the values counted. */
    public static final class KSum {
        double sum;
        double c;
        long n;

        KSum(double sum, double c, long n) {
            this.sum = sum;
            this.c = c;
            this.n = n;
        }

        void add(double x) {
            double t = sum + x;
            c += Math.abs(sum) >= Math.abs(x) ? (sum - t) + x : (x - t) + sum;
            sum = t;
            n++;
        }

        KSum plus(KSum o) {
            KSum k = new KSum(sum, c + o.c, n);
            k.add(o.sum);
            k.n = n + o.n;
            return k;
        }

        double value() {
            return sum + c;
        }

        byte[] encode() {
            return ByteBuffer.allocate(24).putDouble(sum).putDouble(c).putLong(n).array();
        }

        static KSum decode(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new KSum(bb.getDouble(), bb.getDouble(), bb.getLong());
        }
    }

    static double num(Object o) {
        return o instanceof Number x ? x.doubleValue() : Double.NaN;
    }

    static long lng(Object o) {
        return o instanceof Number x ? x.longValue() : 0L;
    }

    /** Base of the functions over one numeric input whose state is a {@link KSum}. */
    abstract static class SumLike implements AggFunction<Object> {
        @Override
        public int inputs() {
            return 1;
        }

        @Override
        public Object start() {
            return new KSum(0, 0, 0);
        }

        @Override
        public Object add(Object s, Batch b) {
            KSum k = (KSum) s;
            double[] x = b.num(0);
            for (int i = 0; i < b.rows(); i++) {
                if (!Double.isNaN(x[i])) {
                    k.add(x[i]);
                }
            }
            return k;
        }

        @Override
        public Object merge(Object a, Object b) {
            return ((KSum) a).plus((KSum) b);
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
        public List<String> sqlState(List<String> in) {
            return List.of("fsum(" + in.get(0) + ")", "count(" + in.get(0) + ")");
        }

        @Override
        public Object fromSql(Object[] p) {
            double s = num(p[0]);
            return new KSum(Double.isNaN(s) ? 0 : s, 0, lng(p[1]));
        }

        @Override
        public Kind kind() {
            return Kind.MERGEABLE;
        }
    }

    /** {@code sum(x)}: Σx, compensated; null when the cell has no value of x. */
    public static final class Sum extends SumLike {
        @Override
        public String name() {
            return "sum";
        }

        @Override
        public Double finish(Object s) {
            KSum k = (KSum) s;
            return k.n == 0 ? null : k.value();
        }

        @Override
        public boolean plainSum() {
            return true;
        }
    }

    /** {@code avg(x)}: (Σx, n), finished Σx / n: right at every level (never an average of averages). */
    public static final class Avg extends SumLike {
        @Override
        public String name() {
            return "avg";
        }

        @Override
        public Double finish(Object s) {
            KSum k = (KSum) s;
            return k.n == 0 ? null : k.value() / k.n;
        }
    }

    /** {@code count()} (rows) and {@code count(x)} (values of x). */
    public static final class Count implements AggFunction<Object> {
        private final boolean rows;

        public Count(boolean rows) {
            this.rows = rows;
        }

        @Override
        public String name() {
            return "count";
        }

        @Override
        public Kind kind() {
            return Kind.MERGEABLE;
        }

        @Override
        public int inputs() {
            return rows ? 0 : 1;
        }

        @Override
        public boolean numeric() {
            return false;
        }

        @Override
        public Object start() {
            return new long[1];
        }

        @Override
        public Object add(Object s, Batch b) {
            long[] n = (long[]) s;
            if (rows) {
                n[0] += b.rows();
            } else {
                Object c = b.columns()[0];
                for (int i = 0; i < b.rows(); i++) {
                    if (c instanceof double[] d ? !Double.isNaN(d[i]) : ((String[]) c)[i] != null) {
                        n[0]++;
                    }
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
            return List.of(rows ? "count(*)" : "count(" + in.get(0) + ")");
        }

        @Override
        public Object fromSql(Object[] p) {
            return new long[] {lng(p[0])};
        }
    }

    /** {@code min(x)}, {@code max(x)}: the extreme, null when none. */
    public static final class MinMax implements AggFunction<Object> {
        private final boolean max;

        public MinMax(boolean max) {
            this.max = max;
        }

        @Override
        public String name() {
            return max ? "max" : "min";
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
            return new double[] {Double.NaN};
        }

        private double pick(double a, double b) {
            if (Double.isNaN(a)) {
                return b;
            }
            if (Double.isNaN(b)) {
                return a;
            }
            return max ? Math.max(a, b) : Math.min(a, b);
        }

        @Override
        public Object add(Object s, Batch b) {
            double[] v = (double[]) s;
            double[] x = b.num(0);
            for (int i = 0; i < b.rows(); i++) {
                v[0] = pick(v[0], x[i]);
            }
            return v;
        }

        @Override
        public Object merge(Object a, Object b) {
            return new double[] {pick(((double[]) a)[0], ((double[]) b)[0])};
        }

        @Override
        public Double finish(Object s) {
            double v = ((double[]) s)[0];
            return Double.isNaN(v) ? null : v;
        }

        @Override
        public byte[] encode(Object s) {
            return ByteBuffer.allocate(8).putDouble(((double[]) s)[0]).array();
        }

        @Override
        public Object decode(byte[] b) {
            return new double[] {ByteBuffer.wrap(b).getDouble()};
        }

        @Override
        public long bytes(Object s) {
            return 24;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of(name() + "(" + in.get(0) + ")");
        }

        @Override
        public Object fromSql(Object[] p) {
            return new double[] {num(p[0])};
        }
    }

    /** {@code wavg(x, w)}: (Σwx, Σw) over the rows where both are present, finished Σwx / Σw. */
    public static final class WeightedAvg implements AggFunction<Object> {
        @Override
        public String name() {
            return "wavg";
        }

        @Override
        public Kind kind() {
            return Kind.MERGEABLE;
        }

        @Override
        public int inputs() {
            return 2;
        }

        @Override
        public Object start() {
            return new KSum[] {new KSum(0, 0, 0), new KSum(0, 0, 0)};
        }

        @Override
        public Object add(Object s, Batch b) {
            KSum[] k = (KSum[]) s;
            double[] x = b.num(0);
            double[] w = b.num(1);
            for (int i = 0; i < b.rows(); i++) {
                if (!Double.isNaN(x[i]) && !Double.isNaN(w[i])) {
                    k[0].add(x[i] * w[i]);
                    k[1].add(w[i]);
                }
            }
            return k;
        }

        @Override
        public Object merge(Object a, Object b) {
            KSum[] x = (KSum[]) a;
            KSum[] y = (KSum[]) b;
            return new KSum[] {x[0].plus(y[0]), x[1].plus(y[1])};
        }

        @Override
        public Double finish(Object s) {
            KSum[] k = (KSum[]) s;
            double w = k[1].value();
            return k[1].n == 0 || w == 0 ? null : k[0].value() / w;
        }

        @Override
        public String emptyNote() {
            return "no weight in the cell";
        }

        @Override
        public byte[] encode(Object s) {
            KSum[] k = (KSum[]) s;
            return ByteBuffer.allocate(48).put(k[0].encode()).put(k[1].encode()).array();
        }

        @Override
        public Object decode(byte[] b) {
            return new KSum[] {KSum.decode(Arrays.copyOfRange(b, 0, 24)), KSum.decode(Arrays.copyOfRange(b, 24, 48))};
        }

        @Override
        public long bytes(Object s) {
            return 96;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            String both = "(" + in.get(0) + ") IS NOT NULL AND (" + in.get(1) + ") IS NOT NULL";
            return List.of("fsum(CASE WHEN " + both + " THEN (" + in.get(0) + ") * (" + in.get(1) + ") END)",
                    "fsum(CASE WHEN " + both + " THEN " + in.get(1) + " END)", "count(CASE WHEN " + both + " THEN 1 END)");
        }

        @Override
        public Object fromSql(Object[] p) {
            long n = lng(p[2]);
            double a = num(p[0]);
            double w = num(p[1]);
            return new KSum[] {new KSum(Double.isNaN(a) ? 0 : a, 0, n), new KSum(Double.isNaN(w) ? 0 : w, 0, n)};
        }
    }

    /** Variance and standard deviation: Welford's (n, mean, M2), merged by Chan's formula. */
    public static final class Welford implements AggFunction<Object> {
        private final String name;
        private final boolean sample;
        private final boolean root;

        /** {@code name}: var_samp, var_pop, stddev_samp, stddev_pop. */
        public Welford(String name) {
            this.name = name;
            this.sample = name.endsWith("samp");
            this.root = name.startsWith("stddev");
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
        public Object start() {
            return new double[3];
        }

        @Override
        public Object add(Object s, Batch b) {
            double[] st = (double[]) s;
            double[] x = b.num(0);
            for (int i = 0; i < b.rows(); i++) {
                if (!Double.isNaN(x[i])) {
                    st[0]++;
                    double d = x[i] - st[1];
                    st[1] += d / st[0];
                    st[2] += d * (x[i] - st[1]);
                }
            }
            return st;
        }

        @Override
        public Object merge(Object a, Object b) {
            double[] x = (double[]) a;
            double[] y = (double[]) b;
            if (x[0] == 0) {
                return y.clone();
            }
            if (y[0] == 0) {
                return x.clone();
            }
            double n = x[0] + y[0];
            double d = y[1] - x[1];
            return new double[] {n, x[1] + d * y[0] / n, x[2] + y[2] + d * d * x[0] * y[0] / n};
        }

        @Override
        public Double finish(Object s) {
            double[] st = (double[]) s;
            double n = st[0];
            if (n == 0 || sample && n < 2) {
                return null;
            }
            double v = Math.max(0, st[2]) / (sample ? n - 1 : n);
            return root ? Math.sqrt(v) : v;
        }

        @Override
        public String emptyNote() {
            return sample ? "fewer than two values in the cell" : "no values in the cell";
        }

        @Override
        public byte[] encode(Object s) {
            double[] st = (double[]) s;
            return ByteBuffer.allocate(24).putDouble(st[0]).putDouble(st[1]).putDouble(st[2]).array();
        }

        @Override
        public Object decode(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new double[] {bb.getDouble(), bb.getDouble(), bb.getDouble()};
        }

        @Override
        public long bytes(Object s) {
            return 40;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of("count(" + in.get(0) + ")", "avg(" + in.get(0) + ")", "var_pop(" + in.get(0) + ")");
        }

        @Override
        public Object fromSql(Object[] p) {
            double n = lng(p[0]);
            double mean = num(p[1]);
            double vp = num(p[2]);
            return new double[] {n, Double.isNaN(mean) ? 0 : mean, n == 0 || Double.isNaN(vp) ? 0 : vp * n};
        }
    }

    /** A growable list of doubles: the values of a holistic cell. */
    public static final class Values {
        double[] v = new double[16];
        int n;

        void add(double x) {
            if (n == v.length) {
                v = Arrays.copyOf(v, n * 2);
            }
            v[n++] = x;
        }

        Values plus(Values o) {
            Values out = new Values();
            out.v = Arrays.copyOf(v, Math.max(16, n + o.n));
            System.arraycopy(o.v, 0, out.v, n, o.n);
            out.n = n + o.n;
            return out;
        }
    }

    /** {@code median(x)}, {@code percentile(x, q)}: exact, from the cell's own values (holistic). */
    public static final class Quantile implements AggFunction<Object> {
        private final double q;
        private final String name;

        public Quantile(String name, double q) {
            if (!(q >= 0 && q <= 1)) {
                throw new IllegalArgumentException("a percentile is between 0 and 1 (0.99), not " + q);
            }
            this.name = name;
            this.q = q;
        }

        /** The quantile asked (0.5 for the median): its sketch form answers the same q. */
        public double q() {
            return q;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Kind kind() {
            return Kind.HOLISTIC;
        }

        @Override
        public int inputs() {
            return 1;
        }

        @Override
        public Object start() {
            return new Values();
        }

        @Override
        public Object add(Object s, Batch b) {
            Values v = (Values) s;
            double[] x = b.num(0);
            for (int i = 0; i < b.rows(); i++) {
                if (!Double.isNaN(x[i])) {
                    v.add(x[i]);
                }
            }
            return v;
        }

        @Override
        public Object merge(Object a, Object b) {
            if (a instanceof Final || b instanceof Final) {
                throw new IllegalStateException(name + " is holistic: a figure computed by the engine is never merged");
            }
            return ((Values) a).plus((Values) b);
        }

        @Override
        public Double finish(Object s) {
            if (s instanceof Final f) {
                return f.value();
            }
            Values v = (Values) s;
            if (v.n == 0) {
                return null;
            }
            double[] x = Arrays.copyOf(v.v, v.n);
            Arrays.sort(x);
            double pos = q * (x.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = (int) Math.ceil(pos);
            return lo == hi ? x[lo] : x[lo] + (pos - lo) * (x[hi] - x[lo]);
        }

        @Override
        public byte[] encode(Object s) {
            if (s instanceof Final f) {
                return f.encode();
            }
            Values v = (Values) s;
            ByteBuffer bb = ByteBuffer.allocate(5 + 8 * v.n).put((byte) 0).putInt(v.n);
            for (int i = 0; i < v.n; i++) {
                bb.putDouble(v.v[i]);
            }
            return bb.array();
        }

        @Override
        public Object decode(byte[] b) {
            if (b[0] == Final.TAG) {
                return Final.decode(b);
            }
            ByteBuffer bb = ByteBuffer.wrap(b, 1, b.length - 1);
            Values v = new Values();
            int n = bb.getInt();
            for (int i = 0; i < n; i++) {
                v.add(bb.getDouble());
            }
            return v;
        }

        @Override
        public long bytes(Object s) {
            return s instanceof Values v ? 32 + 8L * v.v.length : 32;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of("quantile_cont(" + in.get(0) + ", " + q + ")");
        }

        @Override
        public Object fromSql(Object[] p) {
            double v = num(p[0]);
            return new Final(Double.isNaN(v) ? null : v);
        }
    }

    /** {@code count_distinct(x)}: exact, from the cell's own values (holistic; the ≈ sketch comes with phase P3). */
    public static final class Distinct implements AggFunction<Object> {
        @Override
        public String name() {
            return "count_distinct";
        }

        @Override
        public Kind kind() {
            return Kind.HOLISTIC;
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
        public Object start() {
            return new HashSet<String>();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object add(Object s, Batch b) {
            Set<String> set = (Set<String>) s;
            String[] x = b.text(0);
            for (int i = 0; i < b.rows(); i++) {
                if (x[i] != null) {
                    set.add(x[i]);
                }
            }
            return set;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object merge(Object a, Object b) {
            if (a instanceof Final || b instanceof Final) {
                throw new IllegalStateException("count_distinct is holistic: a figure computed by the engine is never merged");
            }
            Set<String> out = new HashSet<>((Set<String>) a);
            out.addAll((Set<String>) b);
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Double finish(Object s) {
            return s instanceof Final f ? f.value() : (double) ((Set<String>) s).size();
        }

        @Override
        @SuppressWarnings("unchecked")
        public byte[] encode(Object s) {
            if (s instanceof Final f) {
                return f.encode();
            }
            StringBuilder b = new StringBuilder("\u0001");
            for (String x : new java.util.TreeSet<>((Set<String>) s)) {
                b.append(x.replace("\u0000", "")).append('\u0000');
            }
            return b.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Object decode(byte[] b) {
            if (b[0] == Final.TAG) {
                return Final.decode(b);
            }
            Set<String> out = new HashSet<>();
            String s = new String(b, 1, b.length - 1, StandardCharsets.UTF_8);
            for (String x : s.split("\u0000")) {
                if (!x.isEmpty()) {
                    out.add(x);
                }
            }
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public long bytes(Object s) {
            return s instanceof Set<?> set ? 48 + 48L * set.size() : 32;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of("count(DISTINCT " + in.get(0) + ")");
        }

        @Override
        public Object fromSql(Object[] p) {
            return new Final(num(p[0]));
        }
    }
}
