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
package com.ash.drishti.server.bi.plan;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.engine.Dialect;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import com.ash.drishti.server.bi.expr.Expressions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stages 1 to 6 of the query pipeline (docs/architecture/BI_DESIGN.md section 4.1) after the language front ends: a bound question becomes the logical plan.
 * Bind (the question's names and the engine's choice of tables: the fact of each measure, the dimensions its fields and filters reach, the dimensions whose
 * security reaches the facts), grain split (measures of different facts are aggregated each at its own grain, then met on the shared fields), then
 * {@link SecurityRewriter} (stage 4, before any optimisation), {@link Guards} (stage 5) and the {@link Optimiser} (stage 6). Pure; one call per question.
 */
public final class Planner {

    /** A dataset alias's identity in the plan: the dataset's name and version (a version-1 file's table: the file's name). */
    public record Source(String dataset, String version) {}

    /**
     * What the planner needs, from whichever language asked.
     *
     * @param flat the model flattened for the engine (with the question's local and guard measures)
     * @param version the model's fingerprint (model and datasets texts)
     * @param main the engine request the language compiled (fields, measures, resolved filters, dates, sort, limit)
     * @param rowConditions the question's own row filters before data-dependent resolution ({@code top} stays {@code top}: the plan never holds data)
     * @param having the filters on measures
     * @param time the resolved time ({@link LogicalPlan#time})
     * @param ids the stable ids of fields and measures (canonical names), where the model has them
     * @param hidden the measures the answer drops (the currency guard's)
     */
    public record Input(Dataset flat, String model, String version, Map<String, Source> sources, QueryRequest main, List<PlanNode.Cond> rowConditions,
            List<PlanNode.Cond> having, String time, boolean rows, List<List<String>> totals, Map<String, String> ids, Set<String> hidden, Map<String, Object> binding) {

        public Input {
            binding = binding == null ? Map.of() : binding;
        }

        public Input(Dataset flat, String model, String version, Map<String, Source> sources, QueryRequest main, List<PlanNode.Cond> rowConditions,
                List<PlanNode.Cond> having, String time, boolean rows, List<List<String>> totals, Map<String, String> ids, Set<String> hidden) {
            this(flat, model, version, sources, main, rowConditions, having, time, rows, totals, ids, hidden, Map.of());
        }
    }

    private Planner() {}

    /** The optimised, secured plan of a question for a reader. */
    public static LogicalPlan plan(Input in, SecuritySignature.Reader reader) {
        LogicalPlan raw = bind(in, reader);
        LogicalPlan secured = SecurityRewriter.rewrite(raw, in.flat(), reader);
        return Optimiser.optimise(secured, in.flat());
    }

    /** Stage 3: the question bound to the model's datasets, one aggregate per fact grain. */
    static LogicalPlan bind(Input in, SecuritySignature.Reader reader) {
        QueryRequest q = in.main();
        Dataset ds = in.flat();
        List<String> fields = q.fields();
        List<String> measures = q.measures().stream().filter(m -> !in.hidden().contains(m)).toList();
        List<Part> parts = parts(in, reader);
        List<PlanNode> aggregates = new ArrayList<>();
        for (Part p : parts) {
            List<PlanNode> scans = new ArrayList<>();
            for (String t : p.tables()) {
                Source s = in.sources().getOrDefault(t, new Source(ds.name(), Integer.toString(ds.version())));
                scans.add(shaped(ds.tables().get(t), new PlanNode.SecureScan(t, s.dataset(), s.version(), scanTime(ds.tables().get(t), in.time()), List.of(), List.of(), List.of(), List.of(), List.of(), false)));
            }
            List<PlanNode.Cond> conds = in.rowConditions().stream().filter(c -> p.tables().contains(alias(c.field()))).toList();
            PlanNode source = new PlanNode.Filter(scans, conds);
            List<String> mine = p.measures().stream().filter(m -> measures.contains(m) || in.hidden().contains(m)).toList();   // the guard's too: the rollup must hold them
            aggregates.add(new PlanNode.Aggregate(List.of(source), p.fact(), fields, mine, null));
            for (String m : mine) {
                aggregates.addAll(ContextNodes.of(ds, m, scans, in.rowConditions().stream().filter(c -> p.tables().contains(alias(c.field()))).toList(), p.fact(), fields));
            }
        }
        PlanNode node = aggregates.size() == 1 ? aggregates.get(0) : new PlanNode.Meet(aggregates, fields);
        List<String> windows = measures.stream().filter(m -> windowed(ds, m, new LinkedHashSet<>())).toList();
        if (!windows.isEmpty()) {
            node = new PlanNode.Window(node, windows);
        }
        if (!in.having().isEmpty()) {
            node = new PlanNode.Having(node, in.having());
        }
        if (!q.orderBy().isEmpty()) {
            node = new PlanNode.Sort(node, q.orderBy().stream().map(s -> s.by() + (s.desc() ? " desc" : " asc")).toList());
        }
        if (q.limit() != null) {
            node = new PlanNode.Limit(node, q.limit());
        }
        List<String> columns = new ArrayList<>(fields);
        columns.addAll(measures);
        node = new PlanNode.Answer(node, columns, in.rows() ? "rows" : "grouped", in.binding());
        return new LogicalPlan(node, in.model(), in.version(), in.time(), in.totals(), in.ids());
    }

