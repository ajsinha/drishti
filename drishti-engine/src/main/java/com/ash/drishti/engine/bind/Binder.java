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
package com.ash.drishti.engine.bind;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.LinkView;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import com.ash.drishti.graph.BadgeRenderer;
import com.ash.drishti.graph.LinkRef;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Link;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.format.Tones;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Binds one panel of an effective layout to a document, producing formatted, toned {@link PanelView}s.
 * Stateless and thread-safe; a failure in one panel becomes that panel's error, never the view's.
 */
public final class Binder {

    private final ElCompiler el;
    private final Formats formats;
    private final ReferenceCatalog catalog;
    private final BadgeRenderer badges;
    private final Mnemonics mnemonics;
    private final ChartBinder charts;
    private final PivotBinder pivots;
    private final PanelLimits limits;

    public Binder(ElCompiler el, Formats formats, ReferenceCatalog catalog, BadgeRenderer badges, Mnemonics mnemonics) {
        this(el, formats, catalog, badges, mnemonics, PanelLimits.defaults());
    }

    public Binder(ElCompiler el, Formats formats, ReferenceCatalog catalog, BadgeRenderer badges, Mnemonics mnemonics, PanelLimits limits) {
        this(el, formats, catalog, badges, mnemonics, limits, com.ash.drishti.engine.pivot.PivotProperties.defaults());
    }

    public Binder(ElCompiler el, Formats formats, ReferenceCatalog catalog, BadgeRenderer badges, Mnemonics mnemonics, PanelLimits limits,
            com.ash.drishti.engine.pivot.PivotProperties pivot) {
        this.el = el;
        this.formats = formats;
        this.catalog = catalog;
        this.badges = badges;
        this.mnemonics = mnemonics;
        this.limits = limits;
        this.charts = new ChartBinder(this, formats, catalog, limits);
        this.pivots = new PivotBinder(el, catalog, this, pivot);
    }

    /**
     * A table's or ladder's rows as raw values of its pivot's fields, for the Pivot tab (every row up to
     * {@code drishti.pivot.max-records}, whatever the table's {@code limit}).
     *
     * @throws IllegalArgumentException when the panel offers no pivot
     */
    public PivotBinder.Records records(Panel p, BindContext c) {
        return pivots.records(p, c);
    }

    public PanelView bind(Panel p, BindContext c) {
        String title;
        try {
            title = p.title() == null ? null : el.template(p.title()).render(c.eval());
        } catch (RuntimeException | StackOverflowError e) {
            title = p.title();   // a title that cannot be rendered from this document shows as written
        }
        String explanation = c.layout().explanations().get(p.id());
        try {
            PanelData data = switch (p.kind()) {
                case KV -> kv(p, c);
                case TABLE, LADDER -> table(p, c);
                case TABS -> tabs(p, c);
                case LINE -> line(p, c);
                case AREA -> area(p, c);
                case HBAR -> bars(p, c);
                case LINKS -> links(c);
                case STATUS -> status(p, c);
                case PROVENANCE -> provenance(c);
                case MARKDOWN -> new PanelData.Text(el.template(p.option("text").orElse("")).render(c.eval()));
                case GAUGE -> gauge(p, c);
                case SURFACE -> surface(p, c);
                case WATERFALL -> charts.waterfall(p, c);
                case HISTOGRAM -> charts.histogram(p, c);
                case SCATTER -> charts.scatter(p, c);
                case CANDLESTICK -> charts.candlestick(p, c);
                case GRAPH -> charts.graph(p, c);
                case TIMELINE -> charts.timeline(p, c);
                case PIVOT -> charts.pivot(p, c);
            };
            return new PanelView(p.id(), p.kind().id(), title, p.code(), p.key(), area(p), p.infer() || explanation != null,
                    explanation, data, null, com.ash.drishti.engine.view.Emptiness.of(data), p.span().orElse(null), p.height().orElse(null));
        } catch (RuntimeException | StackOverflowError e) {
            // one panel whose data does not fit its Sutra must never take the view down; nor one whose expressions
            // are too deep to evaluate (bounded at load by drishti.rachana.max-expression-depth, so only if lifted)
            String error = e instanceof StackOverflowError ? ErrorCode.EL_EVAL.code() + " an expression of this panel is nested too "
                    + "deeply to evaluate (drishti.rachana.max-expression-depth)" : String.valueOf(e.getMessage());
            return new PanelView(p.id(), p.kind().id(), title, p.code(), p.key(), area(p), p.infer(), explanation, null,
                    error, true, p.span().orElse(null), p.height().orElse(null));
        }
    }

