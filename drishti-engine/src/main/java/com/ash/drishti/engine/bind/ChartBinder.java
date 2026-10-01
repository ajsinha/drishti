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
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.LinkView;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.rachana.el.Link;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.format.Tones;
import com.ash.drishti.rachana.model.Panel;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Binds the chart and aggregate panel kinds ({@code waterfall}, {@code histogram}, {@code scatter},
 * {@code candlestick}, {@code graph}, {@code timeline}, {@code pivot}) to a document. Each reads a list the Sutra
 * names, picks fields from its elements (a field name, a dotted path, or an expression over the row such as
 * {@code "@.dv01 * -1"}) and computes what the console draws: running totals, bins, aggregates. Rows without a usable
 * number are left out, never errors, and every kind stops at the {@link PanelLimits} it is given.
 *
 * <p>Stateless and thread-safe.
 */
final class ChartBinder {

    private static final MathContext SIGNIFICANT = new MathContext(6);

    private final Binder binder;
    private final Formats formats;
    private final ReferenceCatalog catalog;
    private final PanelLimits limits;

    ChartBinder(Binder binder, Formats formats, ReferenceCatalog catalog, PanelLimits limits) {
        this.binder = binder;
        this.formats = formats;
        this.catalog = catalog;
        this.limits = limits;
    }

    // ---- shared -------------------------------------------------------------------------------

    private DataNode list(Panel p, String option, BindContext c) {
        Object v = binder.eval(p.option(option).orElseThrow(), c.eval());
        return v instanceof DataNode n ? n : DataNode.missing();
    }

    /** A row's value: an expression over the row when {@code spec} reads like one, else a field or dotted path. */
    private Object field(String spec, DataNode row, int index, BindContext c) {
        if (spec == null) {
            return null;
        }
        if (spec.startsWith("@") || spec.startsWith("$") || spec.indexOf('(') >= 0 || spec.indexOf(' ') >= 0) {
            return binder.eval(spec, c.eval().withRow(row, index));
        }
        return spec.indexOf('.') >= 0 ? row.at(spec) : row.get(spec);
    }

    private static String text(Object v) {
        return Values.text(Values.simplify(v));
    }

    /** {@code v} in format {@code fmt}; with no format, six significant digits (bin edges must not print 0.30000000000000004). */
    private String format(String fmt, double v) {
        if (fmt == null) {
            return Double.isFinite(v) ? new BigDecimal(v).round(SIGNIFICANT).stripTrailingZeros().toPlainString() : "—";
        }
        return formats.format(fmt, Values.normalise(v));
    }

    /** The entity a value names: a link, or an id a pack recognises; null otherwise. */
    private LinkView linkOf(Object value) {
        Object v = Values.simplify(value);
        if (v instanceof Link l) {
            return binder.linkView(l);
        }
        String id = text(v);
        return id.isEmpty() ? null : catalog.kindOf(id).map(k -> binder.linkView(k, id)).orElse(null);
    }

    // ---- waterfall ----------------------------------------------------------------------------

    PanelData waterfall(Panel p, BindContext c) {
        DataNode rows = list(p, "rows", c);
        String label = p.option("label").orElse("label");
        String value = p.option("value").orElse("value");
        String total = p.option("total").orElse("total");
        String fmt = p.option("fmt").orElse(null);
        List<PanelData.Step> steps = new ArrayList<>();
        double run = 0;
        int n = Math.min(rows.size(), limits.maxPoints());
        for (int i = 0; i < n; i++) {
            DataNode row = rows.get(i);
            double v = Values.number(field(value, row, i, c));
            if (!Double.isFinite(v)) {
                continue;   // a step without a number is not a bar
            }
            String l = text(field(label, row, i, c));
            if (Values.truthy(field(total, row, i, c))) {
                steps.add(new PanelData.Step(l, v, 0, v, format(fmt, v), "link", true));
                run = v;
            } else {
                steps.add(new PanelData.Step(l, v, run, run + v, format(fmt, v), v < 0 ? "neg" : "pos", false));
                run += v;
            }
        }
        String sum = p.option("sum").orElse(null);
        if (sum != null && !steps.isEmpty()) {
            steps.add(new PanelData.Step(sum, run, 0, run, format(fmt, run), "link", true));
        }
        return new PanelData.Waterfall(steps, p.option("unit").orElse(null));
    }

    // ---- histogram ----------------------------------------------------------------------------

