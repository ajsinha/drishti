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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.ExprParser;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns one visual, with everything the person has done on the page, into a dataset query (RUPAKA.md section 8). The order of the filters is the
 * order of authority: the report's, the page's, the visual's, the slicers in scope, the marks selected on other visuals, the values carried by a drill-through
 * and the drill-down path. Top-N filters are resolved first (the values with the highest measure, in the context of the other filters) and become
 * {@code in} filters. Nothing here reaches the data: the engine checks every name and binds every value.
 */
public final class VisualQueries {

    /** The query for the visual; {@code highlight} is the same query with the marks selected on other visuals applied, null when nothing is highlighted. */
    public record Built(QueryRequest query, QueryRequest highlight) {}

    /** Resolves a top-N filter to the values it keeps. */
    @FunctionalInterface
    public interface TopValues {
        List<Object> top(String field, int n, String by, boolean desc, List<QueryRequest.Filter> context, LocalDate from, LocalDate to);
    }

    /** Visual types that can draw a highlight over their own bars (the others are filtered instead). */
    static final Set<String> HIGHLIGHTS = Set.of("bar", "column");

    private final Report report;
    private final Report.Page page;
    private final Report.Visual visual;
    private final ReportState state;
    private final Dataset ds;
    private final Resolver resolver;
    private final BiReportProperties props;
    private final TopValues topValues;

    private VisualQueries(Report report, Report.Page page, Report.Visual visual, ReportState state, Dataset ds, BiReportProperties props, TopValues topValues) {
        this.report = report;
        this.page = page;
        this.visual = visual;
        this.state = state;
        this.ds = ds;
        this.resolver = new Resolver(ds);
        this.props = props;
        this.topValues = topValues;
    }

    public static Built build(Report report, Report.Page page, Report.Visual visual, ReportState state, Dataset ds, BiReportProperties props, TopValues top) {
        return new VisualQueries(report, page, visual, state, ds, props, top).build();
    }

    private Built build() {
        VisualCatalogue.Type spec = VisualCatalogue.type(visual.type());
        if (spec == null || "static".equals(spec.group())) {
            throw new DrishtiException(ErrorCode.REPORT_STATE, "the " + visual.type() + " visual draws no data");
        }
        LocalDate from = date(state.from() != null ? state.from() : report.dates() == null ? null : report.dates().from());
        LocalDate to = date(state.to() != null ? state.to() : report.dates() == null ? null : report.dates().to());
        List<QueryRequest.Filter> base = new ArrayList<>();
        List<Report.Filter> tops = new ArrayList<>();
        add(base, tops, report.filters());
        add(base, tops, page.filters());
        add(base, tops, visual.filters());
        slicers(base);
        carried(base);
        List<String> fields = fieldsOf();
        List<String> measures = measuresOf();
        drill(fields, base);
        List<QueryRequest.Filter> marks = new ArrayList<>();
        boolean highlight = false;
        if (visual.interacts() && !"slicer".equals(visual.type())) {
            for (ReportState.Selection s : state.selections()) {
                if (s.visual().equals(visual.id()) || s.values().isEmpty()) {
                    continue;
                }
                Report.Visual from0 = page.visual(s.visual());
                if (from0 == null) {
                    throw new DrishtiException(ErrorCode.REPORT_STATE, "the page has no visual '" + s.visual() + "' to take a selection from");
                }
                if (s.highlight() && HIGHLIGHTS.contains(visual.type()) && state.rows() == null) {
                    highlight = true;
                    marks.add(new QueryRequest.Filter(s.field(), "in", s.values()));
                } else {
                    base.add(new QueryRequest.Filter(s.field(), "in", s.values()));
                }
            }
        }
        List<QueryRequest.Filter> ctx = new ArrayList<>(base);
        for (Report.Filter t : tops) {
            base.add(resolveTop(t, ctx, from, to));
        }
        if (state.rows() != null) {
            return new Built(rows(fields, measures, base, from, to), null);
        }
        List<QueryRequest.Sort> sorts = sorts(fields, measures);
        int limit = limit(spec);
        QueryRequest q = new QueryRequest(fields, measures, base, from, to, sorts, limit, "auto");
        QueryRequest h = null;
        if (highlight) {
            List<QueryRequest.Filter> withMarks = new ArrayList<>(base);
            withMarks.addAll(marks);
            h = new QueryRequest(fields, measures, withMarks, from, to, sorts, limit, "auto");
        }
        return new Built(q, h);
    }

