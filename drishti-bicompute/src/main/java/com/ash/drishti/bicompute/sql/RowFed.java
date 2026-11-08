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
package com.ash.drishti.bicompute.sql;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.CellSet;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.bicompute.spi.Kind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Functions without an SQL form (ARCHITECTURE.md section 26.5: Python functions, Java plug-ins that compute in Java) on the tabular
 * provider: the rows the plan reads (the person's row rules first, then the view's filters) are scanned once, each row dealt to the cells of
 * every set it belongs to, and each cell's declared input columns fed to {@code add} in columnar batches ({@code batchRows}); a holistic
 * function gets all of a cell's rows in one call, refused for that cell (never truncated) past {@code holisticMaxRows}. Every other
 * function of the plan is computed in SQL as before, and the two are put together per cell.
 */
final class RowFed {

    private RowFed() {}

    /** The indices of the plan's functions that have no SQL form. */
    static List<Integer> fed(AggregationPlan plan) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < plan.functions().size(); i++) {
            FunctionCall f = plan.functions().get(i);
            if (f.fn().sqlState(f.inputs().stream().map(x -> "x").toList()).isEmpty()) {
                out.add(i);
            }
        }
        return out;
    }

    /** A cell's rows waiting to be fed: one buffer per input of each fed function. */
    private static final class Pending {
        final Object[] states;
        final List<Object[]> rows = new ArrayList<>();
        long seen;
        String refused;

        Pending(int n) {
            this.states = new Object[n];
        }
    }

    static Partials evaluate(AggregationPlan plan, List<Integer> fed, Function<AggregationPlan, Partials> sql, DuckDbExecutor exec,
            TabularSource.Relation rel, Map<String, String> types, int batchRows, long holisticMaxRows) {
        long t0 = System.nanoTime();
        List<FunctionCall> sqlFns = new ArrayList<>();
        List<Integer> sqlIdx = new ArrayList<>();
        List<FunctionCall> fedFns = new ArrayList<>();
        for (int i = 0; i < plan.functions().size(); i++) {
            if (fed.contains(i)) {
                fedFns.add(plan.functions().get(i));
            } else {
                sqlFns.add(plan.functions().get(i));
                sqlIdx.add(i);
            }
        }
        Partials bySql = sql.apply(plan.with(plan.sets(), sqlFns));
        AggregationPlan fedPlan = plan.with(plan.sets(), fedFns);
        Set<String> used = new LinkedHashSet<>(plan.filters().keySet());
        for (CellSet s : plan.sets()) {
            used.addAll(s.grouping());
            used.addAll(s.at().keySet());
            used.addAll(s.exclude().keySet());
        }
        List<String> dims = new ArrayList<>(used);
        dims.removeIf(d -> !plan.dims().containsKey(d));
        List<Object> params = new ArrayList<>();
        String base = GroupingSetsCompiler.base(fedPlan, rel, types, dims, params, List.of());
        List<String> where = new ArrayList<>();
        for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
            String col = "\"d" + dims.indexOf(f.getKey()) + "\"";
            if (!f.getValue().in().isEmpty()) {
                where.add(col + " IN (" + String.join(", ", java.util.Collections.nCopies(f.getValue().in().size(), "?")) + ")");
                params.addAll(f.getValue().in());
            }
            if (!f.getValue().notIn().isEmpty()) {
                where.add(col + " NOT IN (" + String.join(", ", java.util.Collections.nCopies(f.getValue().notIn().size(), "?")) + ")");
                params.addAll(f.getValue().notIn());
            }
        }
        String q = "SELECT * FROM (" + base + ") AS \"b\"" + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where));
        int inputs = fedFns.stream().mapToInt(f -> f.inputs().size()).sum();
        Map<CellSet, Map<CellKey, Pending>> cells = new LinkedHashMap<>();
        plan.sets().forEach(s -> cells.put(s, new HashMap<>()));
        long[] rowsRead = {0};
        exec.query(q, params, rs -> {
            String[] d = new String[dims.size()];
            while (rs.next()) {
                rowsRead[0]++;
                for (int i = 0; i < d.length; i++) {
                    d[i] = rs.getString(i + 1);
                }
                Object[] in = new Object[inputs];
                for (int i = 0; i < inputs; i++) {
                    in[i] = rs.getObject(dims.size() + 1 + i);
                }
                for (CellSet s : plan.sets()) {
                    if (!member(s, dims, d)) {
                        continue;
                    }
                    Map<String, String> grouped = new TreeMap<>();
                    for (String g : s.grouping()) {
                        grouped.put(g, d[dims.indexOf(g)]);
                    }
                    Pending p = cells.get(s).computeIfAbsent(s.key(grouped), k -> {
                        Pending np = new Pending(fedFns.size());
                        for (int f = 0; f < fedFns.size(); f++) {
                            np.states[f] = fedFns.get(f).fn().start();
                        }
                        return np;
                    });
                    p.seen++;
                    if (p.refused != null) {
                        continue;
                    }
                    p.rows.add(in);
                    if (p.rows.size() >= batchRows) {
                        feed(fedFns, p, false, holisticMaxRows);
                    }
                }
            }
            return null;
        });
        List<Partials.SetResult> out = new ArrayList<>();
        Map<CellSet, Partials.SetResult> sqlSets = new HashMap<>();
        bySql.sets().forEach(r -> sqlSets.put(r.set(), r));
        for (CellSet s : plan.sets()) {
            Map<CellKey, Pending> mine = cells.get(s);
            TreeMap<CellKey, Partials.Entry> merged = new TreeMap<>();
            Partials.SetResult r = sqlSets.get(s);
            Set<CellKey> keys = new LinkedHashSet<>(mine.keySet());
            if (r != null) {
                keys.addAll(r.cells().keySet());
            }
            for (CellKey k : keys) {
                Partials.Entry e = r == null ? null : r.cells().get(k);
                Pending p = mine.get(k);
                if (p != null) {
                    feed(fedFns, p, true, holisticMaxRows);
                }
                Object[] states = new Object[plan.functions().size()];
                Map<Integer, String> notes = new HashMap<>(e == null ? Map.of() : remap(e.notes(), sqlIdx));
                for (int j = 0; j < sqlIdx.size(); j++) {
                    states[sqlIdx.get(j)] = e == null ? sqlFns.get(j).fn().start() : e.states()[j];
                }
                for (int j = 0; j < fed.size(); j++) {
                    AggFunction<Object> fn = fedFns.get(j).fn();
                    states[fed.get(j)] = p == null ? fn.start() : p.states[j];
                    if (p != null && p.refused != null && fn.kind() == Kind.HOLISTIC) {
                        notes.put(fed.get(j), p.refused);
                    }
                }
                long members = e != null ? e.members() : p == null ? 0 : p.seen;
                merged.put(k, new Partials.Entry(members, states, e == null ? null : e.scope(), notes));
            }
            out.add(new Partials.SetResult(s, merged));
        }
        List<String> statements = new ArrayList<>(bySql.stats().statements());
        statements.add(q);
        return new Partials(out, new Partials.Stats(bySql.stats().queries() + 1, statements, (System.nanoTime() - t0) / 1_000_000,
                bySql.stats().rowsRead() < 0 ? rowsRead[0] : bySql.stats().rowsRead() + rowsRead[0]));
    }

    private static Map<Integer, String> remap(Map<Integer, String> notes, List<Integer> idx) {
        Map<Integer, String> out = new HashMap<>();
        notes.forEach((k, v) -> out.put(idx.get(k), v));
        return out;
    }

    private static boolean member(CellSet s, List<String> dims, String[] d) {
        for (Map.Entry<String, String> a : s.at().entrySet()) {
            if (!a.getValue().equals(d[dims.indexOf(a.getKey())])) {
                return false;
            }
        }
        for (Map.Entry<String, List<String>> ex : s.exclude().entrySet()) {
            if (ex.getValue().contains(d[dims.indexOf(ex.getKey())])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Feeds a cell's waiting rows: a mergeable or sketch function batch by batch; a holistic one only at the end, all of the cell's rows in
     * one call, refused for the cell past its bound.
     */
    private static void feed(List<FunctionCall> fns, Pending p, boolean last, long holisticMaxRows) {
        if (p.rows.isEmpty() || p.refused != null) {
            p.rows.clear();
            return;
        }
        boolean anyHolistic = fns.stream().anyMatch(f -> f.fn().kind() == Kind.HOLISTIC);
        if (anyHolistic && holisticMaxRows > 0 && p.seen > holisticMaxRows) {
            p.refused = "refused for this cell: " + p.seen + " rows, more than the " + holisticMaxRows + " a holistic custom function receives "
                    + "(drishti.pivot.functions.holistic-max-rows); filter the view, or use a mergeable or sketch form";
            p.rows.clear();
            return;
        }
        if (anyHolistic && !last) {
            return;                                           // a holistic function waits for all of the cell's rows
        }
        int k = 0;
        for (int f = 0; f < fns.size(); f++) {
            FunctionCall fc = fns.get(f);
            Object[] cols = new Object[fc.inputs().size()];
            for (int c = 0; c < cols.length; c++, k++) {
                if (fc.fn().numeric()) {
                    double[] x = new double[p.rows.size()];
                    for (int r = 0; r < x.length; r++) {
                        Object o = p.rows.get(r)[k];
                        x[r] = o instanceof Number n ? n.doubleValue() : o == null ? Double.NaN : parse(String.valueOf(o));
                    }
                    cols[c] = x;
                } else {
                    String[] x = new String[p.rows.size()];
                    for (int r = 0; r < x.length; r++) {
                        Object o = p.rows.get(r)[k];
                        x[r] = o == null ? null : String.valueOf(o);
                    }
                    cols[c] = x;
                }
            }
            p.states[f] = fc.fn().add(p.states[f], new Batch(p.rows.size(), cols));
        }
        p.rows.clear();
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