    PanelData histogram(Panel p, BindContext c) {
        DataNode rows = list(p, "rows", c);
        String value = p.option("value").orElse(null);
        String fmt = p.option("fmt").orElse(null);
        double[] xs = new double[Math.min(rows.size(), limits.maxValues())];
        int count = 0;
        int dropped = Math.max(0, rows.size() - xs.length);
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < xs.length; i++) {
            double v = Values.number(value == null ? rows.get(i) : field(value, rows.get(i), i, c));
            if (!Double.isFinite(v)) {
                dropped++;
                continue;
            }
            xs[count++] = v;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        List<PanelData.Marker> markers = markers(p, c, fmt);
        if (count == 0) {
            return new PanelData.Histogram(List.of(), markers, 0, dropped, fmt, p.option("unit").orElse(null));
        }
        int k = p.options().get("bins") instanceof Number b ? b.intValue() : (int) Math.max(5, Math.min(40, Math.round(Math.sqrt(count))));
        if (hi == lo) {
            k = 1;
        }
        double width = (hi - lo) / k;
        int[] counts = new int[k];
        for (int i = 0; i < count; i++) {
            int at = width == 0 ? 0 : (int) ((xs[i] - lo) / width);
            counts[Math.min(k - 1, Math.max(0, at))]++;
        }
        List<PanelData.Bin> bins = new ArrayList<>(k);
        for (int b = 0; b < k; b++) {
            double from = lo + b * width;
            double to = b == k - 1 ? hi : lo + (b + 1) * width;
            bins.add(new PanelData.Bin(from, to, counts[b], format(fmt, from) + " to " + format(fmt, to)));
        }
        return new PanelData.Histogram(bins, markers, count, dropped, fmt, p.option("unit").orElse(null));
    }