    private static String area(Panel p) {
        return p.area().name().toLowerCase(Locale.ROOT);
    }

    // ---- values -------------------------------------------------------------------------------

    Object eval(String expr, EvalContext ctx) {
        return el.compile(expr).eval(ctx);
    }

    /** A formatted, toned cell for {@code value}; links become navigable. */
    public Cell cell(String label, Object value, String fmt, String tone, boolean emphasis, String path) {
        Object v = Values.simplify(value);
        if (v instanceof Link l) {
            return new Cell(label, l.display(), null, linkView(l), emphasis, path);
        }
        return new Cell(label, formats.format(fmt, v), Tones.resolve(tone, v), null, emphasis, path);
    }

    public Cell cell(Column col, EvalContext ctx, String path) {
        Expr e = el.compile(col.bind());
        Object v = e.eval(ctx);
        Cell cell = cell(col.label(), v, col.fmt(), col.tone(), false, path);
        if ((col.link() || namesAnId(col.bind())) && cell.link() == null && !Values.isNull(v)) {
            String id = Values.text(v);
            cell = new Cell(cell.label(), cell.text(), cell.tone(), catalog.kindOf(id).map(k -> linkView(k, id)).orElse(null), false, path);
        }
        return cell;
    }

    /**
     * A column bound to an identifier field ({@code @.tradeId}, {@code $.counterpartyId}, {@code @.bookRef}) links its
     * value when the value is an id a pack recognises, without {@code link: true}: an id in a table opens its entity.
     */
    static boolean namesAnId(String bind) {
        if (bind == null || !bind.matches("[@$](\\.[A-Za-z_][A-Za-z0-9_]*|\\[\\d+\\])+")) {
            return false;
        }
        String last = bind.substring(bind.lastIndexOf('.') + 1);
        return last.length() > 2 && (last.endsWith("Id") || last.endsWith("Ref") || last.endsWith("_id"));
    }

    public LinkView linkView(Link l) {
        String kind = l.kind() != null ? l.kind() : catalog.kindOf(l.id()).orElse(null);
        return kind == null ? null : linkView(kind, l.id());
    }

    public LinkView linkView(String kind, String id) {
        return new LinkView(kind, id, mnemonics.codeFor(kind));
    }

    /** The first document path an expression reads, used to address live patches. */
    public String pathOf(String expr) {
        String[] first = {null};
        el.compile(expr).paths(p -> {
            if (first[0] == null && !"$".equals(p)) {
                first[0] = p;
            }
        });
        return first[0];
    }

    private DataNode node(Object v) {
        return v instanceof DataNode n ? n : DataNode.missing();
    }

    // ---- kinds --------------------------------------------------------------------------------

    private PanelData kv(Panel p, BindContext c) {
        EvalContext ctx = c.eval();
        Optional<String> rows = p.option("rows");
        if (rows.isPresent()) {
            ctx = ctx.withRow(eval(rows.get(), c.eval()), 0);
        }
        List<Cell> fields = new ArrayList<>();
        for (Column col : p.columns()) {
            fields.add(cell(col, ctx, rows.isPresent() ? null : pathOf(col.bind())));
        }
        return new PanelData.Fields(fields);
    }

