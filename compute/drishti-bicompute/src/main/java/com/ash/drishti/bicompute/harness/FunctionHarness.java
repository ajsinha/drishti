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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The harness every function passes (ARCHITECTURE.md section 26.5): split-and-merge must equal one pass. It runs the function on sample rows
 * in one pass, in batches (1, 7, 1,000, 65,536 rows), split into 2, 3 and 16 random parts merged left-deep and balanced under several seeds,
 * with every state serialised and read back before merging (as the cluster does), and on an empty input; a mergeable function fails when
 * any answer differs from the one pass beyond the tolerance (relative), a holistic one when its merged values differ at all.
 */
public final class FunctionHarness {

    /** One run of the harness: what was run, its answer, and its difference from the one pass. */
    public record Run(String how, Double value, double diff) {}

    /**
     * The harness's verdict.
     *
     * @param function the function's name
     * @param passed true when every run is within the tolerance
     * @param onePass the one-pass answer
     * @param runs every run
     * @param worst the run with the largest difference
     */
    public record Report(String function, boolean passed, Double onePass, List<Run> runs, Run worst) {}

    private FunctionHarness() {}

    /** Runs the harness on {@code rows} with {@code seeds} seeds and the relative {@code tolerance}. */
    public static Report run(AggFunction<Object> f, Batch rows, int seeds, double tolerance) {
        Double one = f.finish(f.add(f.start(), rows));
        List<Run> runs = new ArrayList<>();
        for (int size : new int[] {1, 7, 1_000, 65_536}) {
            Object s = f.start();
            for (int i = 0; i < rows.rows(); i += size) {
                Object part = f.add(f.start(), rows.slice(i, Math.min(rows.rows(), i + size)));
                s = f.merge(roundTrip(f, s), roundTrip(f, part));
            }
            runs.add(run("batches of " + size, f.finish(s), one, tolerance));
        }
        for (int parts : new int[] {2, 3, 16}) {
            for (int seed = 1; seed <= seeds; seed++) {
                List<Object> states = split(f, rows, parts, new Random(seed * 7919L + parts));
                runs.add(run(parts + " parts, seed " + seed + ", left-deep", f.finish(leftDeep(f, states)), one, tolerance));
                runs.add(run(parts + " parts, seed " + seed + ", balanced", f.finish(balanced(f, states, 0, states.size())), one, tolerance));
            }
        }
        Object withEmpty = f.merge(roundTrip(f, f.add(f.start(), rows)), roundTrip(f, f.add(f.start(), rows.slice(0, 0))));
        runs.add(run("with an empty part", f.finish(withEmpty), one, tolerance));
        Object emptyFirst = f.merge(roundTrip(f, f.start()), roundTrip(f, f.add(f.start(), rows)));
        runs.add(run("an empty part first", f.finish(emptyFirst), one, tolerance));
        Run worst = runs.stream().max(java.util.Comparator.comparingDouble(Run::diff)).orElse(null);
        boolean passed = runs.stream().allMatch(r -> r.diff() <= tolerance);
        return new Report(f.name(), passed, one, runs, worst);
    }

    private static Object roundTrip(AggFunction<Object> f, Object s) {
        return f.decode(f.encode(s));
    }

    private static Run run(String how, Double v, Double one, double tol) {
        double diff;
        if (v == null || one == null) {
            diff = v == null && one == null ? 0 : Double.POSITIVE_INFINITY;
        } else {
            double scale = Math.max(1e-300, Math.max(Math.abs(one), Math.abs(v)));
            diff = v.equals(one) ? 0 : Math.abs(v - one) / scale;
        }
        return new Run(how, v, diff);
    }

    /** The rows dealt into {@code parts} random parts (each row to one part), each part's state round-tripped. */
    private static List<Object> split(AggFunction<Object> f, Batch rows, int parts, Random rnd) {
        List<List<Integer>> idx = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            idx.add(new ArrayList<>());
        }
        for (int r = 0; r < rows.rows(); r++) {
            idx.get(rnd.nextInt(parts)).add(r);
        }
        List<Object> out = new ArrayList<>();
        for (List<Integer> ix : idx) {
            Object[] cols = new Object[rows.columns().length];
            for (int c = 0; c < cols.length; c++) {
                if (rows.columns()[c] instanceof double[] d) {
                    double[] x = new double[ix.size()];
                    for (int i = 0; i < x.length; i++) {
                        x[i] = d[ix.get(i)];
                    }
                    cols[c] = x;
                } else {
                    String[] s = (String[]) rows.columns()[c];
                    String[] x = new String[ix.size()];
                    for (int i = 0; i < x.length; i++) {
                        x[i] = s[ix.get(i)];
                    }
                    cols[c] = x;
                }
            }
            out.add(roundTrip(f, f.add(f.start(), new Batch(ix.size(), cols))));
        }
        java.util.Collections.shuffle(out, rnd);
        return out;
    }

    private static Object leftDeep(AggFunction<Object> f, List<Object> states) {
        Object s = states.get(0);
        for (int i = 1; i < states.size(); i++) {
            s = f.merge(s, states.get(i));
        }
        return s;
    }

    private static Object balanced(AggFunction<Object> f, List<Object> states, int from, int to) {
        if (to - from == 1) {
            return states.get(from);
        }
        int mid = (from + to) / 2;
        return f.merge(roundTrip(f, balanced(f, states, from, mid)), roundTrip(f, balanced(f, states, mid, to)));
    }
}
