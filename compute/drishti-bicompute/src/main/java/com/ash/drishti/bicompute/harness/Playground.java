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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The custom-function playground's computation (HOW_IT_FITS.md section A26.5): the sample cut into parts in row order, the state after each
 * part and after each merge (as the engine keeps it, its bytes and size), the final figure beside the one-pass figure, the cost projected on
 * a real table, and the function tried in a little pivot of the sample (by a group column the author picks), with its total made from the
 * groups' states, never from their figures.
 */
public final class Playground {

    private Playground() {}

    /**
     * @param f the function
     * @param sample the inputs, in order
     * @param parts how many parts (1 to 16)
     * @param groups a group per row (null: no pivot)
     * @param projectRows rows of the real table, for the cost line (0: none)
     * @param nodes nodes times workers the projection divides by (at least 1)
     */
    public static Map<String, Object> run(AggFunction<Object> f, Batch sample, int parts, String[] groups, long projectRows, int nodes) {
        Map<String, Object> out = new LinkedHashMap<>();
        int n = Math.max(1, Math.min(16, parts));
        long t0 = System.nanoTime();
        Object one = f.add(f.start(), sample);
        double ns = sample.rows() == 0 ? 0 : (System.nanoTime() - t0) / (double) sample.rows();
        Double onePass = f.finish(one);
        List<Map<String, Object>> steps = new ArrayList<>();
        boolean mergeable = f.kind() != Kind.HOLISTIC;
        Object merged = null;
        int size = Math.max(1, (sample.rows() + n - 1) / n);
        for (int i = 0, k = 0; i < Math.max(1, sample.rows()); i += size, k++) {
            Batch part = sample.slice(Math.min(i, sample.rows()), Math.min(sample.rows(), i + size));
            Object st = f.add(f.start(), part);
            steps.add(step("part " + (k + 1) + " (rows " + (i + 1) + " to " + Math.min(sample.rows(), i + size) + ")", f, st));
            if (mergeable) {
                merged = merged == null ? st : f.merge(f.decode(f.encode(merged)), f.decode(f.encode(st)));
                if (k > 0) {
                    steps.add(step("merged parts 1 to " + (k + 1), f, merged));
                }
            }
            if (sample.rows() == 0) {
                break;
            }
        }
        out.put("kind", f.kind().word());
        out.put("steps", steps);
        out.put("onePass", onePass);
        Double fin = mergeable && merged != null ? f.finish(merged) : onePass;
        out.put("final", fin);
        out.put("same", fin == null ? onePass == null : FunctionCheck.close(fin, onePass, 1e-12));
        out.put("note", mergeable ? "the parts' states merged, then finished; the one pass is beside it" : "holistic: all the rows of a cell "
                + "in one call, never merged");
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("nsPerRow", Math.round(ns * 10) / 10.0);
        cost.put("rowsPerSecond", ns <= 0 ? null : Math.round(1e9 / ns));
        cost.put("stateBytes", f.encode(one).length);
        if (projectRows > 0 && ns > 0) {
            double secs = projectRows * ns / 1e9 / Math.max(1, nodes);
            cost.put("projection", String.format(java.util.Locale.ROOT, "≈%.1f s for %,d rows on %d node×worker%s", secs, projectRows,
                    Math.max(1, nodes), nodes == 1 ? "" : "s"));
        }
        out.put("cost", cost);
        if (groups != null) {
            TreeMap<String, List<Integer>> byGroup = new TreeMap<>();
            for (int r = 0; r < sample.rows(); r++) {
                byGroup.computeIfAbsent(groups[r] == null ? "(blank)" : groups[r], g -> new ArrayList<>()).add(r);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            Object total = f.start();
            for (Map.Entry<String, List<Integer>> g : byGroup.entrySet()) {
                Object st = f.add(f.start(), pick(sample, g.getValue()));
                if (mergeable) {
                    total = f.merge(total, st);
                }
                rows.add(Map.of("group", g.getKey(), "rows", g.getValue().size(), "value", String.valueOf(f.finish(st))));
            }
            Map<String, Object> pivot = new LinkedHashMap<>();
            pivot.put("rows", rows);
            pivot.put("total", mergeable ? f.finish(total) : onePass);
            pivot.put("totalFrom", mergeable ? "the groups' states merged" : "all the rows (holistic)");
            out.put("pivot", pivot);
        }
        return out;
    }

    private static Map<String, Object> step(String what, AggFunction<Object> f, Object st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("what", what);
        byte[] b = f.encode(st);
        m.put("bytes", b.length);
        m.put("state", describe(st));
        m.put("value", f.finish(st));
        return m;
    }

    /** A state as the screen shows it (its own text for small states, else its size). */
    static String describe(Object st) {
        if (st instanceof double[] d) {
            return java.util.Arrays.toString(d);
        }
        if (st instanceof long[] l) {
            return java.util.Arrays.toString(l);
        }
        if (st instanceof byte[] b) {
            String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            return s.length() <= 400 && s.chars().allMatch(c -> c >= 32 && c < 127) ? s : b.length + " bytes";
        }
        if (st instanceof Object[] a) {
            List<String> parts = new ArrayList<>();
            for (Object o : a) {
                parts.add(describe(o));
            }
            return cut(parts.toString());
        }
        if (st instanceof java.util.Collection<?> c) {
            List<String> parts = new ArrayList<>();
            for (Object o : c) {
                parts.add(describe(o));
            }
            return cut(parts.toString());
        }
        if (st != null && st.getClass().isRecord()) {           // a record's parts, each described (an array inside one too)
            List<String> parts = new ArrayList<>();
            for (java.lang.reflect.RecordComponent rc : st.getClass().getRecordComponents()) {
                try {
                    rc.getAccessor().setAccessible(true);
                    parts.add(rc.getName() + "=" + describe(rc.getAccessor().invoke(st)));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    parts.add(rc.getName() + "=?");
                }
            }
            return cut("{" + String.join(", ", parts) + "}");
        }
        String s = String.valueOf(st);
        if (st != null && s.startsWith(st.getClass().getName() + "@")) {   // no text of its own: never an identity string
            return st.getClass().getSimpleName() + " state";
        }
        return cut(s);
    }

    private static String cut(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }

    private static Batch pick(Batch b, List<Integer> rows) {
        Object[] cols = new Object[b.columns().length];
        for (int c = 0; c < cols.length; c++) {
            if (b.columns()[c] instanceof double[] d) {
                double[] x = new double[rows.size()];
                for (int i = 0; i < x.length; i++) {
                    x[i] = d[rows.get(i)];
                }
                cols[c] = x;
            } else {
                String[] s = (String[]) b.columns()[c];
                String[] x = new String[rows.size()];
                for (int i = 0; i < x.length; i++) {
                    x[i] = s[rows.get(i)];
                }
                cols[c] = x;
            }
        }
        return new Batch(rows.size(), cols);
    }
}
