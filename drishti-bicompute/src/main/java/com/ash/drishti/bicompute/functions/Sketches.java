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

import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The sketches (ARCHITECTURE.md sections 26.3 and 26.6; RACHANA_REFERENCE.md section F4): the ≈ forms of functions that have one name
 * each, never functions of their own. A view writes {@code distinct(x)}, {@code median(x)}, {@code percentile(x, q)}; the scale policy
 * answers it by its sketch when the person accepts ≈ (or the view is large, in auto), and its id then ends in {@code ≈} (a different cache
 * key, a different rollup state). VaR and every provider's own measures are never approximated.
 *
 * <ul>
 *   <li>{@code distinct(x)} ≈: HyperLogLog ({@link Hll}), ±1.04/√m.</li>
 *   <li>{@code median(x)}, {@code percentile(x, q)} ≈: KLL ({@link Kll}), ±1.65% of rank at k = 200.</li>
 *   <li>{@code top_n} ≈: the Space-Saving state ({@link SpaceSaving}, {@link Top}, 10·n counters, each count over by at most the
 *       smallest counter), built and tested; its use by a level's Top N past the group guard is not yet wired.</li>
 * </ul>
 *
 * In SQL (the tabular provider): HyperLogLog's (register, rank) pairs from DuckDB's {@code hash()}; KLL from the cell's quantile points
 * ({@code quantile_disc} at 2k + 1 fractions) and its count, turned into weighted values (decision 6: built in Java; the points add at most
 * 1/(4k) to the rank error); Space-Saving from the cell's {@code histogram}, pruned to the capacity.
 */
public final class Sketches {

    /**
     * The sketches' settings (drishti.pivot.sketches), set once by the host at start; functions parsed afterwards use them.
     *
     * @param hllPrecision p: 2^p registers (11 to 16)
     * @param kllK KLL's k
     * @param topCapacityFactor Space-Saving keeps this many counters per value shown
     */
    public record Settings(int hllPrecision, int kllK, int topCapacityFactor) {
        public Settings {
            hllPrecision = Math.max(Hll.MIN_P, Math.min(Hll.MAX_P, hllPrecision <= 0 ? 12 : hllPrecision));
            kllK = kllK <= 0 ? 200 : Math.max(8, kllK);
            topCapacityFactor = topCapacityFactor <= 0 ? 10 : topCapacityFactor;
        }

        public static Settings defaults() {
            return new Settings(12, 200, 10);
        }
    }

    private static volatile Settings settings = Settings.defaults();

    private Sketches() {}

    public static Settings settings() {
        return settings;
    }

    public static void settings(Settings s) {
        settings = s == null ? Settings.defaults() : s;
    }

    /** What every sketch function says about itself: its error in words and its exact form. */
    public interface Sketch {
        /** The stated error, in words ({@code ±1.6% (HyperLogLog, 4,096 registers)}). */
        String error();

        /** The stated error as a number (relative error, or rank error), for the answer. */
        double errorValue();

        /** The measure's exact form as a view writes it, from the inputs and numbers of this call ({@code count_distinct(CPTY)}). */
        String exactText(List<String> inputs);
    }

    /** The suffix of a sketched call's id ({@code distinct(CPTY) ≈}): the cache, rollups and the cluster tell it from the exact form. */
    public static final String MARK = " ≈";

    /** True when a function has an ≈ form (an exact distinct count or quantile of the built-ins). */
    public static boolean hasSketch(AggFunction<?> f) {
        return f instanceof Builtins.Distinct || f instanceof Builtins.Quantile;
    }

    /** The ≈ form of a call (same inputs and label, its id marked), or empty when it has none. */
    public static java.util.Optional<com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall> approximate(
            com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall f) {
        AggFunction<Object> s;
        if (f.fn() instanceof Builtins.Distinct) {
            s = new ApproxDistinct();
        } else if (f.fn() instanceof Builtins.Quantile q) {
            s = new ApproxQuantile(q.name(), q.q());
        } else {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall(f.id() + MARK, f.label(), s, f.inputs()));
    }

    /**
     * A call's text as written in a rollup or sent between nodes: the function's own text, or that text with {@link #MARK} for its ≈ form.
     */
    public static com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall parse(String text, com.ash.drishti.kernel.api.pivot.PivotProvider p) {
        String t = text.strip();
        if (t.endsWith(MARK.strip())) {
            com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall exact = Functions.parse(t.substring(0, t.length() - 1).strip(), null, p);
            return approximate(exact).orElseThrow(() -> com.ash.drishti.kernel.api.pivot.PivotView.bad(exact.id() + " has no ≈ form; "
                    + "distinct, median and percentile have"));
        }
        return Functions.parse(t, null, p);
    }

