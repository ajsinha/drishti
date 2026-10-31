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
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.report.RelativeDates;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The model query's compiler front end (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 5.4, steps 4 to 10): a checked MQ becomes the engine's
 * {@link QueryRequest}s, which today's planner (per-grain parts, the meeting of facts, layers, rollups, live tables) answers unchanged. Relative windows are
 * computed from the as-of date and the calendar; each {@code top} is asked as its own grouped request in the context of the other filters, as the reader,
 * and becomes an {@code in} filter ({@code "\u0000none"} when nothing qualifies); a drill was already turned into a field and {@code eq} filters; {@code having}
 * becomes the engine's measure filters; the currency guard adds a distinct count of the currency per group; totals add one request per level for the
 * measures that do not add up (declared additivity, not a guess from the expression).
 */
public final class MqCompiler {

    /** The prefix of the measures the currency guard adds (removed from the answer). */
    public static final String GUARD = "__ccy_";

    /** The engine request, the dataset it runs against (with local and guard measures), the totals levels, and the guard measures by the measure they watch. */
    public record Compiled(QueryRequest main, Dataset dataset, List<Level> totals, Map<String, String> guards, LocalDate asOf) {}

    /** One level of totals: the fields it groups by and the request for the measures that do not add up. */
    public record Level(List<String> by, QueryRequest query) {}

    private MqCompiler() {}

    /**
     * @param top runs a grouped request as the reader (for {@code top} filters)
     */
    public static Compiled compile(ModelView v, MqValidator.Checked c, MqValidator.Context ctx, MqProperties props, Function<QueryRequest, QueryService.Result> top) {
        ModelQuery q = c.query();
        LocalDate asOf = MqValidator.asOf(q, ctx);
        LocalDate from = null;
        LocalDate to = null;
        if (q.time().asOf() != null) {
            from = q.time().asOf();
            to = q.time().asOf();
        } else if (q.time().from() != null) {
            from = c.lastDateOnly() ? q.time().to() : q.time().from();
            to = q.time().to();
        }
        String layer = q.options().layer();
        List<QueryRequest.Filter> filters = new ArrayList<>(c.drillFilters());
        for (ModelQuery.Where w : q.where()) {
            if ("relative".equals(w.op())) {
                RelativeDates.Window win = RelativeDates.resolve(w.window(), asOf, ctx.calendar());
                if (win != null) {
                    filters.add(new QueryRequest.Filter(w.field(), "between", List.of(win.from().toString(), win.to().toString())));
                }
            } else if (!"top".equals(w.op())) {
                filters.add(new QueryRequest.Filter(w.field(), w.op(), w.values()));
            }
        }
        if (q.time().last() != null) {
            String ts = c.fields().stream().filter(f -> f.endsWith("." + Dataset.TS)).findFirst().orElse(null);
            if (ts != null) {
                filters.add(new QueryRequest.Filter(ts, "ge", List.of(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minus(q.time().last()).toString())));
            }
        }
        List<QueryRequest.Filter> context = List.copyOf(filters);
        for (ModelQuery.Where w : q.where()) {
            if ("top".equals(w.op())) {
                QueryRequest tq = new QueryRequest(List.of(w.field()), List.of(w.by()), context, from, to, List.of(new QueryRequest.Sort(w.by(), !"asc".equalsIgnoreCase(w.dir()))),
                        w.n(), layer);
                QueryService.Result r = top.apply(tq);
                List<Object> values = new ArrayList<>(column(r, w.field()));
                filters.add(new QueryRequest.Filter(w.field(), "in", values.isEmpty() ? List.of("\u0000none") : values));
            }
        }
        for (ModelQuery.Having h : q.having()) {
            filters.add(new QueryRequest.Filter(h.measure(), h.op(), h.values()));
        }
        Dataset ds = c.dataset();
        List<String> measures = new ArrayList<>(q.measures());
        Map<String, String> guards = new LinkedHashMap<>();
        if (!c.currencyChecks().isEmpty()) {
            Map<String, Dataset.Measure> all = new LinkedHashMap<>(ds.measures());
            Map<String, String> byCurrency = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : c.currencyChecks().entrySet()) {
                String name = byCurrency.computeIfAbsent(e.getValue(), k -> GUARD + byCurrency.size());
                if (!all.containsKey(name)) {
                    all.put(name, new Dataset.Measure(name, "distinct(" + e.getValue() + ")", null, "currency guard", null));
                    measures.add(name);
                }
                guards.put(e.getKey(), name);
            }
            ds = ds.withMeasures(all);
        }
        List<QueryRequest.Sort> sort = q.sort().stream().map(s -> new QueryRequest.Sort(s.by(), s.desc())).toList();
        int limit = Math.min(props.maxLimit(), q.limit() != null ? q.limit() : q.rows() ? props.rowsLimit() : props.groupedLimit());
        QueryRequest main = new QueryRequest(c.fields(), measures, filters, from, to, sort, limit, layer, q.rows() && measures.isEmpty());
        List<Level> levels = new ArrayList<>();
        if (q.totals() != null && !q.rows()) {
            final Dataset finalDs = ds;
            List<String> nonAdd = q.measures().stream().filter(m -> !v.info(finalDs, m).adds()).toList();
            if (!nonAdd.isEmpty()) {
                for (List<String> by : levels(q.totals(), c.fields())) {
                    List<QueryRequest.Filter> measureFree = List.copyOf(filters.subList(0, filters.size() - q.having().size()));     // a having filters groups, not totals
                    levels.add(new Level(by, new QueryRequest(by, nonAdd, measureFree, from, to, List.of(), props.maxLimit(), layer)));
                }
            }
        }
        return new Compiled(main, ds, levels, guards, asOf);
    }

    /** The levels of totals: grand ({@code []}), subtotals (every proper prefix of the fields), or the explicit ones. */
    static List<List<String>> levels(ModelQuery.Totals t, List<String> fields) {
        return switch (t.mode()) {
            case "grand" -> List.of(List.of());
            case "subtotals" -> {
                List<List<String>> out = new ArrayList<>();
                for (int i = 0; i < fields.size(); i++) {
                    out.add(List.copyOf(fields.subList(0, i)));
                }
                yield out;
            }
            default -> t.levels();
        };
    }

    /** The values of one column of an answer. */
    static List<Object> column(QueryService.Result r, String name) {
        List<Object> out = new ArrayList<>();
        for (ArrowIpcWriter.Column col : r.columns()) {
            if (col.name().equals(name)) {
                for (int i = 0; i < r.rows(); i++) {
                    if (col.values()[i] != null) {
                        out.add(col.values()[i]);
                    }
                }
            }
        }
        return out;
    }
}