    // ---- the pieces --------------------------------------------------------------------------------------------------

    private List<String> fieldsOf() {
        Set<String> f = new LinkedHashSet<>();
        switch (visual.type()) {
            case "scatter" -> f.addAll(visual.well("details"));
            case "table" -> visual.well("columns").stream().filter(x -> !ds.measures().containsKey(x)).forEach(f::add);
            case "matrix" -> {
                f.addAll(visual.well("rows"));
                f.addAll(visual.well("columns"));
            }
            case "slicer" -> f.addAll(visual.well("field"));
            default -> {
                f.addAll(visual.well("axis"));
                f.addAll(visual.well("legend"));
            }
        }
        return new ArrayList<>(f);
    }

    private List<String> measuresOf() {
        Set<String> m = new LinkedHashSet<>();
        switch (visual.type()) {
            case "scatter" -> {
                m.addAll(visual.well("x"));
                m.addAll(visual.well("y"));
                m.addAll(visual.well("size"));
            }
            case "table" -> visual.well("columns").stream().filter(x -> ds.measures().containsKey(x)).forEach(m::add);
            case "slicer" -> { }
            default -> {
                m.addAll(visual.well("values"));
                m.addAll(visual.well("secondary"));
                m.addAll(visual.well("target"));
            }
        }
        return new ArrayList<>(m);
    }

    private void add(List<QueryRequest.Filter> base, List<Report.Filter> tops, List<Report.Filter> list) {
        for (Report.Filter f : list) {
            if ("top".equals(f.op())) {
                tops.add(f);
            } else {
                base.add(new QueryRequest.Filter(f.field(), f.op(), f.values()));
            }
        }
    }

    /** Slicer choices in scope: those on this page, and those of report scope on any page; a slicer never filters itself. */
    private void slicers(List<QueryRequest.Filter> base) {
        for (var e : state.slicers().entrySet()) {
            Report.Visual s = null;
            boolean onThisPage = false;
            for (Report.Page p : report.pages()) {
                Report.Visual x = p.visual(e.getKey());
                if (x != null && "slicer".equals(x.type())) {
                    s = x;
                    onThisPage = p.id().equals(page.id());
                    break;
                }
            }
            if (s == null) {
                throw new DrishtiException(ErrorCode.REPORT_STATE, "the report has no slicer '" + e.getKey() + "'");
            }
            if (s.id().equals(visual.id()) || s.well("field").isEmpty() || !(onThisPage || "report".equals(s.format().get("scope")))) {
                continue;
            }
            String field = s.well("field").get(0);
            JsonNode v = e.getValue();
            if (v.isArray() && !v.isEmpty()) {
                base.add(new QueryRequest.Filter(field, "in", ReportState.values(v)));
            } else if (v.isObject()) {
                if (v.hasNonNull("from")) {
                    base.add(new QueryRequest.Filter(field, "ge", List.of(ReportState.scalar(v.get("from")))));
                }
                if (v.hasNonNull("to")) {
                    base.add(new QueryRequest.Filter(field, "le", List.of(ReportState.scalar(v.get("to")))));
                }
            }
        }
    }

    private void carried(List<QueryRequest.Filter> base) {
        for (ReportState.Carry c : state.carry()) {
            if (!c.values().isEmpty() || c.op().contains("null")) {
                base.add(new QueryRequest.Filter(c.field(), c.op(), c.values()));
            }
        }
    }

    /** Drill-down: the axis moves down the hierarchy by one level for each value clicked, and the clicked values become filters. */
    private void drill(List<String> fields, List<QueryRequest.Filter> base) {
        if (state.drill().isEmpty()) {
            return;
        }
        List<String> levels = visual.hierarchy() == null ? null : ds.hierarchies().get(visual.hierarchy());
        if (levels == null || fields.isEmpty()) {
            throw new DrishtiException(ErrorCode.REPORT_STATE, "the visual '" + visual.id() + "' does not drill down a hierarchy");
        }
        int start = Math.max(0, levels.indexOf(fields.get(0)));
        int level = start + state.drill().size();
        if (level >= levels.size()) {
            throw new DrishtiException(ErrorCode.REPORT_STATE, "the hierarchy '" + visual.hierarchy() + "' has " + levels.size() + " levels; there is nothing below " + levels.get(levels.size() - 1));
        }
        for (int i = 0; i < state.drill().size(); i++) {
            base.add(new QueryRequest.Filter(levels.get(start + i), "eq", List.of(state.drill().get(i))));
        }
        fields.set(0, levels.get(level));
    }

