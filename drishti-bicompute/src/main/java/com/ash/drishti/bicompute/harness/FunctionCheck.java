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
package com.ash.drishti.bicompute.harness;

import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole harness a custom function passes before it is saved, shared or used (HOW_IT_FITS.md section A26.5), around the split-and-merge
 * core of {@link FunctionHarness}:
 *
 * <ol>
 *   <li><b>one pass</b> runs;</li>
 *   <li><b>batches and splits</b>: batches of 1, 7, 1,000 and 65,536 rows, 2, 3 and 16 random parts merged left-deep and balanced under
 *       several seeds, every state serialised and read back (mergeable within the tolerance; a sketch within its stated error);</li>
 *   <li><b>empty and null</b>: an empty input, an empty part, a part of nulls, a single row, all values equal, infinities and NaN;</li>
 *   <li><b>state round trip</b>: a state read back finishes to the same figure;</li>
 *   <li><b>deterministic</b>: the same rows in the same order give the same bits;</li>
 *   <li><b>cost</b>: nanoseconds a row under {@code maxNsPerRow}, the fastest of up to {@link #COST_RUNS} warm runs;</li>
 *   <li><b>state bound</b>: the largest state within the declared bytes.</li>
 * </ol>
 *
 * A holistic function runs one pass, the empty and null checks, the round trip, determinism and cost; its size bound is reported.
 */
public final class FunctionCheck {

    /**
     * @param tolerance the relative difference allowed (mergeable)
     * @param seeds the seeds of the random splits
     * @param maxNsPerRow the cost limit (0: none)
     * @param stateBytes the declared state bound (0: none)
     * @param sketchError a sketch's stated relative error
     */
    public record Options(double tolerance, int seeds, long maxNsPerRow, long stateBytes, double sketchError) {
        public static Options defaults() {
            return new Options(1e-12, 5, 2000, 0, 0);
        }
    }

    /** One check: its name, whether it passed and what it found. */
    public record Check(String name, boolean passed, String detail) {}

    /**
     * The verdict.
     *
     * @param function the function's name
     * @param kind its kind
     * @param passed every check passed
     * @param onePass the one-pass figure over the sample
     * @param checks every check, in the order run
     * @param splits the split-and-merge report (null for a holistic function)
     * @param nsPerRow measured cost a row
     * @param stateBytes the largest state seen, serialised
     * @param rows the sample's rows
     */
    public record Verdict(String function, Kind kind, boolean passed, Double onePass, List<Check> checks, FunctionHarness.Report splits,
            double nsPerRow, long stateBytes, int rows) {

        /** The first check that failed, in words (null: none). */
        public String firstFailure() {
            return checks.stream().filter(c -> !c.passed()).map(c -> c.name() + ": " + c.detail()).findFirst().orElse(null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("function", function);
            m.put("kind", kind.word());
            m.put("passed", passed);
            m.put("onePass", onePass);
            m.put("rows", rows);
            m.put("nsPerRow", Math.round(nsPerRow * 10) / 10.0);
            m.put("stateBytes", stateBytes);
            m.put("firstFailure", firstFailure());
            List<Map<String, Object>> cs = new ArrayList<>();
            checks.forEach(c -> cs.add(Map.of("check", c.name(), "passed", c.passed(), "detail", c.detail())));
            m.put("checks", cs);
            if (splits != null) {
                List<Map<String, Object>> runs = new ArrayList<>();
                splits.runs().forEach(r -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("how", r.how());
                    x.put("value", r.value());
                    x.put("diff", Double.isInfinite(r.diff()) ? "one side empty" : r.diff());
                    runs.add(x);
                });
                m.put("runs", runs);
                m.put("worst", splits.worst() == null ? null : Map.of("how", splits.worst().how(), "diff", Double.isInfinite(splits.worst().diff())
                        ? "one side empty" : splits.worst().diff()));
            }
            return m;
        }
    }

    /** The most warm runs timed for the cost: the fastest counts, and timing stops at the first run within the limit. */
    static final int COST_RUNS = 5;

    private FunctionCheck() {}

    /** Runs every check on {@code sample} (the function's declared inputs, in order). */
    public static Verdict run(AggFunction<Object> f, Batch sample, Options o) {
        List<Check> checks = new ArrayList<>();
        long[] maxState = {0};
        Double one;
        long t0 = System.nanoTime();
        Object state;
        try {
            state = f.add(f.start(), sample);
            one = f.finish(state);
            maxState[0] = Math.max(maxState[0], f.encode(state).length);
        } catch (RuntimeException e) {
            checks.add(new Check("one pass", false, "failed: " + e.getClass().getSimpleName() + " " + e.getMessage()));
            return new Verdict(f.name(), f.kind(), false, null, checks, null, 0, 0, sample.rows());
        }
        double ns = sample.rows() == 0 ? 0 : (System.nanoTime() - t0) / (double) sample.rows();     // replaced by a warm run below
        checks.add(new Check("one pass", true, "the figure is " + one));

        FunctionHarness.Report splits = null;
        if (f.kind() != Kind.HOLISTIC) {
            double tol = f.kind() == Kind.SKETCH ? Math.max(o.sketchError(), o.tolerance()) : o.tolerance();
            try {
                splits = FunctionHarness.run(f, sample, Math.max(1, o.seeds()), tol);
                checks.add(new Check("batches and splits", splits.passed(), splits.passed() ? splits.runs().size() + " runs within " + tol
                        + " of the one pass" : "differs from the one pass by " + splits.worst().diff() + " (relative) in " + splits.worst().how()
                                + ": " + splits.worst().value() + " against " + one));
            } catch (RuntimeException e) {
                checks.add(new Check("batches and splits", false, "failed: " + e.getClass().getSimpleName() + " " + e.getMessage()));
            }
        }

        checks.addAll(edges(f, sample, one, o, maxState));

        try {
            Object again = f.decode(f.encode(f.add(f.start(), sample)));
            Double v = f.finish(again);
            boolean same = v == null ? one == null : v.equals(one);
            checks.add(new Check("state round trip", same, same ? "the state read back finishes to the same figure" : "read back: " + v
                    + ", before: " + one));
        } catch (RuntimeException e) {
            checks.add(new Check("state round trip", false, "failed: " + e.getMessage()));
        }

        try {
            long t1 = System.nanoTime();
            Double second = f.finish(f.add(f.start(), sample));
            ns = sample.rows() == 0 ? 0 : (System.nanoTime() - t1) / (double) sample.rows();     // the cost of a warm run (after the splits)
            boolean same = second == null ? one == null : one != null && Double.doubleToLongBits(second) == Double.doubleToLongBits(one);
            checks.add(new Check("deterministic", same, same ? "the same rows in the same order gave the same bits" : "two runs gave " + one
                    + " and " + second));
            // the cost is the fastest of a few warm runs: one run timed alone also timed whatever else the machine did meanwhile (a
            // collection, another busy thread), and a function within its limit was refused on a busy server
            for (int k = 1; k < COST_RUNS && sample.rows() > 0 && o.maxNsPerRow() > 0 && ns > o.maxNsPerRow(); k++) {
                long t2 = System.nanoTime();
                f.finish(f.add(f.start(), sample));
                ns = Math.min(ns, (System.nanoTime() - t2) / (double) sample.rows());
            }
        } catch (RuntimeException e) {
            checks.add(new Check("deterministic", false, "failed: " + e.getMessage()));
        }

        boolean fast = o.maxNsPerRow() <= 0 || sample.rows() < 1000 || ns <= o.maxNsPerRow();
        checks.add(new Check("cost", fast, String.format(java.util.Locale.ROOT, "%.0f ns a row (limit %d)%s", ns, o.maxNsPerRow(),
                sample.rows() < 1000 ? "; under 1,000 sample rows the cost is shown, not judged" : "")));

        boolean small = o.stateBytes() <= 0 || maxState[0] <= o.stateBytes();
        checks.add(new Check("state bound", small, "the largest state was " + maxState[0] + " bytes" + (o.stateBytes() > 0 ? " (bound "
                + o.stateBytes() + ")" : "") + (f.kind() == Kind.HOLISTIC ? "; holistic: a cell's rows are bounded by holistic-max-rows" : "")));

        boolean passed = checks.stream().allMatch(Check::passed);
        return new Verdict(f.name(), f.kind(), passed, one, checks, splits, ns, maxState[0], sample.rows());
    }

    private static List<Check> edges(AggFunction<Object> f, Batch sample, Double one, Options o, long[] maxState) {
        List<Check> out = new ArrayList<>();
        boolean mergeable = f.kind() != Kind.HOLISTIC;
        try {
            Double empty = f.finish(f.add(f.start(), sample.slice(0, 0)));
            out.add(new Check("empty", true, "an empty cell finishes to " + (empty == null ? "nothing (" + f.emptyNote() + ")" : empty)));
        } catch (RuntimeException e) {
            out.add(new Check("empty", false, "an empty cell failed: " + e.getMessage()));
        }
        try {
            Batch nulls = nulls(sample, Math.max(1, Math.min(10, sample.rows())));
            Object s = f.add(f.start(), nulls);
            maxState[0] = Math.max(maxState[0], f.encode(s).length);
            String d = "a part of nulls runs";
            boolean ok = true;
            if (mergeable) {
                Double withNulls = f.finish(f.merge(f.decode(f.encode(f.add(f.start(), sample))), f.decode(f.encode(s))));
                Double together = f.finish(f.add(f.start(), concat(sample, nulls)));
                ok = close(withNulls, together, o.tolerance());
                d = ok ? "a part of nulls merged equals one pass over the rows and the nulls" : "a part of nulls merged gives " + withNulls
                        + ", one pass over the same rows " + together;
            }
            out.add(new Check("nulls", ok, d));
        } catch (RuntimeException e) {
            out.add(new Check("nulls", false, "a part of nulls failed: " + e.getMessage()));
        }
        if (sample.rows() > 0) {
            try {
                Double single = f.finish(f.add(f.start(), sample.slice(0, 1)));
                out.add(new Check("single row", true, "one row finishes to " + single));
            } catch (RuntimeException e) {
                out.add(new Check("single row", false, "one row failed: " + e.getMessage()));
            }
            try {
                Batch same = repeat(sample.slice(0, 1), 100);
                Double a = f.finish(f.add(f.start(), same));
                String d = "100 equal rows finish to " + a;
                boolean ok = true;
                if (mergeable) {
                    Double b = f.finish(f.merge(f.add(f.start(), same.slice(0, 37)), f.add(f.start(), same.slice(37, 100))));
                    ok = close(a, b, o.tolerance());
                    d = ok ? d + ", split or not" : "100 equal rows give " + a + " in one pass and " + b + " split";
                }
                out.add(new Check("all equal", ok, d));
            } catch (RuntimeException e) {
                out.add(new Check("all equal", false, "equal rows failed: " + e.getMessage()));
            }
        }
        if (f.numeric() && sample.columns().length > 0) {
            try {
                Batch odd = oddNumbers(sample);
                f.finish(f.add(f.start(), odd));
                out.add(new Check("infinities and NaN", true, "inputs with NaN and ±infinity run"));
            } catch (RuntimeException e) {
                out.add(new Check("infinities and NaN", false, "inputs with NaN or infinity failed: " + e.getMessage()));
            }
        }
        return out;
    }

    static boolean close(Double a, Double b, double tol) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a.equals(b)) {
            return true;
        }
        double scale = Math.max(1e-300, Math.max(Math.abs(a), Math.abs(b)));
        return Math.abs(a - b) / scale <= tol;
    }

    private static Batch nulls(Batch like, int n) {
        Object[] cols = new Object[like.columns().length];
        for (int i = 0; i < cols.length; i++) {
            if (like.columns()[i] instanceof double[]) {
                double[] d = new double[n];
                Arrays.fill(d, Double.NaN);
                cols[i] = d;
            } else {
                cols[i] = new String[n];
            }
        }
        return new Batch(n, cols);
    }

    private static Batch concat(Batch a, Batch b) {
        Object[] cols = new Object[a.columns().length];
        int n = a.rows() + b.rows();
        for (int i = 0; i < cols.length; i++) {
            if (a.columns()[i] instanceof double[] x) {
                double[] y = (double[]) b.columns()[i];
                double[] z = Arrays.copyOf(x, n);
                System.arraycopy(y, 0, z, a.rows(), b.rows());
                cols[i] = z;
            } else {
                String[] x = (String[]) a.columns()[i];
                String[] y = (String[]) b.columns()[i];
                String[] z = Arrays.copyOf(x, n);
                System.arraycopy(y, 0, z, a.rows(), b.rows());
                cols[i] = z;
            }
        }
        return new Batch(n, cols);
    }

    private static Batch repeat(Batch one, int n) {
        Object[] cols = new Object[one.columns().length];
        for (int i = 0; i < cols.length; i++) {
            if (one.columns()[i] instanceof double[] d) {
                double[] x = new double[n];
                Arrays.fill(x, d[0]);
                cols[i] = x;
            } else {
                String[] x = new String[n];
                Arrays.fill(x, ((String[]) one.columns()[i])[0]);
                cols[i] = x;
            }
        }
        return new Batch(n, cols);
    }

    private static Batch oddNumbers(Batch like) {
        double[] odd = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.0, 1e308, -1e308};
        Object[] cols = new Object[like.columns().length];
        for (int i = 0; i < cols.length; i++) {
            if (like.columns()[i] instanceof double[]) {
                cols[i] = odd.clone();
            } else {
                cols[i] = new String[] {"NaN", "Infinity", "-Infinity", "0", "-0", "1e308", null};
            }
        }
        return new Batch(odd.length, cols);
    }
}