    /**
     * The time a scan is bound to (section 4.5, mixed modes): a live table asked live or for the current state reads its in-memory state ({@code live}); a
     * snapshot table asked live reads the current business date ({@code current}); an as-of date or a window binds every scan alike.
     */
    static String scanTime(Dataset.Table t, String time) {
        if (t == null || time.startsWith("as-of ") || time.startsWith("window ") || time.startsWith("last ")) {
            return time;
        }
        if (t.isLive()) {
            return "live";
        }
        return "live".equals(time) ? "current" : time;
    }

    /** A dataset's own derivation above its scan (section 3.6): de-duplication, derived columns, grouping, as the engine applies them. */
    static PlanNode shaped(Dataset.Table t, PlanNode.SecureScan scan) {
        if (t == null) {
            return scan;
        }
        PlanNode node = scan;
        Dataset.Shape shape = t.shape();
        if (shape != null && shape.dedup()) {
            node = new PlanNode.Dedup(node, shape.dedupKeys(), (shape.latest() ? "latest by " : "earliest by ") + shape.dedupBy());
        }
        Map<String, String> derived = new LinkedHashMap<>();
        t.calculated().forEach(c -> derived.put(c.name(), c.expr()));
        t.columns().values().stream().filter(c -> c.ref() != null).forEach(c -> derived.put(c.name(), "related(" + c.ref() + ")"));
        if (!derived.isEmpty()) {
            node = new PlanNode.Derive(node, derived);
        }
        if (shape != null && shape.grouped()) {
            node = new PlanNode.Regroup(node, shape.groupBy(), shape.groupColumns());
        }
        return node;
    }

    /** A fact part of the question: the fact (the base table), the tables it reads and the measures it computes. */
    record Part(String fact, Set<String> tables, List<String> measures) {}

    /** The engine's choice of tables (the fact of each measure, the dimensions it reaches, the dimensions whose security reaches it), part by part. */
    static List<Part> parts(Input in, SecuritySignature.Reader reader) {
        QueryRequest q = in.main();
        SqlCompiler.Reader r = new SqlCompiler.Reader(reader.enforce(), reader.roles(), reader.attributes(), reader.masks());
        try {
            SqlCompiler.Plan p = new SqlCompiler(in.flat(), Dialect.DUCKDB).plan(q, r);
            List<Part> out = new ArrayList<>();
            if (p.multiFact()) {
                for (SqlCompiler.Plan part : p.parts()) {
                    out.add(new Part(part.base(), new LinkedHashSet<>(part.tables()), part.measureNames()));
                }
            } else {
                out.add(new Part(p.base() == null ? p.tables().iterator().next() : p.base(), new LinkedHashSet<>(p.tables()), p.measureNames()));
            }
            return out;
        } catch (RuntimeException e) {                                          // the engine refuses it when it runs; the plan still says what was asked
            Set<String> tables = new LinkedHashSet<>();
            q.fields().forEach(f -> tables.add(alias(f)));
            q.measures().forEach(m -> tables.addAll(tablesOf(in.flat(), m, new LinkedHashSet<>())));
            String fact = tables.isEmpty() ? "" : tables.iterator().next();
            return List.of(new Part(fact, tables, q.measures()));
        }
    }

    /** The tables a measure reads (through the measures it names). */
    static Set<String> tablesOf(Dataset ds, String measure, Set<String> seen) {
        Set<String> out = new LinkedHashSet<>();
        Dataset.Measure m = ds.measures().get(measure);
        if (m == null || m.expr() == null || !seen.add(measure)) {
            return out;
        }
        try {
            Node.walk(Expressions.parse(m.expr()), n -> {
                if (n instanceof Node.Ref r && ds.tables().containsKey(r.parts().get(0))) {
                    out.add(r.parts().get(0));
                } else if (n instanceof Node.MeasureRef x) {
                    out.addAll(tablesOf(ds, x.name(), seen));
                }
            });
        } catch (RuntimeException e) {
            // a measure that does not parse reads nothing the plan can name
        }
        return out;
    }

    /** True when a measure (or one it names) computes over the answer's rows (a window, a running total). */
    static boolean windowed(Dataset ds, String measure, Set<String> seen) {
        Dataset.Measure m = ds.measures().get(measure);
        if (m == null || m.expr() == null || !seen.add(measure)) {
            return false;
        }
        Node n;
        try {
            n = Expressions.parse(m.expr());
        } catch (RuntimeException e) {
            return false;
        }
        return Node.anyMatch(n, x -> x instanceof Node.Call c && Node.isWindow(c.function()) || x instanceof Node.MeasureRef r && windowed(ds, r.name(), seen));
    }

    static String alias(String field) {
        int dot = field.indexOf('.');
        return dot < 0 ? field : field.substring(0, dot);
    }

    /** The ids map: name to id. */
    public static Map<String, String> ids(Map<String, String> idToName) {
        Map<String, String> out = new LinkedHashMap<>();
        idToName.forEach((id, name) -> out.put(name, id));
        return out;
    }
}