    private QueryRequest.Filter resolveTop(Report.Filter t, List<QueryRequest.Filter> context, LocalDate from, LocalDate to) {
        List<Object> values = topValues.top(t.field(), t.n(), t.by(), !"asc".equals(t.dir()), context, from, to);
        return new QueryRequest.Filter(t.field(), "in", values.isEmpty() ? List.of("\u0000none") : values);
    }

    private List<QueryRequest.Sort> sorts(List<String> fields, List<String> measures) {
        if (visual.sort() != null) {
            return List.of(new QueryRequest.Sort(visual.sort().by(), visual.sort().desc()));
        }
        if (fields.isEmpty()) {
            return List.of();
        }
        String first = fields.get(0);
        if (isDate(first)) {
            return List.of(new QueryRequest.Sort(first, false));
        }
        boolean ranked = !measures.isEmpty() && !Set.of("heatmap", "matrix", "table", "slicer").contains(visual.type());
        return ranked ? List.of(new QueryRequest.Sort(measures.get(0), true)) : List.of();       // else the engine's own order (it never sorts on a masked column)
    }

    private boolean isDate(String field) {
        try {
            Resolver.ColRef ref = resolver.resolve(List.of(field.split("\\.")), null);
            if (ref.part() != null) {
                return true;
            }
            Dataset.Column c = resolver.column(ref.table(), ref.column());
            return c != null && ("date".equals(c.type()) || "timestamp".equals(c.type()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private int limit(VisualCatalogue.Type spec) {
        int dflt = switch (spec.group()) {
            case "table" -> props.tableRows();
            case "slicer" -> props.slicerValues();
            default -> props.chartRows();
        };
        int want = visual.limit() != null ? visual.limit() : dflt;
        return state.limit() != null ? Math.min(want, state.limit()) : want;
    }

    // ---- show the rows -----------------------------------------------------------------------------------------------

    /** The rows behind the visual: every column of the table the visual reads, under the same filters (and the mark, when one was chosen). */
    private QueryRequest rows(List<String> fields, List<String> measures, List<QueryRequest.Filter> base, LocalDate from, LocalDate to) {
        String table = null;
        for (String f : fields) {
            table = tableOf(f);
            if (table != null) {
                break;
            }
        }
        for (int i = 0; table == null && i < measures.size(); i++) {
            Set<String> tables = tablesOfMeasure(measures.get(i), new LinkedHashSet<>());
            table = tables.isEmpty() ? null : tables.iterator().next();
        }
        if (table == null) {
            throw new DrishtiException(ErrorCode.REPORT_STATE, "the visual reads no table whose rows could be shown");
        }
        final String from0 = table;
        Dataset.Table t = ds.tables().get(from0);
        List<String> cols = new ArrayList<>();
        cols.add(from0 + "." + Dataset.ID);
        if (t.each() != null) {
            cols.add(from0 + "." + Dataset.INDEX);
        }
        t.columns().keySet().stream().limit(40).forEach(c -> cols.add(from0 + "." + c));
        cols.add(from0 + "." + Dataset.DATE);
        List<QueryRequest.Filter> filters = new ArrayList<>(base);
        ReportState.Mark mark = state.rows();
        if (mark.field() != null && !mark.values().isEmpty()) {
            filters.add(new QueryRequest.Filter(mark.field(), "in", mark.values()));
        }
        int limit = Math.min(props.showRows(), state.limit() != null ? state.limit() : props.showRows());
        return new QueryRequest(cols, List.of(), filters, from, to, List.of(new QueryRequest.Sort(cols.get(0), false)), limit, "auto");
    }

    private String tableOf(String field) {
        try {
            return resolver.resolve(List.of(field.split("\\.")), null).table();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Set<String> tablesOfMeasure(String name, Set<String> seen) {
        Set<String> tables = new LinkedHashSet<>();
        Dataset.Measure m = ds.measures().get(name);
        if (m == null || m.expr() == null || !seen.add(name)) {
            return tables;
        }
        Node.walk(ExprParser.parse(m.expr()), n -> {
            if (n instanceof Node.Ref r && ds.tables().containsKey(r.parts().get(0))) {
                tables.add(r.parts().get(0));
            } else if (n instanceof Node.MeasureRef mr) {
                tables.addAll(tablesOfMeasure(mr.name(), seen));
            }
        });
        return tables;
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            throw new DrishtiException(ErrorCode.REPORT_STATE, "dates are yyyy-mm-dd, not '" + s + "'");
        }
    }
}
