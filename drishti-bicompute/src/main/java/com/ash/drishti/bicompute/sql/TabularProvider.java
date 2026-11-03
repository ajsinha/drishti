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
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The tabular provider (PIVOT_ENGINE.md section 6.2): any table of any pack, read by DuckDB. Cell sets that share a restriction compile to
 * one {@code GROUP BY GROUPING SETS} statement each ({@link GroupingSetsCompiler}); a statement answers states (sums and counts, Welford's
 * parts, a holistic figure of each set's own rows), which the core merges and finishes. Row rules and masks are the source's: a masked field
 * is not in the catalogue, and the row rules are applied inside the base relation before any filter or grouping.
 */
public final class TabularProvider implements PivotProvider {

    /** Rows a second one DuckDB connection scans in memory, for the estimate (measured scans replace it in phase P3). */
    static final double ROWS_PER_SECOND = 50_000_000;
    /** Bytes a second one connection reads from a local disk, for an estimate without a row count. */
    static final double BYTES_PER_SECOND = 500_000_000;

    private final DuckDbExecutor exec;
    private final TabularSource.Resolver resolver;
    private volatile int feedBatchRows = 65_536;
    private volatile long feedHolisticMaxRows = 5_000_000L;

    public TabularProvider(DuckDbExecutor exec, TabularSource.Resolver resolver) {
        this.exec = exec;
        this.resolver = resolver;
    }

    public DuckDbExecutor executor() {
        return exec;
    }

    /** The resolver of its sources (what-if overlays wrap it). */
    public TabularSource.Resolver resolver() {
        return resolver;
    }

    @Override
    public String id() {
        return "tabular";
    }

    @Override
    public boolean serves(String source) {
        return resolver.serves(source);
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        return resolver.sources(v);
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, true, true);
    }

    @Override
    public Catalogue catalogue(Viewer v, String source, String date) {
        TabularSource s = resolver.open(v, source, date);
        return new Catalogue(s.id(), s.title(), s.fields(), List.of(), s.date(), s.dates(), s.version(), s.masked(), s.notes());
    }

    @Override
    public String version(Viewer v, String source, String date) {
        return resolver.open(v, source, date).version();
    }

    @Override
    public Estimate estimate(Viewer v, AggregationPlan plan) {
        TabularSource s = resolver.open(v, plan.source(), plan.date());
        java.util.Set<String> cols = new java.util.LinkedHashSet<>();
        plan.dims().values().forEach(d -> cols.add(d.field()));
        plan.functions().forEach(f -> cols.addAll(f.inputs()));
        int of = s.fields().size();
        long rows = s.rows();
        long bytes = s.bytes() < 0 || of == 0 ? -1 : s.bytes() * Math.max(1, cols.size()) / of;
        double secs = rows >= 0 ? rows / ROWS_PER_SECOND : bytes >= 0 ? bytes / BYTES_PER_SECOND : 0;
        return new Estimate(rows, bytes, cols.size(), of, secs, rows < 0 && bytes < 0 ? "the source cannot say how many rows it holds without reading them"
                : "the source's row count; " + cols.size() + " of " + of + " columns read; " + (long) ROWS_PER_SECOND / 1_000_000
                        + " million rows a second per connection");
    }

    @Override
    public Partials evaluate(Viewer v, AggregationPlan plan) {
        List<Integer> fed = RowFed.fed(plan);
        if (!fed.isEmpty()) {
            TabularSource s = resolver.open(v, plan.source(), plan.date());
            boolean python = fed.stream().anyMatch(i -> plan.functions().get(i).fn() instanceof com.ash.drishti.bicompute.python.PythonFunction);
            if (python && Boolean.FALSE.equals(s.notes().get(TabularSource.USER_CODE))) {
                throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_OFF, s.id() + " is memory-only data: Python custom functions do not read it "
                        + "(user code is off for memory-only stores); use a formula, an SQL macro or an approved Java plug-in");
            }
            return RowFed.evaluate(plan, fed, p -> evaluateSql(v, p), exec, s.relation(exec), types(s), feedBatchRows, feedHolisticMaxRows);
        }
        return evaluateSql(v, plan);
    }

    /**
     * How functions without an SQL form (Python, Java plug-ins) are fed (PIVOT_ENGINE.md sections 8.4 and 8.5): rows in each batch, and the
     * most rows of one cell a holistic one receives.
     */
    public void feed(int batchRows, long holisticMaxRows) {
        this.feedBatchRows = Math.max(1, batchRows);
        this.feedHolisticMaxRows = holisticMaxRows;
    }

    private Partials evaluateSql(Viewer v, AggregationPlan plan) {
        long t0 = System.nanoTime();
        TabularSource s = resolver.open(v, plan.source(), plan.date());
        Map<String, String> types = types(s);
        TabularSource.Relation rel = s.relation(exec);
        List<GroupingSetsCompiler.Compiled> stmts = GroupingSetsCompiler.compile(plan, rel, types);
        Map<CellSet, TreeMap<CellKey, Partials.Entry>> out = new LinkedHashMap<>();
        plan.sets().forEach(set -> out.put(set, new TreeMap<>()));
        List<String> sql = new ArrayList<>();
        for (GroupingSetsCompiler.Compiled c : stmts) {
            sql.add(c.sql());
            read(plan, c, out);
        }
        List<Partials.SetResult> sets = new ArrayList<>();
        for (CellSet set : plan.sets()) {
            sets.add(new Partials.SetResult(set, out.get(set)));
        }
        return new Partials(sets, new Partials.Stats(stmts.size(), sql, (System.nanoTime() - t0) / 1_000_000, -1));
    }

    private static Map<String, String> types(TabularSource s) {
        Map<String, String> types = new LinkedHashMap<>();
        s.fields().forEach(f -> types.put(f.name(), f.type()));
        return types;
    }

    private void read(AggregationPlan plan, GroupingSetsCompiler.Compiled c, Map<CellSet, TreeMap<CellKey, Partials.Entry>> out) {
        int nd = c.dims().size();
        // the sets of this statement by their grouped dimensions (the GROUPING() bitmask)
        Map<Long, List<CellSet>> byMask = new HashMap<>();
        for (CellSet set : c.sets()) {
            long mask = 0;
            for (int i = 0; i < nd; i++) {
                if (!set.grouping().contains(c.dims().get(i))) {
                    mask |= 1L << (nd - 1 - i);
                }
            }
            byMask.computeIfAbsent(mask, k -> new ArrayList<>()).add(set);
        }
        exec.query(c.sql(), c.params(), rs -> {
            int rows = 0;
            while (rs.next()) {
                if (plan.maxGroups() > 0 && ++rows > plan.maxGroups()) {
                    throw new DrishtiException(ErrorCode.PIVOT_TOO_MANY_CELLS, "the view's groups run past " + plan.maxGroups()
                            + " in one statement: filter it, collapse a level or show the top N of a level; a view is never cut short");
                }
                String[] vals = new String[nd];
                for (int i = 0; i < nd; i++) {
                    vals[i] = rs.getString(i + 1);
                }
                long mask = rs.getLong(nd + 1);
                long members = rs.getLong(nd + 2);
                List<CellSet> sets = byMask.get(mask);
                if (sets == null) {
                    continue;
                }
                Object[] states = null;
                for (CellSet set : sets) {
                    boolean match = true;
                    for (Map.Entry<String, String> at : set.at().entrySet()) {
                        if (!at.getValue().equals(vals[c.dims().indexOf(at.getKey())])) {
                            match = false;
                            break;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                    if (states == null) {
                        states = states(plan, rs, nd + 3);
                    }
                    Map<String, String> grouped = new TreeMap<>();
                    for (String d : set.grouping()) {
                        grouped.put(d, vals[c.dims().indexOf(d)]);
                    }
                    out.get(set).put(set.key(grouped), new Partials.Entry(members, states));
                }
            }
            return null;
        });
    }

    private static Object[] states(AggregationPlan plan, java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        Object[] states = new Object[plan.functions().size()];
        int k = col;
        for (int f = 0; f < plan.functions().size(); f++) {
            FunctionCall fc = plan.functions().get(f);
            int n = fc.fn().sqlState(fc.inputs().stream().map(x -> "x").toList()).size();
            Object[] parts = new Object[n];
            for (int j = 0; j < n; j++) {
                parts[j] = rs.getObject(k++);
            }
            states[f] = fc.fn().fromSql(parts);
        }
        return states;
    }

    @Override
    public Rows drill(Viewer v, AggregationPlan plan, CellKey key, int offset, int limit) {
        TabularSource s = resolver.open(v, plan.source(), plan.date());
        Map<String, String> types = types(s);
        TabularSource.Relation rel = s.relation(exec);
        Map<String, String> coords = new LinkedHashMap<>(key.coords());
        GroupingSetsCompiler.Compiled q = GroupingSetsCompiler.drill(plan, rel, types, s.fields(), coords, offset, limit, false);
        GroupingSetsCompiler.Compiled n = GroupingSetsCompiler.drill(plan, rel, types, s.fields(), coords, 0, 0, true);
        List<String> names = s.fields().stream().map(Field::name).toList();
        List<List<Object>> rows = exec.query(q.sql(), q.params(), rs -> {
            List<List<Object>> r = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(names.size());
                for (int i = 0; i < names.size(); i++) {
                    row.add(rs.getObject(i + 1));
                }
                r.add(row);
            }
            return r;
        });
        long total = exec.query(n.sql(), n.params(), rs -> rs.next() ? rs.getLong(1) : 0L);
        return new Rows(names, rows, total, s.masked() > 0 ? s.masked() + " masked column(s) left out" : "");
    }

    @Override
    public Optional<String> scopeOf(Viewer v, AggregationPlan plan, CellKey key) {
        return Optional.of(GroupingSetsCompiler.words(key.coords()));
    }
}