    private List<PanelData.Marker> markers(Panel p, BindContext c, String fmt) {
        List<PanelData.Marker> out = new ArrayList<>();
        if (p.options().get("markers") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("value") != null) {
                    double v;
                    try {
                        v = Values.number(binder.eval(String.valueOf(m.get("value")), c.eval()));
                    } catch (RuntimeException e) {
                        v = Double.NaN;     // a marker the document cannot give is left out, the histogram still draws
                    }
                    if (Double.isFinite(v)) {
                        String tone = m.get("tone") == null ? "accent" : Tones.resolve(String.valueOf(m.get("tone")), v);
                        out.add(new PanelData.Marker(m.get("label") == null ? null : String.valueOf(m.get("label")), v, format(fmt, v),
                                tone == null ? "accent" : tone));
                    }
                }
            }
        }
        return out;
    }

    // ---- scatter ------------------------------------------------------------------------------

    PanelData scatter(Panel p, BindContext c) {
        DataNode rows = list(p, "rows", c);
        String x = p.option("x").orElseThrow();
        String y = p.option("y").orElseThrow();
        String size = p.option("size").orElse(null);
        String label = p.option("label").orElse(null);
        String group = p.option("group").orElse(null);
        String fmt = p.option("fmt").orElse(null);
        String xFmt = p.option("xFmt").orElse(fmt);
        Set<String> groups = new LinkedHashSet<>();
        List<PanelData.Point> points = new ArrayList<>();
        int more = 0;
        for (int i = 0; i < rows.size(); i++) {
            DataNode row = rows.get(i);
            double xv = Values.number(field(x, row, i, c));
            double yv = Values.number(field(y, row, i, c));
            if (!Double.isFinite(xv) || !Double.isFinite(yv)) {
                continue;
            }
            if (points.size() >= limits.maxPoints()) {
                more++;
                continue;
            }
            Double sv = null;
            if (size != null) {
                double s = Values.number(field(size, row, i, c));
                sv = Double.isFinite(s) ? Math.abs(s) : null;
            }
            Object lv = label == null ? null : field(label, row, i, c);
            String g = group == null ? null : text(field(group, row, i, c));
            if (g != null && !g.isEmpty()) {
                groups.add(g);
            }
            points.add(new PanelData.Point(xv, yv, sv, format(xFmt, xv), format(fmt, yv), lv == null ? null : text(lv),
                    g == null || g.isEmpty() ? null : g, lv == null ? null : linkOf(lv)));
        }
        return new PanelData.Scatter(points, List.copyOf(groups), p.option("xLabel").orElse(x), p.option("yLabel").orElse(y), size != null, more);
    }

    // ---- candlestick --------------------------------------------------------------------------

    PanelData candlestick(Panel p, BindContext c) {
        DataNode rows = list(p, "rows", c);
        String x = p.option("x").orElse("date");
        String[] ohlc = {p.option("open").orElse("open"), p.option("high").orElse("high"), p.option("low").orElse("low"),
            p.option("close").orElse("close")};
        String volume = p.option("volume").orElse(null);
        String fmt = p.option("fmt").orElse(null);
        List<PanelData.Candle> candles = new ArrayList<>();
        boolean anyVolume = false;
        for (int i = Math.max(0, rows.size() - limits.maxPoints()); i < rows.size(); i++) {
            DataNode row = rows.get(i);
            double[] v = new double[4];
            boolean ok = true;
            for (int k = 0; k < 4; k++) {
                v[k] = Values.number(field(ohlc[k], row, i, c));
                ok &= Double.isFinite(v[k]);
            }
            if (!ok) {
                continue;   // a bar needs all four prices
            }
            Double vol = null;
            if (volume != null) {
                double d = Values.number(field(volume, row, i, c));
                vol = Double.isFinite(d) ? d : null;
                anyVolume |= vol != null;
            }
            candles.add(new PanelData.Candle(text(field(x, row, i, c)), v[0], Math.max(v[1], Math.max(v[0], v[3])),
                    Math.min(v[2], Math.min(v[0], v[3])), v[3], vol));
        }
        String last = null;
        String change = null;
        String tone = null;
        if (!candles.isEmpty()) {
            PanelData.Candle end = candles.get(candles.size() - 1);
            double prev = candles.size() > 1 ? candles.get(candles.size() - 2).close() : end.open();
            last = format(fmt, end.close());
            double d = end.close() - prev;
            change = (d > 0 ? "+" : d < 0 ? "−" : "") + format(fmt, Math.abs(d)) + (prev == 0 ? "" : " (" + (d > 0 ? "+" : d < 0 ? "−" : "")
                    + formats.format("pct2", Math.abs(d / prev)) + ")");
            tone = Tones.resolve("sign", d);
        }
        return new PanelData.Candles(candles, anyVolume, last, change, tone, fmt, p.option("unit").orElse(null));
    }

    // ---- graph --------------------------------------------------------------------------------

    PanelData graph(Panel p, BindContext c) {
        DataNode nodes = list(p, "nodes", c);
        DataNode edges = p.option("edges").isPresent() ? list(p, "edges", c) : DataNode.missing();
        String label = p.option("label").orElse("label");
        String group = p.option("group").orElse("type");
        String self = c.doc().ref().id();
        List<PanelData.Node> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int more = 0;
        for (int i = 0; i < nodes.size(); i++) {
            DataNode n = nodes.get(i);
            String id = n.get("id").asText();
            if (id.isEmpty() || !ids.add(id)) {
                continue;   // a node needs an id, once
            }
            if (out.size() >= limits.maxNodes()) {
                more++;
                ids.remove(id);
                continue;
            }
            String kind = n.get("kind").asText();
            LinkView link = kind.isEmpty() ? linkOf(id) : binder.linkView(kind, id);
            String l = text(field(label, n, i, c));
            String g = text(field(group, n, i, c));
            out.add(new PanelData.Node(id, l.isEmpty() ? id : l, g.isEmpty() ? null : g, link, id.equals(self)));
        }
        List<PanelData.Edge> lines = new ArrayList<>();
        for (int i = 0; i < edges.size() && lines.size() < 2 * limits.maxNodes(); i++) {
            DataNode e = edges.get(i);
            String from = e.get("from").asText();
            String to = e.get("to").asText();
            if (ids.contains(from) && ids.contains(to)) {
                String l = e.get("label").asText();
                lines.add(new PanelData.Edge(from, to, l.isEmpty() ? null : l));
            }
        }
        return new PanelData.Graph(out, lines, p.option("layout").orElse("tree"), more);
    }

    // ---- timeline -----------------------------------------------------------------------------

    PanelData timeline(Panel p, BindContext c) {
        DataNode rows = list(p, "rows", c);
        String date = p.option("date").orElse("date");
        String label = p.option("label").orElse("event");
        String detail = p.option("detail").orElse("description");
        String status = p.option("status").orElse("status");
        String tone = p.option("tone").orElse("status");
        List<PanelData.Event> events = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            DataNode row = rows.get(i);
            String d = text(field(date, row, i, c));
            String l = text(field(label, row, i, c));
            if (d.isEmpty() && l.isEmpty()) {
                continue;
            }
            Object sv = field(status, row, i, c);
            String s = text(sv);
            String dt = text(field(detail, row, i, c));
            events.add(new PanelData.Event(d, l, dt.isEmpty() ? null : dt, s.isEmpty() ? null : s, s.isEmpty() ? null : Tones.resolve(tone, sv)));
        }
        // ISO dates and timestamps sort as text; undated events keep their place at the start
        events.sort(Comparator.comparing(PanelData.Event::date));
        int more = Math.max(0, events.size() - limits.maxEvents());
        return new PanelData.Timeline(more == 0 ? events : List.copyOf(events.subList(more, events.size())), more);
    }

    // ---- pivot --------------------------------------------------------------------------------

    /** Count, sum, minimum and maximum of the values that fall in one cell, row, column or the whole grid. */
    private static final class Acc {
        int n;
        double sum;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        void add(double v) {
            n++;
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        double get(String agg) {
            return switch (agg) {
                case "count" -> n;
                case "avg" -> n == 0 ? Double.NaN : sum / n;
                case "min" -> n == 0 ? Double.NaN : min;
                case "max" -> n == 0 ? Double.NaN : max;
                default -> sum;
            };
        }
    }

    PanelData pivot(Panel p, BindContext c) {
        DataNode rows = list(p, "rows", c);
        String by = p.option("by").orElseThrow();
        String across = p.option("across").orElseThrow();
        String value = p.option("value").orElse(null);
        String agg = value == null ? "count" : p.option("agg").orElse("sum");
        String fmt = agg.equals("count") ? null : p.option("fmt").orElse(null);
        String tone = p.option("tone").orElse(null);
        boolean heat = Boolean.TRUE.equals(p.options().get("heat"));
        boolean totals = !Boolean.FALSE.equals(p.options().get("totals"));
        Map<String, Map<String, Acc>> grid = new LinkedHashMap<>();
        Map<String, Acc> rowAcc = new LinkedHashMap<>();
        Map<String, Acc> colAcc = new LinkedHashMap<>();
        Acc all = new Acc();
        int n = Math.min(rows.size(), limits.maxValues());
        for (int i = 0; i < n; i++) {
            DataNode row = rows.get(i);
            double v = value == null ? 1 : Values.number(field(value, row, i, c));
            if (!Double.isFinite(v)) {
                continue;
            }
            String r = key(field(by, row, i, c));
            String col = key(field(across, row, i, c));
            boolean shown = colAcc.containsKey(col) || colAcc.size() < limits.pivotColumns();
            rowAcc.computeIfAbsent(r, k -> new Acc()).add(v);
            all.add(v);
            if (shown) {
                colAcc.computeIfAbsent(col, k -> new Acc()).add(v);
                grid.computeIfAbsent(r, k -> new LinkedHashMap<>()).computeIfAbsent(col, k -> new Acc()).add(v);
            }
        }
        List<String> columns = List.copyOf(colAcc.keySet());
        List<PanelData.PivotRow> out = new ArrayList<>();
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Acc> e : rowAcc.entrySet()) {
            if (out.size() >= limits.pivotRows()) {
                break;
            }
            Map<String, Acc> cells = grid.getOrDefault(e.getKey(), Map.of());
            List<Cell> texts = new ArrayList<>(columns.size());
            List<Double> values = new ArrayList<>(columns.size());
            for (String col : columns) {
                Acc a = cells.get(col);
                double v = a == null ? Double.NaN : a.get(agg);
                if (Double.isFinite(v)) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                    texts.add(cell(v, fmt, tone));
                    values.add(v);
                } else {
                    texts.add(Cell.of(null, ""));
                    values.add(null);
                }
            }
            out.add(new PanelData.PivotRow(e.getKey(), texts, values, totals ? cell(e.getValue().get(agg), fmt, tone) : null));
        }
        List<Cell> foot = null;
        if (totals) {
            foot = new ArrayList<>();
            for (String col : columns) {
                foot.add(cell(colAcc.get(col).get(agg), fmt, tone));
            }
            foot.add(cell(all.get(agg), fmt, tone));
        }
        return new PanelData.Pivot(by, columns, out, foot, agg, heat, Double.isFinite(lo) ? lo : null, Double.isFinite(hi) ? hi : null,
                rowAcc.size() - out.size());
    }

    private static String key(Object v) {
        String s = text(v);
        return s.isEmpty() ? "(none)" : s;
    }

    private Cell cell(double v, String fmt, String tone) {
        return Double.isFinite(v) ? new Cell(null, format(fmt, v), Tones.resolve(tone, v), null, false, null) : Cell.of(null, "");
    }
}
