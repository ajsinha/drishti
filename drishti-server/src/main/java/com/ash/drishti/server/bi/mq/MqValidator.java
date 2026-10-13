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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.engine.Dialect;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import com.ash.drishti.server.bi.engine.TableRules;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.report.RelativeDates;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Steps 2 to 10 of a model query's validation (docs/architecture/BI_MODEL_SPEC.md section 5.3), in order, before any data is read; the first step that fails
 * answers with every problem of that step, each with its path and, where one exists, a {@code fix}: 3 names ({@code DRS-9152}) and the contract
 * ({@code 9153}), 4 local measures ({@code 9154}), 5 filters ({@code 9155}), 6 reachability ({@code 9156}, by the engine's own planner), 7 time
 * ({@code 9157}), 8 the correctness guards (currency {@code 9158} and semi-additive {@code 9159} are warnings in wave W1; a row extract across two facts is
 * {@code 9160}), 9 security ({@code 9161}: a filter, sort or top on a field masked for the reader), 10 Python ({@code 9162}). Step 2 (the model) is the
 * catalogue's. The outcome feeds {@link MqCompiler}.
 */
public final class MqValidator {

    /** The reader's side of the checks: their masks, roles and attributes, and the date and calendar that relative windows are computed from. */
    public record Context(SqlCompiler.Masks masks, Set<String> roles, Map<String, List<Object>> attributes, boolean enforce, LocalDate today, BusinessCalendar calendar) {}

    /**
     * A query that passed: with names for ids, the dataset with its local measures, the fields after a drill and the drill's filters, the measures whose
     * groups the currency guard counts (measure to currency field), whether a semi-additive window is narrowed to its last date, and the warnings.
     */
    public record Checked(ModelQuery query, Dataset dataset, List<String> fields, List<QueryRequest.Filter> drillFilters, Map<String, String> currencyChecks,
            boolean lastDateOnly, List<String> warnings) {}

    private static final Set<String> NUMERIC = Set.of("integer", "decimal");
    private static final Set<String> MEASURE_OPS = Set.of("eq", "ne", "lt", "le", "gt", "ge");

    private MqValidator() {}

    public static Checked validate(ModelView v, ModelQuery query, Context ctx, MqProperties props) {
        // ---- 3 names and the contract
        ModelQuery q = resolveIds(v, query);
        Dataset base = v.flat();
        Resolver resolver = new Resolver(base);
        List<MqException.Problem> out = new ArrayList<>();
        Map<String, ModelDef.Meas> defs = v.measureDefs();
        for (int i = 0; i < q.fields().size(); i++) {
            field(resolver, base, q.fields().get(i), "select.fields[" + i + "]", out);
        }
        Set<String> measureNames = new LinkedHashSet<>(base.measures().keySet());
        measureNames.addAll(q.define().keySet());
        for (int i = 0; i < q.measures().size(); i++) {
            measure(measureNames, q.measures().get(i), "select.measures[" + i + "]", out);
        }
        for (int i = 0; i < q.where().size(); i++) {
            field(resolver, base, q.where().get(i).field(), "where[" + i + "].field", out);
            if ("top".equals(q.where().get(i).op()) && q.where().get(i).by() != null) {
                measure(measureNames, q.where().get(i).by(), "where[" + i + "].by", out);
            }
        }
        for (int i = 0; i < q.having().size(); i++) {
            measure(measureNames, q.having().get(i).measure(), "having[" + i + "].measure", out);
        }
        List<String> hierarchy = null;
        if (q.drill() != null) {
            hierarchy = base.hierarchies().get(q.drill().hierarchy());
            if (hierarchy == null) {
                out.add(new MqException.Problem("DRS-9152", "drill.hierarchy", "the model has no hierarchy '" + q.drill().hierarchy() + "'",
                        Map.of("replace", "drill.hierarchy", "with", List.copyOf(base.hierarchies().keySet()))));
            }
        }
        Set<String> selected = new LinkedHashSet<>(q.fields());
        selected.addAll(q.measures());
        for (int i = 0; i < q.sort().size(); i++) {
            String by = q.sort().get(i).by();
            if (!selected.contains(by)) {
                out.add(new MqException.Problem("DRS-9152", "sort[" + i + "].by", "'" + by + "' is not a field or measure the query selects",
                        Map.of("replace", "sort[" + i + "].by", "with", List.copyOf(selected))));
            }
        }
        fail(out);
        contract(v.def().contract(), q, defs, out);
        fail(out);

        // ---- 4 local measures
        Dataset ds = withDefine(base, q, com.ash.drishti.server.bi.layer.Flattener.measureIds(v.measureDefs()), out);
        fail(out);

        // ---- 5 filters
        List<String> fields = new ArrayList<>(q.fields());
        List<QueryRequest.Filter> drillFilters = new ArrayList<>();
        Resolver dr = new Resolver(ds);
        for (int i = 0; i < q.where().size(); i++) {
            filter(dr, ds, q.where().get(i), measureNames, "where[" + i + "]", out);
        }
        for (int i = 0; i < q.having().size(); i++) {
            ModelQuery.Having h = q.having().get(i);
            if (!MEASURE_OPS.contains(h.op()) || h.values().size() != 1 || !(h.values().get(0) instanceof Number)) {
                out.add(MqException.Problem.of("DRS-9155", "having[" + i + "]", "a having compares a measure to one number with eq, ne, lt, le, gt or ge"));
            }
        }
        if (hierarchy != null) {
            drill(q, hierarchy, fields, drillFilters, out);
        }
        if (q.totals() != null && "levels".equals(q.totals().mode())) {
            for (int i = 0; i < q.totals().levels().size(); i++) {
                for (String f : q.totals().levels().get(i)) {
                    if (!fields.contains(f)) {
                        out.add(MqException.Problem.of("DRS-9155", "totals.levels[" + i + "]", "a level of totals groups by fields of the query; '" + f + "' is not one"));
                    }
                }
            }
        }
        if (q.rows() && !q.measures().isEmpty()) {
            out.add(new MqException.Problem("DRS-9155", "select.measures", "a row extract (shape: rows) has no measures", Map.of("set", "shape", "to", "grouped")));
        }
        fail(out);

        // ---- 6 reachability, by the engine's planner
        reach(v, ds, q, fields, ctx, out);
        fail(out);

        // ---- 7 time
        time(ds, q, fields, ctx, props, out);
        fail(out);

        // ---- 8 the correctness guards
        List<String> warnings = new ArrayList<>();
        if (q.rows()) {
            Set<String> facts = new LinkedHashSet<>();
            fields.forEach(f -> {
                String alias = f.substring(0, f.indexOf('.'));
                if (v.facts().contains(alias)) {
                    facts.add(alias);
                }
            });
            if (facts.size() > 1) {
                out.add(new MqException.Problem("DRS-9160", "select.fields", "the rows of " + facts + " cannot be listed together: each row of one would repeat for "
                        + "every row of the other. Ask each fact's rows in its own extract", Map.of("split", List.copyOf(facts))));
            }
        }
        fail(out);
        Map<String, String> currency = new LinkedHashMap<>();
        boolean lastOnly = false;
        boolean dated = fields.stream().anyMatch(f -> f.endsWith("." + Dataset.DATE) || f.contains("." + Dataset.DATE + "."));
        boolean window = q.time().from() != null && !q.time().from().equals(q.time().to());
        List<String> semi = new ArrayList<>();
        for (String m : q.measures()) {
            ModelView.MeasureInfo info = v.info(ds, m);
            if (info.currency() != null && info.currency().contains(".") && !fields.contains(info.currency()) && !pinned(q, info.currency())) {
                currency.put(m, info.currency());
            }
            if (info.semiAdditive() && window && !dated) {
                semi.add(m);
            }
        }
        if (!semi.isEmpty()) {
            lastOnly = semi.size() == q.measures().size();
            warnings.add("DRS-9159 " + semi + (semi.size() == 1 ? " is" : " are") + " semi-additive (a balance): over a window of dates "
                    + (lastOnly ? "the answer is the last date's value" : "it is added across the dates; ask it alone or group by the business date") + " (refused from wave W2)");
        }

        // ---- 9 security
        for (int i = 0; i < q.where().size(); i++) {
            ModelQuery.Where w = q.where().get(i);
            if (masked(dr, ctx, w.field())) {
                out.add(new MqException.Problem("DRS-9161", "where[" + i + "].field", "'" + w.field() + "' is masked for you: it cannot be " + ("top".equals(w.op()) ? "ranked" : "filtered") + " on",
                        fixKey(dr, w.field(), "where[" + i + "].field")));
            }
        }
        for (int i = 0; i < q.sort().size(); i++) {
            String by = q.sort().get(i).by();
            if (by.contains(".") && masked(dr, ctx, by)) {
                out.add(new MqException.Problem("DRS-9161", "sort[" + i + "].by", "'" + by + "' is masked for you: it cannot be sorted on", fixKey(dr, by, "sort[" + i + "].by")));
            }
        }
        fail(out);

        // ---- 10 Python
        for (int i = 0; i < q.measures().size(); i++) {
            Dataset.Measure m = ds.measures().get(q.measures().get(i));
            if (m != null && m.python() != null) {
                out.add(MqException.Problem.of("DRS-9162", "select.measures[" + i + "]", "'" + m.name() + "' is a Python measure (" + m.python()
                        + "): a python visual runs it; a grouped query cannot"));
            }
        }
        fail(out);
        return new Checked(q, ds, fields, drillFilters, currency, lastOnly, warnings);
    }

    private static void fail(List<MqException.Problem> out) {
        if (!out.isEmpty()) {
            throw new MqException(out);
        }
    }

    /** Stable ids in fields, measures, filters and sorts become names. */
    static ModelQuery resolveIds(ModelView v, ModelQuery q) {
        Map<String, String> ids = v.ids();
        if (ids.isEmpty()) {
            return q;
        }
        List<String> fields = q.fields().stream().map(f -> ids.getOrDefault(f, f)).toList();
        List<String> measures = q.measures().stream().map(f -> ids.getOrDefault(f, f)).toList();
        List<ModelQuery.Where> where = q.where().stream().map(w -> new ModelQuery.Where(ids.getOrDefault(w.field(), w.field()), w.op(), w.values(), w.window(), w.n(),
                w.by() == null ? null : ids.getOrDefault(w.by(), w.by()), w.dir())).toList();
        List<ModelQuery.Having> having = q.having().stream().map(h -> new ModelQuery.Having(ids.getOrDefault(h.measure(), h.measure()), h.op(), h.values())).toList();
        List<ModelQuery.Sort> sort = q.sort().stream().map(s -> new ModelQuery.Sort(ids.getOrDefault(s.by(), s.by()), s.desc())).toList();
        return new ModelQuery(q.mq(), q.model(), q.define(), fields, measures, where, having, q.time(), sort, q.limit(), q.totals(), q.drill(), q.shape(), q.params(),
                q.options());
    }

    private static void field(Resolver r, Dataset ds, String f, String path, List<MqException.Problem> out) {
        try {
            Resolver.ColRef ref = r.resolve(List.of(f.split("\\.", -1)), null);
            if (ref.column() == null) {
                out.add(MqException.Problem.of("DRS-9152", path, "'" + f + "' is a dataset; a field is alias.column"));
            } else if (!f.startsWith(ref.table() + ".")) {
                out.add(new MqException.Problem("DRS-9152", path, "a field names its dataset: '" + f + "' is " + ref.table() + "." + f,
                        Map.of("replace", path, "with", List.of(ref.table() + "." + f))));
            }
        } catch (IllegalArgumentException e) {
            List<String> like = new ArrayList<>();
            String col = f.contains(".") ? f.split("\\.")[1] : f;
            ds.tables().values().forEach(t -> t.columns().keySet().stream().filter(c -> c.equalsIgnoreCase(col)).forEach(c -> like.add(t.name() + "." + c)));
            out.add(new MqException.Problem("DRS-9152", path, e.getMessage(), like.isEmpty() ? null : Map.of("replace", path, "with", like)));
        }
    }

    private static void measure(Set<String> names, String m, String path, List<MqException.Problem> out) {
        if (names.contains(m)) {
            return;
        }
        List<String> like = names.stream().filter(n -> n.equalsIgnoreCase(m) || n.toLowerCase(Locale.ROOT).contains(m.toLowerCase(Locale.ROOT))).toList();
        out.add(new MqException.Problem("DRS-9152", path, "the model has no measure '" + m + "'", like.isEmpty() ? null : Map.of("replace", path, "with", like)));
    }

    private static void contract(ModelDef.Contract c, ModelQuery q, Map<String, ModelDef.Meas> defs, List<MqException.Problem> out) {
        for (int i = 0; i < q.measures().size(); i++) {
            String m = q.measures().get(i);
            ModelDef.Deprecation d = c.deprecated().get(m);
            if (c.listed() && !q.define().containsKey(m) && !c.measures().contains(m)) {
                out.add(new MqException.Problem("DRS-9153", "select.measures[" + i + "]", "'" + m + "' is not in the model's contract" + (d == null ? ""
                        : ": it is deprecated since " + d.since() + ", use " + d.use()), d == null ? null : Map.of("replace", "select.measures[" + i + "]", "with", List.of(d.use()))));
            }
        }
        if (c.listed()) {
            for (int i = 0; i < q.fields().size(); i++) {
                String f = q.fields().get(i);
                String[] p = f.split("\\.");
                String baseField = p.length > 2 ? p[0] + "." + p[1] : f;
                if (!c.fields().contains(f) && !c.fields().contains(baseField)) {
                    out.add(MqException.Problem.of("DRS-9153", "select.fields[" + i + "]", "'" + f + "' is not in the model's contract (fields: " + c.fields() + ")"));
                }
            }
        }
    }

    /** The flattened dataset with the query's local measures, checked like a model's (step 4). */
    private static Dataset withDefine(Dataset base, ModelQuery q, Map<String, String> ids, List<MqException.Problem> out) {
        if (q.define().isEmpty()) {
            return base;
        }
        Map<String, Dataset.Measure> measures = new LinkedHashMap<>(base.measures());
        for (Map.Entry<String, String> e : q.define().entrySet()) {
            if (base.measures().containsKey(e.getKey())) {
                out.add(MqException.Problem.of("DRS-9154", "define." + e.getKey(), "'" + e.getKey() + "' is a measure of the model: a local measure takes another name"));
            }
            measures.put(e.getKey(), new Dataset.Measure(e.getKey(), com.ash.drishti.server.bi.expr.Expressions.withMeasureNames(e.getValue(), ids), null, "local measure", null));
        }
        Dataset ds = new Dataset(base.name(), base.version(), base.title(), base.description(), base.sample(), base.engine(), base.tables(), base.relationships(), measures,
                base.hierarchies(), base.security(), base.rollups(), base.refresh(), base.compose(), base.derived(), base.tests());
        for (DatasetParser.Problem p : DatasetParser.check(ds)) {
            for (String name : q.define().keySet()) {
                if (p.error() && (p.path().equals("measures." + name) || p.path().startsWith("measures." + name + "."))) {
                    out.add(MqException.Problem.of("DRS-9154", "define." + name, p.message()));
                }
            }
        }
        return ds;
    }

    private static String type(Resolver r, String field) {
        Resolver.ColRef ref = r.resolve(List.of(field.split("\\.", -1)), null);
        return ref.part() != null ? "integer" : r.column(ref.table(), ref.column()).type();
    }

    private static void filter(Resolver r, Dataset ds, ModelQuery.Where w, Set<String> measures, String path, List<MqException.Problem> out) {
        String type = type(r, w.field());
        int n = w.values().size();
        switch (w.op()) {
            case "relative" -> {
                if (!Set.of("date", "timestamp").contains(type)) {
                    out.add(MqException.Problem.of("DRS-9155", path, "a relative window filters a date; '" + w.field() + "' is " + type));
                } else if (w.window() == null || !RelativeDates.valid(w.window())) {
                    out.add(new MqException.Problem("DRS-9155", path + ".window", "'" + w.window() + "' is not a relative window",
                            Map.of("replace", path + ".window", "with", RelativeDates.DEFAULT_OPTIONS)));
                }
                return;
            }
            case "top" -> {
                if (w.n() == null || w.n() < 1 || w.by() == null || !measures.contains(w.by())) {
                    out.add(MqException.Problem.of("DRS-9155", path, "top is { field, op: top, n: <how many>, by: <a measure>, dir: desc | asc }"));
                }
                return;
            }
            case "is-null", "not-null" -> {
                if (n != 0) {
                    out.add(MqException.Problem.of("DRS-9155", path, w.op() + " takes no value"));
                }
                return;
            }
            case "in", "not-in" -> { }
            case "between" -> {
                if (n != 2) {
                    out.add(MqException.Problem.of("DRS-9155", path, "between takes two values: [low, high]"));
                }
            }
            case "contains", "starts-with" -> {
                if (!"string".equals(type) || n != 1) {
                    out.add(MqException.Problem.of("DRS-9155", path, w.op() + " takes one text value on a text field; '" + w.field() + "' is " + type));
                }
                return;
            }
            default -> {
                if (n != 1) {
                    out.add(MqException.Problem.of("DRS-9155", path, w.op() + " takes one value"));
                }
                if ("boolean".equals(type) && !Set.of("eq", "ne").contains(w.op())) {
                    out.add(MqException.Problem.of("DRS-9155", path, "a true/false field is compared with eq or ne"));
                }
            }
        }
        for (Object x : w.values()) {
            String why = valueProblem(type, x);
            if (why != null) {
                out.add(MqException.Problem.of("DRS-9155", path + ".values", "'" + x + "' " + why + "; '" + w.field() + "' is " + type));
            }
        }
    }

    private static String valueProblem(String type, Object x) {
        if (NUMERIC.contains(type)) {
            if (x instanceof Number) {
                return null;
            }
            try {
                Double.parseDouble(String.valueOf(x));
                return null;
            } catch (NumberFormatException e) {
                return "is not a number";
            }
        }
        if ("date".equals(type)) {
            try {
                LocalDate.parse(String.valueOf(x));
                return null;
            } catch (RuntimeException e) {
                return "is not a date (yyyy-mm-dd)";
            }
        }
        if ("boolean".equals(type) && !(x instanceof Boolean) && !Set.of("true", "false").contains(String.valueOf(x))) {
            return "is not true or false";
        }
        return null;
    }

    private static void drill(ModelQuery q, List<String> levels, List<String> fields, List<QueryRequest.Filter> filters, List<MqException.Problem> out) {
        int at = -1;
        int start = -1;
        for (int i = 0; i < fields.size() && at < 0; i++) {
            int l = levels.indexOf(fields.get(i));
            if (l >= 0) {
                at = i;
                start = l;
            }
        }
        if (at < 0) {
            out.add(new MqException.Problem("DRS-9155", "drill", "the query groups by no level of '" + q.drill().hierarchy() + "'",
                    Map.of("add", "select.fields", "value", levels.get(0))));
            return;
        }
        int level = start + q.drill().path().size();
        if (level >= levels.size()) {
            out.add(MqException.Problem.of("DRS-9155", "drill.path", "the drill goes past the last level of '" + q.drill().hierarchy() + "' (" + levels.get(levels.size() - 1) + ")"));
            return;
        }
        fields.set(at, levels.get(level));
        for (int i = 0; i < q.drill().path().size(); i++) {
            filters.add(new QueryRequest.Filter(levels.get(start + i), "eq", List.of(q.drill().path().get(i))));
        }
    }

    /** Step 6: the engine's planner, without masks, answers whether every field, filter and rule reaches each measure's fact many-to-one. */
    private static void reach(ModelView v, Dataset ds, ModelQuery q, List<String> fields, Context ctx, List<MqException.Problem> out) {
        List<String> measures = new ArrayList<>(q.measures().stream().filter(m -> ds.measures().get(m) == null || ds.measures().get(m).python() == null).toList());
        List<QueryRequest.Filter> filters = new ArrayList<>();
        for (ModelQuery.Where w : q.where()) {
            filters.add(new QueryRequest.Filter(w.field(), "is-null", List.of()));
        }
        for (ModelQuery.Having h : q.having()) {
            filters.add(new QueryRequest.Filter(h.measure(), "gt", List.of(0.0)));
        }
        if (fields.isEmpty() && measures.isEmpty()) {
            return;
        }
        SqlCompiler.Reader reader = new SqlCompiler.Reader(ctx.enforce(), ctx.roles(), ctx.attributes(), SqlCompiler.Masks.NONE);
        try {
            new SqlCompiler(ds, Dialect.DUCKDB).plan(new QueryRequest(fields, measures, filters, null, null, List.of(), null, "auto"), reader);
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.QUERY_INVALID) {
                List<String> shared = shared(ds, measures);
                String path = fields.isEmpty() ? "select.measures" : "select.fields[0]";
                for (int i = 0; i < fields.size(); i++) {
                    String alias = fields.get(i).substring(0, fields.get(i).indexOf('.'));
                    for (String m : measures) {
                        Set<String> tables = ModelView.tablesOf(ds, m);
                        if (!tables.isEmpty() && tables.stream().noneMatch(t -> t.equals(alias) || TableRules.reaches(ds, t, alias))) {
                            path = "select.fields[" + i + "]";
                        }
                    }
                }
                String fix = shared.isEmpty() ? " Ask the measures in separate queries." : " Group by a field they all reach (" + String.join(", ", shared) + ") or ask the measures apart.";
                out.add(new MqException.Problem("DRS-9156", path, e.getMessage().replaceFirst("^DRS-\\d+ ", "") + "." + fix,
                        shared.isEmpty() ? null : Map.of("replace", path, "with", shared)));
            }
        }
    }

    /** Columns of a dimension every measure's fact reaches many-to-one (at most four, keys first): what a refused grouping can use instead. */
    private static List<String> shared(Dataset ds, List<String> measures) {
        List<Set<String>> facts = measures.stream().map(m -> ModelView.tablesOf(ds, m)).filter(s -> !s.isEmpty()).toList();
        List<String> out = new ArrayList<>();
        for (Dataset.Table t : ds.tables().values()) {
            boolean all = !facts.isEmpty() && facts.stream().allMatch(f -> f.stream().anyMatch(x -> x.equals(t.name()) || TableRules.reaches(ds, x, t.name())));
            boolean isFact = facts.stream().anyMatch(f -> f.contains(t.name()));
            if (all && !isFact) {
                t.columns().values().stream().filter(c -> c.expr() == null).limit(4).forEach(c -> out.add(t.name() + "." + c.name()));
                break;
            }
        }
        return out;
    }

    private static void time(Dataset ds, ModelQuery q, List<String> fields, Context ctx, MqProperties props, List<MqException.Problem> out) {
        ModelQuery.Time t = q.time();
        if (t.asOf() != null && ctx.today() != null && t.asOf().isAfter(ctx.today())) {
            out.add(new MqException.Problem("DRS-9157", "time.as-of", "the as-of date " + t.asOf() + " is after the current business date " + ctx.today(),
                    Map.of("replace", "time.as-of", "with", List.of(ctx.today().toString()))));
        }
        if (t.from() != null && ChronoUnit.DAYS.between(t.from(), t.to()) + 1 > props.maxDays()) {
            out.add(MqException.Problem.of("DRS-9157", "time.window", "the window is " + (ChronoUnit.DAYS.between(t.from(), t.to()) + 1) + " days, over the "
                    + props.maxDays() + " one query may read (drishti.bi.mq.max-days)"));
        }
        if (t.live() && ds.tables().values().stream().noneMatch(Dataset.Table::isLive)) {
            out.add(new MqException.Problem("DRS-9157", "time.live", "the model has no live dataset: ask it as of a date", Map.of("remove", "time.live")));
        }
        if (t.last() != null) {
            boolean history = fields.stream().anyMatch(f -> {
                Dataset.Table table = ds.tables().get(f.substring(0, f.indexOf('.')));
                return table != null && table.isHistory();
            });
            if (!history) {
                out.add(MqException.Problem.of("DRS-9157", "time.window.last", "a sliding window reads a history (a live dataset's samples): group by its ts field"));
            }
        }
        for (int i = 0; i < q.where().size(); i++) {
            ModelQuery.Where w = q.where().get(i);
            if ("relative".equals(w.op()) && RelativeDates.valid(w.window()) && ctx.today() != null) {
                RelativeDates.Window win = RelativeDates.resolve(w.window(), asOf(q, ctx), ctx.calendar());
                if (win != null && ChronoUnit.DAYS.between(win.from(), win.to()) + 1 > props.maxDays() && t.from() != null) {
                    out.add(MqException.Problem.of("DRS-9157", "where[" + i + "].window", "the window '" + w.window() + "' is over the " + props.maxDays() + " days a query may read"));
                }
            }
        }
    }

    /** The date relative windows and time intelligence end at: the as-of date, the end of the window, else today. */
    static LocalDate asOf(ModelQuery q, Context ctx) {
        return q.time().asOf() != null ? q.time().asOf() : q.time().to() != null ? q.time().to() : ctx.today();
    }

    private static boolean pinned(ModelQuery q, String field) {
        return q.where().stream().anyMatch(w -> w.field().equals(field) && ("eq".equals(w.op()) || "in".equals(w.op()) && w.values().size() == 1));
    }

    private static boolean masked(Resolver r, Context ctx, String field) {
        try {
            Resolver.ColRef ref = r.resolve(List.of(field.split("\\.", -1)), null);
            return ref.column() != null && ctx.masks().masked(ref.table(), ref.column());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The fix for a masked field: the dataset's key (its id), which identifies the same rows and is not masked. */
    private static Map<String, Object> fixKey(Resolver r, String field, String path) {
        String alias = field.substring(0, field.indexOf('.'));
        return Map.of("replace", path, "with", List.of(alias + "." + Dataset.ID));
    }
}
