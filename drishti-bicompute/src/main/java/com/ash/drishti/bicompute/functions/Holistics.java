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

import com.ash.drishti.bicompute.functions.Builtins.Values;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The holistic statistics of RACHANA_REFERENCE.md section F4 (each cell and total from its own values, never from other cells' figures):
 * {@link Iqr} (p75 − p25), {@link TrimMean} (Excel's {@code TRIMMEAN}), {@link Kth} ({@code kth_largest}, {@code kth_smallest}: Excel's
 * {@code LARGE}, {@code SMALL}), {@link Mode} (the most frequent value, a number or a name) and {@link StringAgg} (the values joined,
 * bounded). The numeric ones keep the cell's values ({@link Values}); the text ones a count per distinct value, whose merge is the exact
 * union (so a cluster's parts merge to the one-pass answer).
 */
public final class Holistics {

    private Holistics() {}

    /** The q-quantile of sorted values by linear interpolation at q·(n−1): Excel's {@code PERCENTILE.INC}, DuckDB's {@code quantile_cont}. */
    static double quantile(double[] sorted, double q) {
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return lo == hi ? sorted[lo] : sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    /** Base of the numeric holistic functions over one input: the state is the cell's values (or a figure SQL finished, {@link Final}). */
    abstract static class OfValues implements AggFunction<Object> {
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
                throw new IllegalStateException(name() + " is holistic: a figure computed by the engine is never merged");
            }
            return ((Values) a).plus((Values) b);
        }

        /** The figure from the cell's values, sorted ascending (at least one). */
        abstract Double of(double[] sorted);

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
            return of(x);
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
            return List.of();
        }

        @Override
        public Object fromSql(Object[] p) {
            double v = Builtins.num(p[0]);
            return new Final(Double.isNaN(v) ? null : v);
        }
    }

    /** {@code iqr(x)}: p75 − p25 by Excel's {@code QUARTILE.INC} (linear interpolation); SQL finishes it per grouping set. */
    public static final class Iqr extends OfValues {
        @Override
        public String name() {
            return "iqr";
        }

        @Override
        Double of(double[] x) {
            return quantile(x, 0.75) - quantile(x, 0.25);
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of("quantile_cont(" + in.get(0) + ", 0.75) - quantile_cont(" + in.get(0) + ", 0.25)");
        }
    }

    /**
     * {@code trimmean(x, pct)}: the mean without the tails, as Excel's {@code TRIMMEAN}: ⌊n·pct/2⌋ values are left out at each end (the
     * count left out rounded down to an even number).
     */
    public static final class TrimMean extends OfValues {
        private final double pct;

        public TrimMean(double pct) {
            if (!(pct >= 0 && pct < 1)) {
                throw new IllegalArgumentException("trimmean leaves out a fraction from 0 to under 1 of the values (0.1), not " + pct);
            }
            this.pct = pct;
        }

        @Override
        public String name() {
            return "trimmean";
        }

        @Override
        Double of(double[] x) {
            int k = (int) Math.floor(x.length * pct / 2);
            Builtins.KSum s = new Builtins.KSum(0, 0, 0);
            for (int i = k; i < x.length - k; i++) {
                s.add(x[i]);
            }
            return s.value() / (x.length - 2 * k);
        }
    }

    /** {@code kth_largest(x, k)}, {@code kth_smallest(x, k)}: Excel's {@code LARGE} and {@code SMALL}; ties count each time. */
    public static final class Kth extends OfValues {
        private final boolean largest;
        private final int k;

        public Kth(String name, double k) {
            if (!(k >= 1 && k == Math.rint(k) && k <= Integer.MAX_VALUE)) {
                throw new IllegalArgumentException(name + " takes a whole k of 1 or more (kth_largest(NOTIONAL, 3)), not " + k);
            }
            this.largest = "kth_largest".equals(name);
            this.k = (int) k;
        }

        @Override
        public String name() {
            return largest ? "kth_largest" : "kth_smallest";
        }

        @Override
        Double of(double[] x) {
            if (k > x.length) {
                return null;
            }
            return largest ? x[x.length - k] : x[k - 1];
        }

        @Override
        public String emptyNote() {
            return "fewer than " + k + " values in the cell (Excel's #NUM!)";
        }
    }

    /** Base of the text holistic functions: a count per distinct text value (sorted), merged exactly by adding the counts. */
    abstract static class OfTexts implements AggFunction<Object> {
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
            return new TreeMap<String, Long>();
        }

        /** The most distinct values the state keeps; past it the answer is known to be refused, so no more are kept (bounded state). */
        int cap() {
            return Integer.MAX_VALUE;
        }

        private void put(TreeMap<String, Long> m, String k, long n) {
            if (m.size() < cap() || m.containsKey(k)) {
                m.merge(k, n, Long::sum);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object add(Object s, Batch b) {
            TreeMap<String, Long> m = (TreeMap<String, Long>) s;
            String[] x = b.text(0);
            for (int i = 0; i < b.rows(); i++) {
                if (x[i] != null) {
                    put(m, x[i], 1L);
                }
            }
            return m;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object merge(Object a, Object b) {
            TreeMap<String, Long> out = new TreeMap<>((TreeMap<String, Long>) a);
            ((TreeMap<String, Long>) b).forEach((k, v) -> put(out, k, v));
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        public byte[] encode(Object s) {
            TreeMap<String, Long> m = (TreeMap<String, Long>) s;
            List<byte[]> keys = new ArrayList<>();
            int size = 4;
            for (String k : m.keySet()) {
                byte[] kb = k.getBytes(StandardCharsets.UTF_8);
                keys.add(kb);
                size += 12 + kb.length;
            }
            ByteBuffer bb = ByteBuffer.allocate(size).putInt(m.size());
            int i = 0;
            for (Long v : m.values()) {
                byte[] kb = keys.get(i++);
                bb.putInt(kb.length).put(kb).putLong(v);
            }
            return bb.array();
        }

        @Override
        public Object decode(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            TreeMap<String, Long> m = new TreeMap<>();
            int n = bb.getInt();
            for (int i = 0; i < n; i++) {
                byte[] kb = new byte[bb.getInt()];
                bb.get(kb);
                m.put(new String(kb, StandardCharsets.UTF_8), bb.getLong());
            }
            return m;
        }

        @Override
        @SuppressWarnings("unchecked")
        public long bytes(Object s) {
            long b = 48;
            for (String k : ((TreeMap<String, Long>) s).keySet()) {
                b += 72 + 2L * k.length();
            }
            return b;
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of();
        }

        @Override
        public Object fromSql(Object[] p) {
            throw new UnsupportedOperationException(name() + " is computed from the cell's rows");
        }
    }

    /** A number as text without a binary tail: {@code 242000000.0} as {@code 242000000}, {@code 1.0E9} as {@code 1000000000}. */
    static String plain(String s) {
        if (s.indexOf('.') < 0 && s.indexOf('E') < 0) {
            return s;
        }
        try {
            return new BigDecimal(s).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return s;
        }
    }

    static Double number(String s) {
        try {
            double d = Double.parseDouble(s.strip());
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code mode(x)}: the most frequent value, a number when every value is one ({@code mode(PRICE)}), else a name
     * ({@code mode(CURRENCY)}). Ties go to the smallest (as SAS's {@code MODE}; Excel's {@code MODE.SNGL} takes the first met, which a
     * merged total cannot know); when no value repeats there is no mode (Excel's #N/A).
     */
    public static final class Mode extends OfTexts {
        @Override
        public String name() {
            return "mode";
        }

        @Override
        public Double finish(Object s) {
            return result(s) instanceof Double d ? d : null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object result(Object s) {
            TreeMap<String, Long> m = (TreeMap<String, Long>) s;
            if (m.isEmpty()) {
                return null;
            }
            TreeMap<Double, Long> nums = new TreeMap<>();
            for (Map.Entry<String, Long> e : m.entrySet()) {
                Double d = number(e.getKey());
                if (d == null) {
                    nums = null;
                    break;
                }
                nums.merge(d == 0 ? 0.0 : d, e.getValue(), Long::sum);
            }
            Map<?, Long> counts = nums != null ? nums : m;
            Object best = null;
            long most = 0;
            for (Map.Entry<?, Long> e : counts.entrySet()) {          // in ascending order: the first of the most frequent wins ties
                if (e.getValue() > most) {
                    most = e.getValue();
                    best = e.getKey();
                }
            }
            return most < 2 ? null : best;
        }

        @Override
        public String emptyNote() {
            return "no values in the cell, or no value repeats (Excel's #N/A)";
        }
    }

    /** The most values {@code string_agg} joins in one cell. */
    public static final int STRING_AGG_MAX = 1_000;

    /**
     * {@code string_agg(x, sep, [distinct])}: the cell's values joined by {@code sep}, in text order (so every split and every node gives the
     * same text), each value as often as it occurs or, with distinct, once. Bounded: past {@value #STRING_AGG_MAX} values the cell has no
     * figure, said in words (never a cut list that looks whole).
     */
    public static final class StringAgg extends OfTexts {
        private final String sep;
        private final boolean distinct;

        public StringAgg(String sep, boolean distinct) {
            this.sep = sep == null ? ", " : sep;
            this.distinct = distinct;
        }

        @Override
        public String name() {
            return "string_agg";
        }

        @Override
        int cap() {
            return STRING_AGG_MAX + 1;
        }

        @Override
        public String resultUnit() {
            return "";
        }

        @Override
        public Double finish(Object s) {
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object result(Object s) {
            TreeMap<String, Long> m = (TreeMap<String, Long>) s;
            if (m.isEmpty()) {
                return null;
            }
            long total = distinct ? m.size() : m.values().stream().mapToLong(Long::longValue).sum();
            if (total > STRING_AGG_MAX) {
                return null;
            }
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Long> e : m.entrySet()) {
                String v = plain(e.getKey());
                for (long i = 0; i < (distinct ? 1 : e.getValue()); i++) {
                    if (b.length() > 0) {
                        b.append(sep);
                    }
                    b.append(v);
                }
            }
            return b.toString();
        }

        @Override
        public String emptyNote() {
            return "no values in the cell, or more than " + STRING_AGG_MAX + " to join (string_agg joins at most " + STRING_AGG_MAX
                    + "; filter the cell, or count them with count_distinct)";
        }
    }
}
