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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks a model a model query (docs/architecture/BI_MODEL_SPEC.md section 5): validates it (steps 3 to 10, {@link MqValidator}), compiles it
 * ({@link MqCompiler}), runs the engine requests as the reader through the model's runner, and writes the one answer shape of section 5.2: the model, typed
 * columns with stable ids, additivity and masks, the rows, the totals per level, and the meta (as-of, dates, layer, tables, masks, warnings, cache, time,
 * the data generation that is the standing query's cursor, the canonical query that ran). Has no state; the catalogue binds a model to a reader.
 */
public final class MqService {

    /** Runs one engine request against a dataset as the bound reader (the model's dataset, or it with local and guard measures). */
    public interface Runner {
        QueryService.Result run(Dataset ds, QueryRequest q, boolean explain);
    }

    /** A model bound to one reader: what it is, how its requests run, and the reader's side of the checks. */
    public record Bound(ModelView view, Runner runner, MqValidator.Context context) {}

    /** An answer: the JSON body, and the main result (without the guard columns) for an Arrow stream. */
    public record Answer(Map<String, Object> body, QueryService.Result result) {}

    private final MqProperties props;

    public MqService(MqProperties props) {
        this.props = props;
    }

    public MqProperties properties() {
        return props;
    }

    /** Steps 3 to 9 without reading data (top-N is not resolved): {@code valid}, the warnings, and the engine request it would run; a refusal throws. */
    public Map<String, Object> validate(Bound b, ModelQuery q) {
        MqValidator.Checked c = MqValidator.validate(b.view(), q, b.context(), props);
        MqCompiler.Compiled compiled = MqCompiler.compile(b.view(), c, b.context(), props, tq -> new QueryService.Result(List.of(), 0, null));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", true);
        m.put("model", model(b.view()));
        m.put("warnings", c.warnings());
        m.put("currencyGuard", c.currencyChecks());
        m.put("query", MqParser.canonical(c.query()));
        m.put("request", request(compiled.main()));
        m.put("totals", compiled.totals().size());
        return m;
    }

    /**
     * What one query ran to, before the answer is written: the main result with and without the currency guard's columns, the totals per level, the warnings
     * (the validator's and the guard's) and when it started.
     */
    public record Ran(ModelQuery query, MqValidator.Checked checked, MqCompiler.Compiled compiled, QueryService.Result raw, QueryService.Result shown,
            List<Map<String, Object>> totals, List<String> warnings, long started) {

        /** The main result with the query's warnings ahead of the engine's: what a consumer that keeps the engine's answer shape shows. */
        public QueryService.Result withWarnings() {
            QueryService.Meta m = shown.meta();
            if (m == null || warnings.isEmpty()) {
                return shown;
            }
            List<String> all = new ArrayList<>(warnings);
            m.warnings().stream().filter(w -> !all.contains(w)).forEach(all::add);
            return new QueryService.Result(shown.columns(), shown.rows(), new QueryService.Meta(m.layer(), m.rollup(), m.rows(), m.millis(), m.cached(), m.dates(), m.tables(),
                    m.masked(), all, m.sql(), m.generation()));
        }

        /** The canonical query that ran (section 5.2 {@code meta.query}). */
        public Map<String, Object> canonical() {
            return MqParser.canonical(checked.query());
        }
    }

    public Answer ask(Bound b, ModelQuery q, boolean explain) {
        return answer(b, run(b, q, explain, false));
    }

    /**
     * Validates, compiles and runs a query as the bound reader, without writing the answer. {@code engineLimit}: a query without a {@code limit} keeps the
     * engine's rule (refused over {@code drishti.bi.query.max-rows}, {@code DRS-9022}) instead of the language's default cut; the consumers that predate the
     * language (a report visual, an extract, an alert) ask that way, so their answers are what they were.
     */
    public Ran run(Bound b, ModelQuery q, boolean explain, boolean engineLimit) {
        long t0 = System.nanoTime();
        MqValidator.Checked c = MqValidator.validate(b.view(), q, b.context(), props);
        MqCompiler.Compiled compiled = MqCompiler.compile(b.view(), c, b.context(), props, tq -> b.runner().run(c.dataset(), tq, false));
        QueryRequest main = compiled.main();
        if (engineLimit && q.limit() == null) {
            main = new QueryRequest(main.fields(), main.measures(), main.filters(), main.from(), main.to(), main.orderBy(), null, main.layer());
        }
        QueryService.Result r = b.runner().run(compiled.dataset(), main, explain);
        List<String> warnings = new ArrayList<>(c.warnings());
        warnings.addAll(guardWarnings(r, compiled.guards()));
        QueryService.Result shown = withoutGuards(r);
        List<Map<String, Object>> totals = new ArrayList<>();
        for (MqCompiler.Level level : compiled.totals()) {
            QueryService.Result tr = b.runner().run(compiled.dataset(), level.query(), false);
            totals.add(Map.of("by", level.by(), "measures", level.query().measures(), "rows", rows(tr)));
        }
        return new Ran(q, c, compiled, r, shown, totals, warnings, t0);
    }

    /** The answer of section 5.2 for a query that ran. */
    public Answer answer(Bound b, Ran ran) {
        ModelQuery q = ran.query();
        MqValidator.Checked c = ran.checked();
        MqCompiler.Compiled compiled = ran.compiled();
        QueryService.Result r = ran.raw();
        QueryService.Result shown = ran.shown();
        List<Map<String, Object>> totals = ran.totals();
        List<String> warnings = ran.warnings();
        long t0 = ran.started();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mq", 1);
        body.put("model", model(b.view()));
        body.put("columns", columns(b.view(), compiled.dataset(), c, shown));
        body.put("rows", rows(shown));
        body.put("rowCount", shown.rows());
        if (!totals.isEmpty() || q.totals() != null) {
            body.put("totals", totals);
        }
        QueryService.Meta mm = r.meta();
        Map<String, Object> meta = new LinkedHashMap<>();
        boolean timed = q.time().asOf() != null || q.time().from() != null;
        meta.put("asOf", !timed && mm != null && !mm.dates().isEmpty() ? mm.dates().get(mm.dates().size() - 1) : compiled.asOf() == null ? null : compiled.asOf().toString());
        meta.put("dates", mm == null ? List.of() : mm.dates());
        meta.put("layer", mm == null ? null : mm.layer());
        meta.put("rollup", mm == null ? null : mm.rollup());
        meta.put("parts", Math.max(1, r.meta() == null ? 1 : factCount(b.view(), c)));
        meta.put("tables", mm == null ? List.of() : mm.tables());
        meta.put("masked", mm == null ? List.of() : mm.masked().stream().filter(x -> !x.startsWith(MqCompiler.GUARD)).toList());
        List<String> all = new ArrayList<>(warnings);
        if (mm != null) {
            all.addAll(mm.warnings());
        }
        meta.put("warnings", all);
        meta.put("noAccess", noAccess(all));
        meta.put("cached", mm != null && mm.cached());
        meta.put("millis", Math.round((System.nanoTime() - t0) / 1e5) / 10.0);
        meta.put("generation", "g-" + (mm == null ? 0 : mm.generation()));
        meta.put("query", MqParser.canonical(c.query()));
        meta.put("sql", mm == null ? null : mm.sql());
        body.put("meta", meta);
        return new Answer(body, shown);
    }

    /** The prefix of the engine's note when a row rule of the model shuts the reader out of a dataset (SqlCompiler). */
    static final String NO_ACCESS = "you have no access to rows of ";

    /** The datasets (model aliases) the reader may see no row of, from the engine's notes: the viewer says so instead of "no rows for this selection". */
    public static List<String> noAccess(List<String> warnings) {
        List<String> out = new ArrayList<>();
        for (String w : warnings) {
            int end = w.indexOf(" in this model");
            if (w.startsWith(NO_ACCESS) && end > NO_ACCESS.length()) {
                for (String a : w.substring(NO_ACCESS.length(), end).split(",\\s*")) {
                    if (!out.contains(a)) {
                        out.add(a);
                    }
                }
            }
        }
        return out;
    }

    private static int factCount(ModelView v, MqValidator.Checked c) {
        java.util.Set<String> facts = new java.util.LinkedHashSet<>();
        c.query().measures().forEach(m -> facts.addAll(ModelView.tablesOf(c.dataset(), m)));
        return facts.size();
    }

    static Map<String, Object> model(ModelView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.def().id() == null || v.def().id().isBlank() ? v.id() : v.def().id());
        m.put("name", v.def().name());
        m.put("version", v.def().version());
        m.put("implicit", v.def().implicit());
        m.put("compat", v.compat());
        return m;
    }

    private static Map<String, Object> request(QueryRequest q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fields", q.fields());
        m.put("measures", q.measures());
        m.put("filters", q.filters().stream().map(f -> Map.of("field", f.field(), "op", f.op(), "values", f.values())).toList());
        m.put("from", q.from() == null ? null : q.from().toString());
        m.put("to", q.to() == null ? null : q.to().toString());
        m.put("orderBy", q.orderBy().stream().map(s -> Map.of("by", s.by(), "desc", s.desc())).toList());
        m.put("limit", q.limit());
        m.put("layer", q.layer());
        return m;
    }

    private List<Map<String, Object>> columns(ModelView v, Dataset ds, MqValidator.Checked c, QueryService.Result r) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resolver resolver = new Resolver(ds);
        List<String> masked = r.meta() == null ? List.of() : r.meta().masked();
        for (ArrowIpcWriter.Column col : r.columns()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", col.name());
            boolean field = c.fields().contains(col.name());
            if (field) {
                m.put("id", v.fieldId(col.name()));
                m.put("kind", "field");
                String type;
                try {
                    Resolver.ColRef ref = resolver.resolve(List.of(col.name().split("\\.", -1)), null);
                    type = ref.part() != null ? "integer" : resolver.column(ref.table(), ref.column()).type();
                } catch (RuntimeException e) {
                    type = kind(col.kind());
                }
                m.put("type", type);
            } else {
                ModelView.MeasureInfo info = v.info(ds, col.name());
                m.put("id", info.id());
                m.put("kind", "measure");
                m.put("type", kind(col.kind()));
                m.put("format", info.format());
                m.put("additive", info.adds() ? Boolean.TRUE : info.semiAdditive() ? Map.of("over-time", info.additive()) : Boolean.FALSE);
            }
            m.put("masked", masked.contains(col.name()));
            out.add(m);
        }
        return out;
    }

    private static String kind(ArrowIpcWriter.Kind k) {
        return switch (k) {
            case TEXT -> "string";
            case INT64 -> "integer";
            case FLOAT64 -> "decimal";
        };
    }

    static List<List<Object>> rows(QueryService.Result r) {
        List<List<Object>> rows = new ArrayList<>(r.rows());
        for (int i = 0; i < r.rows(); i++) {
            List<Object> row = new ArrayList<>(r.columns().size());
            for (ArrowIpcWriter.Column c : r.columns()) {
                row.add(c.values()[i]);
            }
            rows.add(row);
        }
        return rows;
    }

    /** The currency guard's finding: the groups where a measure adds rows of several currencies (wave W1: a warning, W2 refuses). */
    private static List<String> guardWarnings(QueryService.Result r, Map<String, String> guards) {
        List<String> out = new ArrayList<>();
        Map<String, List<String>> byGuard = new LinkedHashMap<>();
        guards.forEach((m, g) -> byGuard.computeIfAbsent(g, k -> new ArrayList<>()).add(m));
        for (Map.Entry<String, List<String>> e : byGuard.entrySet()) {
            ArrowIpcWriter.Column g = r.columns().stream().filter(c -> c.name().equals(e.getKey())).findFirst().orElse(null);
            if (g == null) {
                continue;
            }
            List<String> groups = new ArrayList<>();
            for (int i = 0; i < r.rows(); i++) {
                Object v = g.values()[i];
                if (v instanceof Number n && n.longValue() > 1) {
                    List<String> label = new ArrayList<>();
                    for (ArrowIpcWriter.Column c : r.columns()) {
                        if (c.kind() == ArrowIpcWriter.Kind.TEXT && !c.name().startsWith(MqCompiler.GUARD)) {
                            label.add(String.valueOf(c.values()[i]));
                        }
                    }
                    groups.add(label.isEmpty() ? "the total" : String.join(" / ", label));
                }
            }
            if (!groups.isEmpty()) {
                out.add("DRS-9158 " + e.getValue() + " add" + (e.getValue().size() == 1 ? "s" : "") + " amounts in several currencies in " + groups.size() + " group"
                        + (groups.size() == 1 ? "" : "s") + " (" + String.join("; ", groups.stream().limit(5).toList()) + (groups.size() > 5 ? "; ..." : "")
                        + "): split by the currency or filter one currency (refused from wave W2)");
            }
        }
        return out;
    }

    private static QueryService.Result withoutGuards(QueryService.Result r) {
        if (r.columns().stream().noneMatch(c -> c.name().startsWith(MqCompiler.GUARD))) {
            return r;
        }
        List<ArrowIpcWriter.Column> cols = r.columns().stream().filter(c -> !c.name().startsWith(MqCompiler.GUARD)).toList();
        return new QueryService.Result(cols, r.rows(), r.meta());
    }
}
