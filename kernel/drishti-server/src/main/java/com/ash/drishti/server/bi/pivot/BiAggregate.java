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
package com.ash.drishti.server.bi.pivot;

import com.ash.drishti.kernel.api.pivot.AggregationPlan;
import com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall;
import com.ash.drishti.kernel.api.pivot.CellKey;
import com.ash.drishti.kernel.api.pivot.CellSet;
import com.ash.drishti.kernel.api.pivot.Dim;
import com.ash.drishti.bicompute.formula.FormulaFunction;
import com.ash.drishti.bicompute.sql.GroupingSetsCompiler;
import com.ash.drishti.bicompute.sql.TabularSource;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * BI's totals and subtotals on the pivot engine (ARCHITECTURE.md section 26.9; ARCHITECTURE.md §28, totals): every level a matrix, a table or a
 * model query asks for, for every measure that is a pivot function, in <b>one</b> grouping-sets statement of the engine's
 * function states over the model's rows as the reader may see them. The rows are the model's own detail query (row rules, masks, joins,
 * layers and dates applied by BI's compiler), never run on its own: it is the FROM item of the engine's statement, which runs in BI's
 * engine where the layers are. Each level's figure is made from its own rows' states (a ratio, an average, a distinct count, a percentile
 * are right at every level; nothing is summed from the figures shown).
 *
 * <p>Measures that are not pivot functions (time intelligence, running, context, Python) get no total; the answer names them.
 */
public final class BiAggregate {

    /**
     * The totals of one level.
     *
     * @param by the fields grouped (empty: the grand total)
     * @param measures the measures, in order
     * @param masked the measures masked for the reader (their figures null)
     * @param rows per group, the grouped values then the measures' figures
     */
    public record Level(List<String> by, List<String> measures, List<String> masked, List<List<Object>> rows) {

        /** The shape a report's answer carries: {@code {by, columns, masked, rows}}. */
        public Map<String, Object> toReport() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("by", by);
            List<String> cols = new ArrayList<>(by);
            cols.addAll(measures);
            m.put("columns", cols);
            m.put("masked", masked);
            m.put("rows", rows);
            return m;
        }

        /** The shape a model query's answer carries: {@code {by, measures, rows}}. */
        public Map<String, Object> toMq() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("by", by);
            m.put("measures", measures);
            m.put("rows", rows);
            return m;
        }
    }

    /**
     * The answer: each level, and the measures not totalled with why.
     *
     * @param levels in the order asked
     * @param notTotalled measure to the reason it has no total
     */
    public record Totals(List<Level> levels, Map<String, String> notTotalled) {}

    private BiAggregate() {}

    /**
     * The totals of {@code levels} for {@code measures} under {@code base}'s filters and dates (measure filters left out: a having filters
     * groups, not totals). {@code run} asks the model's detail query as the reader (under {@link QueryService#aggregating}).
     */
    public static Totals totals(Dataset ds, List<List<String>> levels, List<String> measures, QueryRequest base, int maxGroups,
            Function<QueryRequest, QueryService.Result> run) {
        Map<String, String> skipped = new LinkedHashMap<>();
        List<BiMeasures.Translated> fns = new ArrayList<>();
        for (String m : measures) {
            try {
                fns.add(BiMeasures.translate(ds, m));
            } catch (BiMeasures.NotPivot e) {
                skipped.put(m, e.getMessage());
            }
        }
        List<List<String>> wanted = new ArrayList<>();
        for (List<String> l : levels) {
            if (!wanted.contains(l)) {
                wanted.add(List.copyOf(l));
            }
        }
        if (fns.isEmpty() || wanted.isEmpty()) {
            return new Totals(List.of(), skipped);
        }
        // one statement per fact: a measure aggregates its own fact's rows (a model's measures of two facts never share one row set)
        Map<String, List<BiMeasures.Translated>> byFact = new LinkedHashMap<>();
        for (BiMeasures.Translated t : fns) {
            byFact.computeIfAbsent(t.tables().isEmpty() ? "" : t.tables().iterator().next(), k -> new ArrayList<>()).add(t);
        }
        if (byFact.size() > 1 && byFact.containsKey("")) {
            List<BiMeasures.Translated> constants = byFact.remove("");
            byFact.values().iterator().next().addAll(constants);
        }
        List<QueryRequest.Filter> filters = base.filters().stream().filter(f -> !ds.measures().containsKey(f.field())).toList();
        List<List<Level>> parts = new ArrayList<>();
        for (List<BiMeasures.Translated> group : byFact.values()) {
            Set<String> fields = new LinkedHashSet<>();
            wanted.forEach(fields::addAll);
            group.forEach(t -> fields.addAll(t.fields()));
            QueryRequest detail = new QueryRequest(new ArrayList<>(fields), List.of(), filters, base.from(), base.to(), List.of(), null, base.layer(),
                    true);
            Statement st = new Statement(wanted, group, maxGroups);
            QueryService.Result r = QueryService.aggregating(st, () -> run.apply(detail));
            parts.add(st.read(r));
        }
        return new Totals(join(parts, fns.stream().map(BiMeasures.Translated::measure).toList()), skipped);
    }

    /** The levels of each fact's statement side by side: per level, per group, the measures in the order asked (a group a fact lacks: empty). */
    static List<Level> join(List<List<Level>> parts, List<String> order) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        Map<List<String>, Map<List<Object>, Map<String, Object>>> rows = new LinkedHashMap<>();
        Map<List<String>, Set<String>> masked = new LinkedHashMap<>();
        for (List<Level> part : parts) {
            for (Level l : part) {
                Map<List<Object>, Map<String, Object>> byKey = rows.computeIfAbsent(l.by(), k -> new LinkedHashMap<>());
                masked.computeIfAbsent(l.by(), k -> new LinkedHashSet<>()).addAll(l.masked());
                for (List<Object> r : l.rows()) {
                    List<Object> key = r.subList(0, l.by().size());
                    Map<String, Object> vals = byKey.computeIfAbsent(new ArrayList<>(key), k -> new LinkedHashMap<>());
                    for (int i = 0; i < l.measures().size(); i++) {
                        vals.put(l.measures().get(i), r.get(l.by().size() + i));
                    }
                }
            }
        }
        List<Level> out = new ArrayList<>();
        rows.forEach((by, byKey) -> {
            List<List<Object>> rs = new ArrayList<>();
            byKey.forEach((key, vals) -> {
                List<Object> row = new ArrayList<>(key);
                order.forEach(m -> row.add(vals.get(m)));
                rs.add(row);
            });
            rs.sort(Comparator.comparing((List<Object> x) -> Statement.key(x, by.size())));
            out.add(new Level(by, order, List.copyOf(masked.get(by)), rs));
        });
        return out;
    }

    /** The engine's statement over the detail query, and the reading of its answer. */
    static final class Statement implements QueryService.Outer {
        final List<List<String>> levels;
        final List<BiMeasures.Translated> measures;
        final int maxGroups;
        List<String> dims = List.of();
        List<CellSet> sets = List.of();
        List<FunctionCall> calls = List.of();
        final Set<String> maskedFields = new TreeSet<>();

        Statement(List<List<String>> levels, List<BiMeasures.Translated> measures, int maxGroups) {
            this.levels = levels;
            this.measures = measures;
            this.maxGroups = Math.max(1, maxGroups);
        }

        @Override
        public String signature() {
            return "levels=" + levels + ";measures=" + measures.stream().map(m -> m.measure() + "=" + m.formula()).toList() + ";max=" + maxGroups;
        }

        @Override
        public int maxRows() {
            return maxGroups;
        }

        /** Never from a cache: {@link #read} needs the plan this statement's {@link #wrap} makes (an answer served again skips it). */
        @Override
        public boolean cacheable() {
            return false;
        }

        @Override
        public SqlCompiler.Compiled wrap(SqlCompiler.Compiled detail) {
            Map<String, String> types = new LinkedHashMap<>();
            for (SqlCompiler.Out o : detail.columns()) {
                if (o.masked()) {
                    maskedFields.add(o.name());
                } else {
                    types.put(o.name(), "string");
                }
            }
            Map<String, Dim> dimMap = new LinkedHashMap<>();
            List<CellSet> cs = new ArrayList<>();
            for (List<String> l : levels) {
                if (l.stream().anyMatch(maskedFields::contains)) {
                    continue;                                            // a masked field is never grouped: that level is not made
                }
                l.forEach(f -> dimMap.putIfAbsent(f, new Dim(f, null)));
                cs.add(CellSet.of(l, Map.of()));
            }
            List<FunctionCall> fc = new ArrayList<>();
            for (BiMeasures.Translated t : measures) {
                List<String> in = new ArrayList<>(t.fields());
                fc.add(new FunctionCall(t.measure(), t.measure(), FormulaFunction.of(t.measure(), t.formula(), in), in));
            }
            this.sets = cs;
            this.calls = fc;
            AggregationPlan plan = new AggregationPlan("bi", null, null, Map.of(), dimMap, fc, cs.isEmpty() ? List.of(CellSet.of(List.of(), Map.of()))
                    : cs, null, maxGroups, null);
            // the rows' order and the compiler's row cap mean nothing under a GROUP BY: dropped, so the engine never sorts the model's rows
            String rows = detail.sql().replaceFirst("(?s) ORDER BY [^?']*? LIMIT \\d+$", "").replaceFirst(" LIMIT " + Integer.MAX_VALUE + "$", "");
            TabularSource.Relation rel = new TabularSource.Relation("(" + rows + ")", detail.params(), null, List.of());
            List<GroupingSetsCompiler.Compiled> stmts = GroupingSetsCompiler.compile(plan, rel, types);
            GroupingSetsCompiler.Compiled c = stmts.get(0);     // the sets share one shape (no restriction): one statement
            this.dims = c.dims();
            List<SqlCompiler.Out> outs = new ArrayList<>();
            for (int i = 0; i < c.dims().size(); i++) {
                outs.add(new SqlCompiler.Out("d" + i, false, false, null));
            }
            outs.add(new SqlCompiler.Out("_g", false, false, null));
            outs.add(new SqlCompiler.Out("_n", false, false, null));
            for (int i = 0; i < c.stateColumns(); i++) {
                outs.add(new SqlCompiler.Out("s" + i, true, false, null));
            }
            return new SqlCompiler.Compiled(c.sql(), c.params(), outs, detail.tables(), detail.base(), detail.notes());
        }

        /** The answer's rows dealt to their levels, each state finished. */
        List<Level> read(QueryService.Result r) {
            if (calls.isEmpty() && !measures.isEmpty()) {
                throw new IllegalStateException("the totals' statement was not made for this answer (a cached answer has no plan)");
            }
            int nd = dims.size();
            Map<Long, CellSet> byMask = new LinkedHashMap<>();
            for (CellSet s : sets) {
                long mask = 0;
                for (int i = 0; i < nd; i++) {
                    if (!s.grouping().contains(dims.get(i))) {
                        mask |= 1L << (nd - 1 - i);
                    }
                }
                byMask.put(mask, s);
            }
            List<String> names = measures.stream().map(BiMeasures.Translated::measure).toList();
            List<String> masked = measures.stream().filter(m -> m.fields().stream().anyMatch(maskedFields::contains))
                    .map(BiMeasures.Translated::measure).toList();
            Map<CellSet, List<List<Object>>> rows = new LinkedHashMap<>();
            sets.forEach(s -> rows.put(s, new ArrayList<>()));
            List<ArrowIpcWriter.Column> cols = r.columns();
            if (r.rows() > maxGroups) {
                throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.QUERY_ROWS, "the totals have more than " + maxGroups
                        + " groups: group by less or filter; a total is never cut short");
            }
            for (int row = 0; row < r.rows(); row++) {
                long mask = ((Number) cols.get(nd).values()[row]).longValue();
                CellSet s = byMask.get(mask);
                if (s == null) {
                    continue;
                }
                List<Object> out = new ArrayList<>();
                for (String f : s.grouping()) {
                    Object v = cols.get(dims.indexOf(f)).values()[row];
                    out.add(v == null || CellKey.BLANK.equals(String.valueOf(v)) ? null : String.valueOf(v));
                }
                int k = nd + 2;
                for (FunctionCall fc : calls) {
                    int n = fc.fn().sqlState(fc.inputs().stream().map(x -> "x").toList()).size();
                    Object[] parts = new Object[n];
                    for (int j = 0; j < n; j++) {
                        parts[j] = cols.get(k++).values()[row];
                    }
                    Double v = masked.contains(fc.id()) ? null : fc.fn().finish(fc.fn().fromSql(parts));
                    out.add(v);
                }
                rows.get(s).add(out);
            }
            List<Level> out = new ArrayList<>();
            for (List<String> l : levels) {
                CellSet s = sets.stream().filter(x -> x.grouping().equals(l)).findFirst().orElse(null);
                if (s == null) {
                    continue;
                }
                List<List<Object>> rs = rows.get(s);
                int width = l.size();
                rs.sort(Comparator.comparing((List<Object> x) -> key(x, width)));
                out.add(new Level(l, names, masked, rs));
            }
            return out;
        }

        static String key(List<Object> row, int width) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < width; i++) {
                b.append(row.get(i) == null ? "￿" : String.valueOf(row.get(i))).append('\u0001');
            }
            return b.toString();
        }
    }

    /**
     * The levels of a matrix with subtotals: the grand total, every group of the first row fields (the first, the first two, ... all), and
     * with a column field each of those per column.
     */
    public static List<List<String>> matrixLevels(List<String> rows, List<String> cols) {
        List<List<String>> by = new ArrayList<>();
        by.add(List.of());
        for (int depth = 1; depth < rows.size(); depth++) {
            by.add(rows.subList(0, depth));
        }
        List<List<String>> withCol = new ArrayList<>();
        if (!cols.isEmpty()) {
            for (List<String> b : by) {
                List<String> c = new ArrayList<>(b);
                c.add(cols.get(0));
                withCol.add(c);
            }
            by.addAll(withCol);
            by.add(rows);                                           // each row across the columns
        }
        return by.stream().map(List::copyOf).toList();
    }
}