    /** The values of a SQL list (a JDBC array, an array, a list); empty for null. */
    static List<Object> list(Object o) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof java.sql.Array a) {
            try {
                return Arrays.asList((Object[]) a.getArray());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("a list from DuckDB could not be read: " + e.getMessage(), e);
            }
        }
        if (o instanceof Object[] a) {
            return Arrays.asList(a);
        }
        if (o instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        if (o instanceof double[] d) {
            List<Object> out = new ArrayList<>(d.length);
            for (double x : d) {
                out.add(x);
            }
            return out;
        }
        if (o instanceof long[] d) {
            List<Object> out = new ArrayList<>(d.length);
            for (long x : d) {
                out.add(x);
            }
            return out;
        }
        throw new IllegalStateException("not a list: " + o.getClass().getName());
    }

    private static String pct(double x) {
        return String.format(Locale.ROOT, "%.2g%%", x * 100);
    }

    // ---- HyperLogLog -------------------------------------------------------------------------------------------------------------------

    /** {@code distinct(x)} ≈: the HyperLogLog estimate of the distinct values. */
    public static final class ApproxDistinct implements AggFunction<Object>, Sketch {
        private final int p;

        public ApproxDistinct() {
            this(settings.hllPrecision());
        }

        public ApproxDistinct(int p) {
            this.p = p;
        }

        @Override
        public String name() {
            return "distinct";
        }

        @Override
        public Kind kind() {
            return Kind.SKETCH;
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
            return new Hll(p, Hll.FAMILY_NONE);
        }

        @Override
        public Object add(Object s, Batch b) {
            Hll h = (Hll) s;
            Object c = b.columns()[0];
            for (int i = 0; i < b.rows(); i++) {
                String v = c instanceof double[] d ? (Double.isNaN(d[i]) ? null : Double.toString(d[i])) : ((String[]) c)[i];
                if (v != null) {
                    h.addHash(Hll.hash(v));
                }
            }
            return h;
        }

        @Override
        public Object merge(Object a, Object b) {
            return ((Hll) a).merge((Hll) b);
        }

        @Override
        public Double finish(Object s) {
            Hll h = (Hll) s;
            return h.empty() ? 0.0 : (double) Math.round(h.estimate());
        }

        @Override
        public byte[] encode(Object s) {
            return ((Hll) s).encode();
        }

        @Override
        public Object decode(byte[] bytes) {
            return Hll.decode(bytes);
        }

        @Override
        public long bytes(Object s) {
            return 48 + (1L << p);
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of(Hll.sql(in.get(0), p));
        }

        @Override
        public Object fromSql(Object[] parts) {
            Hll h = new Hll(p, Hll.FAMILY_SQL);
            for (Object o : list(parts[0])) {
                if (o instanceof Number x) {
                    h.addCode(x.longValue());
                }
            }
            return h;
        }

        @Override
        public String error() {
            return "±" + pct(errorValue()) + " (HyperLogLog, " + String.format(Locale.ROOT, "%,d", 1 << p) + " registers, one standard error)";
        }

        @Override
        public double errorValue() {
            return Hll.relativeError(p);
        }

        @Override
        public String exactText(List<String> inputs) {
            return "distinct(" + inputs.get(0) + ")";
        }
    }

    // ---- KLL -----------------------------------------------------------------------------------------------------------------------------

    /** {@code median(x)}, {@code percentile(x, q)} ≈: the q-quantile of a KLL sketch. */
    public static final class ApproxQuantile implements AggFunction<Object>, Sketch {
        private final String name;
        private final double q;
        private final int k;

        public ApproxQuantile(String name, double q) {
            this(name, q, settings.kllK());
        }

        public ApproxQuantile(String name, double q, int k) {
            if (!(q >= 0 && q <= 1)) {
                throw new IllegalArgumentException("a percentile is between 0 and 1 (0.99), not " + q);
            }
            this.name = name;
            this.q = q;
            this.k = k;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Kind kind() {
            return Kind.SKETCH;
        }

        @Override
        public int inputs() {
            return 1;
        }

        @Override
        public Object start() {
            return new Kll(k);
        }

        @Override
        public Object add(Object s, Batch b) {
            Kll kl = (Kll) s;
            double[] x = b.num(0);
            for (int i = 0; i < b.rows(); i++) {
                kl.update(x[i]);
            }
            return kl;
        }

        @Override
        public Object merge(Object a, Object b) {
            return ((Kll) a).merge((Kll) b);
        }

        @Override
        public Double finish(Object s) {
            return ((Kll) s).quantile(q);
        }

        @Override
        public byte[] encode(Object s) {
            return ((Kll) s).encode();
        }

        @Override
        public Object decode(byte[] bytes) {
            return Kll.decode(bytes);
        }

        @Override
        public long bytes(Object s) {
            return ((Kll) s).bytes();
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return List.of("count(" + in.get(0) + ")", "quantile_disc(" + in.get(0) + ", " + Kll.fractions(2 * k) + ")");
        }

        @Override
        public Object fromSql(Object[] parts) {
            long n = parts[0] instanceof Number x ? x.longValue() : 0;
            List<Double> pts = new ArrayList<>();
            for (Object o : list(parts[1])) {
                pts.add(o instanceof Number x ? x.doubleValue() : null);
            }
            return Kll.fromPoints(k, pts, n);
        }

        @Override
        public String error() {
            return "±" + pct(errorValue()) + " of rank (KLL, k = " + k + ")";
        }

        @Override
        public double errorValue() {
            return Kll.rankError(k) + 1.0 / (4.0 * k);
        }

        @Override
        public String exactText(List<String> inputs) {
            return "median".equals(name) ? "median(" + inputs.get(0) + ")" : "percentile(" + inputs.get(0) + ", " + q + ")";
        }
    }

    // ---- Space-Saving -------------------------------------------------------------------------------------------------------------------

    /**
     * The ≈ state of {@code top_n} by rows: the n most frequent values of x (a Space-Saving summary of 10·n counters; exact: every value
     * counted, holistic); {@link #values} gives them with their counts and bounds, {@link #finish} the rows they hold. Built and tested;
     * a level's Top N does not use it yet.
     */
    public static final class Top implements AggFunction<Object>, Sketch {
        private final int n;
        private final boolean exact;
        private final int capacity;

        public Top(int n, boolean exact) {
            if (n < 1 || n > 1_000) {
                throw new IllegalArgumentException("top takes 1 to 1,000 values, not " + n);
            }
            this.n = n;
            this.exact = exact;
            this.capacity = exact ? 0 : n * settings.topCapacityFactor();
        }

        @Override
        public String name() {
            return "top_n";
        }

        @Override
        public Kind kind() {
            return exact ? Kind.HOLISTIC : Kind.SKETCH;
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
            return new SpaceSaving(capacity);
        }

        @Override
        public Object add(Object s, Batch b) {
            SpaceSaving ss = (SpaceSaving) s;
            Object c = b.columns()[0];
            for (int i = 0; i < b.rows(); i++) {
                String v = c instanceof double[] d ? (Double.isNaN(d[i]) ? null : Double.toString(d[i])) : ((String[]) c)[i];
                ss.add(v, 1);
            }
            return ss;
        }

        @Override
        public Object merge(Object a, Object b) {
            return ((SpaceSaving) a).merge((SpaceSaving) b);
        }

        @Override
        public Double finish(Object s) {
            long t = 0;
            for (SpaceSaving.Counted c : ((SpaceSaving) s).top(n)) {
                t += c.count();
            }
            return (double) t;
        }

        @Override
        public String emptyNote() {
            return "no values in the cell";
        }

        /** The n most frequent values of a state, largest first. */
        public List<SpaceSaving.Counted> values(Object s) {
            return ((SpaceSaving) s).top(n);
        }

        @Override
        public byte[] encode(Object s) {
            return ((SpaceSaving) s).encode();
        }

        @Override
        public Object decode(byte[] bytes) {
            return SpaceSaving.decode(bytes);
        }

        @Override
        public long bytes(Object s) {
            return ((SpaceSaving) s).bytes();
        }

        @Override
        public List<String> sqlState(List<String> in) {
            String h = "histogram(CAST(" + in.get(0) + " AS VARCHAR)) FILTER (WHERE " + in.get(0) + " IS NOT NULL)";
            return List.of("map_keys(" + h + ")", "map_values(" + h + ")");
        }

        @Override
        public Object fromSql(Object[] parts) {
            List<String> vals = new ArrayList<>();
            List<Long> counts = new ArrayList<>();
            for (Object o : list(parts[0])) {
                vals.add(o == null ? null : String.valueOf(o));
            }
            for (Object o : list(parts[1])) {
                counts.add(o instanceof Number x ? x.longValue() : 0L);
            }
            return SpaceSaving.fromCounts(capacity, vals, counts);
        }

        @Override
        public boolean sqlStateMergeable() {
            return true;                       // the exact form's state is every value's count: it merges exactly
        }

        @Override
        public String error() {
            return exact ? "exact" : "counts over by at most the smallest of " + capacity + " counters (Space-Saving)";
        }

        @Override
        public double errorValue() {
            return 0;
        }

        @Override
        public String exactText(List<String> inputs) {
            return "top_n(" + n + ", count(" + inputs.get(0) + "))";
        }
    }
}