    private PanelData status(Panel p, BindContext c) {
        List<Cell> fields = new ArrayList<>();
        if (p.options().get("fields") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    String bind = String.valueOf(m.get("bind"));
                    Object v = eval(bind, c.eval());
                    fields.add(cell(String.valueOf(m.get("label")), v, (String) m.get("fmt"), (String) m.get("tone"), false, pathOf(bind)));
                }
            }
        }
        return new PanelData.Fields(fields);
    }

    private PanelData table(Panel p, BindContext c) {
        DataNode rows = node(eval(p.option("rows").orElseThrow(), c.eval()));
        String rowsPath = pathOf(p.option("rows").get());
        int limit = p.options().get("limit") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
        List<Column> cols = p.columns();
        List<String> headers = cols.stream().map(Column::label).toList();
        List<Boolean> numeric = cols.stream().map(col -> col.fmt() != null && !col.fmt().equals("date") && !col.fmt().equals("text")).toList();
        String highlight = p.option("highlight").orElse(null);
        List<PanelData.Row> out = new ArrayList<>();
        double[] totals = new double[cols.size()];
        boolean[] masked = new boolean[cols.size()];          // a column with a masked value is never added up
        String childrenExpr = p.option("children").orElse(null);
        int[] budget = {limits.treeRows()};
        for (int i = 0; i < rows.size(); i++) {
            EvalContext rc = c.eval().withRow(rows.get(i), i);
            for (int k = 0; k < cols.size() && childrenExpr == null; k++) {
                if (cols.get(k).total() && !masked[k]) {
                    Object x = el.compile(cols.get(k).bind()).eval(rc);
                    masked[k] = Values.masked(x);
                    double v = Values.number(x);
                    totals[k] += Double.isNaN(v) ? 0 : v;
                }
            }
            if (i >= limit) {
                continue;
            }
            String rowPath = rowsPath == null ? null : rowsPath + "[" + i + "]";
            if (childrenExpr != null) {
                PanelData.Row tree = treeRow(cols, rc, rowPath, childrenExpr, highlight, totals, masked, i < limit, 0, budget);
                if (tree != null) {
                    out.add(tree);
                }
                continue;
            }
            List<Cell> cells = new ArrayList<>(cols.size());
            for (Column col : cols) {
                cells.add(cell(col, rc, null));
            }
            boolean hl = highlight != null && Values.truthy(eval(highlight, rc));
            out.add(new PanelData.Row(cells, hl, rowPath));
        }
        PanelData.Row total = null;
        if (cols.stream().anyMatch(Column::total)) {
            List<Cell> cells = new ArrayList<>();
            for (int k = 0; k < cols.size(); k++) {
                Column col = cols.get(k);
                Object sum = masked[k] ? Values.mask() : Values.normalise(totals[k]);
                cells.add(col.total() ? cell(null, sum, col.fmt(), col.tone(), false, null) : Cell.of(null, ""));
            }
            String label = p.option("totalLabel").orElse("Total");
            int at = firstBlankBefore(cols);
            cells.set(at, Cell.of(null, label));
            total = new PanelData.Row(cells, false, null);
        }
        String more = null;
        if (rows.size() > limit) {
            more = p.option("moreLabel").map(m -> Values.text(eval(m, c.eval()))).orElse((rows.size() - limit) + " more");
        }
        boolean search = !p.option("search").map(String::trim).filter(v -> v.equalsIgnoreCase("false") || v.equalsIgnoreCase("no")).isPresent();
        return new PanelData.Table(headers, numeric, out, total, more, search, pivots.view(p).orElse(null),
                childrenExpr == null ? null : ChartBinder.expandLevels(p));
    }

    /**
     * One row of a table or ladder with {@code children}, and the rows nested under it (the expression is evaluated for each,
     * with {@code @} the row). A {@code total: true} column adds up the rows that have no children; a masked value is never
     * added up. {@code emit} false walks the row for the totals only (a row beyond the table's {@code limit}).
     *
     * @param budget rows left to build in all (at the front), so a deep or cyclic document stays bounded
     * @return the row, or null when {@code emit} is false
     */
    private PanelData.Row treeRow(List<Column> cols, EvalContext rc, String path, String childrenExpr, String highlight, double[] totals,
            boolean[] masked, boolean emit, int depth, int[] budget) {
        List<PanelData.Row> sub = new ArrayList<>();
        DataNode kids = depth < limits.treeDepth() ? node(eval(childrenExpr, rc)) : DataNode.missing();
        int walked = 0;
        for (int j = 0; j < kids.size() && budget[0] > 0; j++) {
            budget[0]--;
            walked++;
            PanelData.Row child = treeRow(cols, rc.withRow(kids.get(j), j), null, childrenExpr, highlight, totals, masked, emit, depth + 1, budget);
            if (child != null) {
                sub.add(child);
            }
        }
        if (walked == 0) {                                    // a leaf: the only rows a total adds up
            for (int k = 0; k < cols.size(); k++) {
                if (cols.get(k).total() && !masked[k]) {
                    Object x = el.compile(cols.get(k).bind()).eval(rc);
                    masked[k] = Values.masked(x);
                    double v = Values.number(x);
                    totals[k] += Double.isNaN(v) ? 0 : v;
                }
            }
        }
        if (!emit) {
            return null;
        }
        List<Cell> cells = new ArrayList<>(cols.size());
        for (Column col : cols) {
            cells.add(cell(col, rc, null));
        }
        boolean hl = highlight != null && Values.truthy(eval(highlight, rc));
        return new PanelData.Row(cells, hl, path, sub);
    }

    /** Where the total label goes: the column just before the first totalled column, else the first. */
    private static int firstBlankBefore(List<Column> cols) {
        for (int k = 0; k < cols.size(); k++) {
            if (cols.get(k).total()) {
                return Math.max(0, k - 1);
            }
        }
        return 0;
    }

    private PanelData tabs(Panel p, BindContext c) {
        DataNode each = node(eval(p.option("each").orElseThrow(), c.eval()));
        String tabTitle = p.option("tabTitle").orElse("'#' + (#index + 1)");
        List<PanelData.Tab> tabs = new ArrayList<>();
        for (int i = 0; i < each.size(); i++) {
            EvalContext rc = c.eval().withRow(each.get(i), i);
            List<Cell> fields = new ArrayList<>();
            if (p.body() != null) {
                for (Column col : p.body().columns()) {
                    fields.add(cell(col, rc, null));
                }
            }
            tabs.add(new PanelData.Tab(Values.text(eval(tabTitle, rc)), fields));
        }
        return new PanelData.Tabs(p.option("layout").orElse("tabs"), tabs);
    }

    /** The linked entity a chart reads its points from, when it names one with {@code source}. */
    public Optional<EntityRef> chartSource(Panel p, EvalContext ctx) {
        return p.option("source").map(s -> Values.simplify(eval(s, ctx))).filter(Link.class::isInstance).map(Link.class::cast)
                .map(this::linkView).map(lv -> lv == null ? null : EntityRef.of(lv.kind(), lv.id()));
    }

    private PanelData line(Panel p, BindContext c) {
        EvalContext data = c.eval();
        LinkView source = null;
        Optional<EntityRef> src = chartSource(p, c.eval());
        if (src.isPresent()) {
            EntityDocument d = c.linked().get(src.get());
            if (d == null) {
                throw new IllegalStateException(c.pending().contains(src.get()) ? "waiting for " + src.get().id() : src.get().id() + " unavailable");
            }
            data = EvalContext.of(d.data(), formats);
            source = linkView(src.get().kind(), src.get().id());
        }
        DataNode rows = node(eval(p.option("rows").orElse("points"), data));
        String x = p.option("x").orElse("tenor");
        String y = p.option("y").orElse("value");
        List<String> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            xs.add(rows.get(i).get(x).asText());
            ys.add(rows.get(i).get(y).asDouble());
        }
        String mark = p.option("mark").map(m -> Values.text(eval(m, c.eval()))).filter(s -> !s.isEmpty()).orElse(null);
        String markText = null;
        if (mark != null && xs.contains(mark)) {
            double v = ys.get(xs.indexOf(mark));
            String unit = p.option("unit").orElse("");
            markText = mark + " point " + formats.format(p.option("fmt").orElse(null), Values.normalise(v))
                    + (unit.isEmpty() || unit.equals("%") ? unit : " " + unit);
        }
        return new PanelData.Chart(xs, List.of(new PanelData.Series(p.title(), ys, "link")), mark, markText, null, null, source,
                p.option("unit").orElse(null));
    }

    private PanelData area(Panel p, BindContext c) {
        DataNode rows = node(eval(p.option("rows").orElseThrow(), c.eval()));
        String x = p.option("x").orElse("tenor");
        List<String> xs = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            xs.add(rows.get(i).get(x).asText());
        }
        List<PanelData.Series> series = new ArrayList<>();
        if (p.options().get("series") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    String field = String.valueOf(m.get("value"));
                    List<Double> values = new ArrayList<>();
                    for (int i = 0; i < rows.size(); i++) {
                        values.add(rows.get(i).get(field).asDouble());
                    }
                    series.add(new PanelData.Series(String.valueOf(m.get("label")), values, (String) m.get("tone")));
                }
            }
        }
        Double limit = p.option("limit").map(l -> Values.number(eval(l, c.eval()))).filter(d -> !d.isNaN()).orElse(null);
        return new PanelData.Chart(xs, series, null, null, limit, p.option("limitLabel").orElse(null), null, p.option("unit").orElse(null));
    }

    private PanelData bars(Panel p, BindContext c) {
        DataNode rows = node(eval(p.option("rows").orElseThrow(), c.eval()));
        String label = p.option("label").orElse("label");
        String value = p.option("value").orElse("value");
        String fmt = p.option("fmt").orElse(null);
        String tone = p.option("tone").orElse(null);
        List<PanelData.Bar> bars = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            DataNode r = rows.get(i);
            double v = Values.number(r.get(value));
            if (!Double.isFinite(v)) {
                continue;   // a row without a usable number is not a bar
            }
            bars.add(new PanelData.Bar(r.get(label).asText(), v, formats.format(fmt, Values.normalise(v)),
                    tone == null ? (v < 0 ? "neg" : "pos") : Tones.resolve(tone, v)));
        }
        return new PanelData.Bars(bars);
    }

    private PanelData links(BindContext c) {
        List<PanelData.LinkItem> items = new ArrayList<>();
        for (LinkRef r : c.links()) {
            EntityDocument d = c.linked().get(r.target());
            String status = d != null ? "resolved" : c.pending().contains(r.target()) ? "pending" : "missing";
            String badge = d == null ? "" : badges.badge(r.target().kind(), d.data());
            items.add(new PanelData.LinkItem(r.label(), r.target().id(), linkView(r.target().kind(), r.target().id()), badge, status));
        }
        return new PanelData.Links(items);
    }

    private PanelData provenance(BindContext c) {
        var pv = c.doc().provenance();
        return new PanelData.Fields(List.of(
                Cell.of("Layout", c.layout().label()),
                Cell.of("Fingerprint", c.fingerprint().shortForm()),
                Cell.of("Source", pv.source() + ", gen " + pv.generation())));
    }

    private PanelData gauge(Panel p, BindContext c) {
        Object value = eval(p.option("value").orElseThrow(), c.eval());
        double v = Values.number(value);
        double max = p.option("max").map(m -> Values.number(eval(m, c.eval()))).filter(Double::isFinite).orElse(1.0);
        String fmt = p.option("fmt").orElse("pct0");
        if (!Double.isFinite(v)) {
            return new PanelData.Gauge(Double.NaN, max, Values.masked(value) ? DataNode.MASK : "—", p.option("label").orElse(null));
        }
        return new PanelData.Gauge(v, max, formats.format(fmt, v / (max == 0 ? 1 : max)), p.option("label").orElse(null));
    }

    private PanelData surface(Panel p, BindContext c) {
        DataNode rows = node(eval(p.option("rows").orElseThrow(), c.eval()));
        String y = p.option("y").orElseThrow();
        List<Column> cols = p.columns();
        List<String> ys = new ArrayList<>();
        List<List<Double>> z = new ArrayList<>();
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < rows.size(); i++) {
            EvalContext rc = c.eval().withRow(rows.get(i), i);
            Object label = y.startsWith("@") || y.startsWith("$") ? eval(y, rc) : rows.get(i).get(y);
            ys.add(Values.text(Values.simplify(label)));
            List<Double> row = new ArrayList<>(cols.size());
            for (Column col : cols) {
                double v = Values.number(el.compile(col.bind()).eval(rc));
                row.add(Double.isFinite(v) ? v : null);
                if (Double.isFinite(v)) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
            }
            z.add(row);
        }
        return new PanelData.Surface(cols.stream().map(Column::label).toList(), ys, z, Double.isFinite(lo) ? lo : null,
                Double.isFinite(hi) ? hi : null, p.option("fmt").orElse(null), p.option("unit").orElse(null), p.option("view").orElse("heatmap"));
    }

    /** True for panel kinds that consume linked documents while binding. */
    public static boolean needsLinks(PanelKind k) {
        return k == PanelKind.LINE || k == PanelKind.LINKS;
    }
}
