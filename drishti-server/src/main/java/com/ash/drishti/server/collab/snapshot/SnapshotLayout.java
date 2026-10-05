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
package com.ash.drishti.server.collab.snapshot;

import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays a {@link ViewModel} out as a {@link SnapshotModel}: the title, the strip, then the panels in order, then the watermark band and a
 * tiled diagonal watermark. Text, key-value, table, metric, list and timeline panels are drawn as they read; line charts, bars,
 * histograms, waterfalls and gauges as simple marks; a chart kind with no simple drawing (surface, scatter, candles, graph) as a
 * labelled placeholder that says to open it in Drishti. A panel the viewer-set may not open ({@code denied}) is never drawn, not even
 * its title. Sizes are limits: past {@code maxHeight} the remaining panels are replaced by one line saying how many were left out.
 *
 * <p>Text width is estimated from the font size (no font is loaded here), so a layout is a pure function of its inputs.
 */
public final class SnapshotLayout {

    /** What the watermark says. {@code recipients} is already "3 recipients" or a list of names; {@code asOf} the data's date or "live". */
    public record Watermark(String sender, String recipients, String when, String asOf, long generation, String link) {

        /** The two band lines. */
        List<String> lines() {
            return List.of("Shared by " + sender + " with " + recipients + " on " + when,
                    "Data as of " + asOf + " · generation " + generation + " · " + link);
        }

        String tile() {
            return sender + " · " + recipients + " · " + when;
        }
    }

    private static final int M = 16;
    private static final int ROW = 18;
    private static final int INK = 0x1B2330;
    private static final int GREY = 0x6B7686;
    private static final int RULE = 0xD5DAE1;
    private static final int BAR = 0x4F7CAC;
    private static final int POS = 0x1E8E3E;
    private static final int NEG = 0xC5221F;
    private static final int BAND = 0xEEF1F5;
    private static final int BAND_HEIGHT = 46;

    private final int width;
    private final int maxHeight;
    private final int maxPanels;
    private final int maxRows;

    public SnapshotLayout(int width, int maxHeight, int maxPanels, int maxRows) {
        this.width = width;
        this.maxHeight = maxHeight;
        this.maxPanels = maxPanels;
        this.maxRows = maxRows;
    }

    /** Builds the picture of the view; {@code onlyPanel} (or null) restricts it to one panel by id. */
    public SnapshotModel build(ViewModel v, String onlyPanel, Watermark w) {
        Pen pen = new Pen();
        int y = header(pen, v);
        List<PanelView> shown = new ArrayList<>();
        for (PanelView p : v.panels()) {                 // a denied panel is not a panel of this picture
            if (p.denied() == null && (onlyPanel == null || onlyPanel.equals(p.id()))) {
                shown.add(p);
            }
        }
        shown.sort((a, b) -> Boolean.compare(!"main".equals(a.area()), !"main".equals(b.area())));
        int drawn = 0;
        int left = 0;
        for (int i = 0; i < shown.size(); i++) {
            Pen sub = new Pen();
            int end = i >= maxPanels ? Integer.MAX_VALUE : panel(sub, shown.get(i), y);
            if (end + BAND_HEIGHT > maxHeight) {
                left = shown.size() - drawn;
                break;
            }
            pen.items.addAll(sub.items);
            y = end + 8;
            drawn++;
        }
        if (left > 0) {
            pen.text(M, y + 12, "… " + left + (left == 1 ? " more panel" : " more panels") + " not shown: open the view in Drishti", 11, false, GREY);
            y += ROW + 4;
        }
        int height = Math.min(maxHeight, Math.max(160, y + 8 + BAND_HEIGHT));
        pen.items.add(new SnapshotModel.Box(0, height - BAND_HEIGHT, width, BAND_HEIGHT, BAND, true));
        List<String> lines = w.lines();
        pen.text(M, height - BAND_HEIGHT + 18, fit(lines.get(0), width - 2 * M, 11), 11, true, INK);
        pen.text(M, height - BAND_HEIGHT + 36, fit(lines.get(1), width - 2 * M, 10), 10, false, GREY);
        tiles(pen, height - BAND_HEIGHT, w.tile());
        return new SnapshotModel(width, height, pen.items);
    }

    // ---- watermark ----------------------------------------------------------------------------------------------

    private void tiles(Pen pen, int bottom, String tile) {
        String t = tile.length() > 70 ? tile.substring(0, 69) + "…" : tile;
        for (int y = 90; y < bottom + 120; y += 170) {
            for (int x = -40; x < width; x += 430) {
                pen.items.add(new SnapshotModel.Text(x + (y / 170 % 2) * 200, y, t, 20, true, false, 0x888888, -28, 0.16f));
            }
        }
    }

    // ---- header ---------------------------------------------------------------------------------------------------

    private int header(Pen pen, ViewModel v) {
        ViewModel.TitleView t = v.title();
        String head = (t.pill() == null || t.pill().isBlank() ? "" : t.pill() + "  ") + (t.id() == null ? "" : t.id());
        pen.text(M, 28, fit(head, width - 2 * M, 18), 18, true, INK);
        int y = 36;
        if (t.with() != null && t.with().text() != null && !t.with().text().isBlank()) {
            pen.text(M, y + 12, fit(t.with().text(), width - 2 * M, 12), 12, false, GREY);
            y += 18;
        }
        List<Cell> strip = v.strip() == null ? List.of() : v.strip();
        int cols = Math.max(1, (width - 2 * M) / 150);
        int colW = (width - 2 * M) / cols;
        for (int i = 0; i < strip.size() && i < cols * 3; i++) {
            Cell c = strip.get(i);
            int x = M + (i % cols) * colW;
            int yy = y + 6 + (i / cols) * 38;
            pen.text(x, yy + 10, fit(nz(c.label()), colW - 8, 10), 10, false, GREY);
            pen.text(x, yy + 28, fit(nz(c.text()), colW - 8, 14), 14, true, tone(c.tone()));
        }
        int rows = strip.isEmpty() ? 0 : Math.min(3, (strip.size() + cols - 1) / cols);
        y += 10 + rows * 38;
        pen.items.add(new SnapshotModel.Line(M, y, width - M, y, RULE));
        return y + 10;
    }

    // ---- panels ----------------------------------------------------------------------------------------------------

    private int panel(Pen pen, PanelView p, int y0) {
        int y = y0;
        pen.items.add(new SnapshotModel.Box(M, y, width - 2 * M, 22, BAND, true));
        pen.text(M + 6, y + 16, fit(nz(p.title()), width - 2 * M - 12, 13), 13, true, INK);
        y += 30;
        if (p.error() != null) {
            return line(pen, y, "This panel could not be built.", GREY);
        }
        if (p.empty() || p.data() == null) {
            return line(pen, y, "No data available", GREY);
        }
        return switch (p.data()) {
            case PanelData.Fields f -> fields(pen, y, f.fields(), maxRows * 2);
            case PanelData.Table t -> table(pen, y, t);
            case PanelData.Tabs t -> tabs(pen, y, t);
            case PanelData.Text t -> wrapped(pen, y, nz(t.text()));
            case PanelData.Metric m -> metric(pen, y, m);
            case PanelData.Gauge g -> gauge(pen, y, g);
            case PanelData.Bars b -> bars(pen, y, b.bars().stream().map(x -> new Bar(x.label(), x.value(), x.text(), x.tone())).toList());
            case PanelData.Waterfall wf -> bars(pen, y, wf.steps().stream().map(x -> new Bar(x.label(), x.value(), x.text(), x.tone())).toList());
            case PanelData.Histogram h -> histogram(pen, y, h);
            case PanelData.Chart c -> chart(pen, y, c);
            case PanelData.Links l -> list(pen, y, l.links().stream().map(i -> nz(i.label()) + "   " + nz(i.text())).toList());
            case PanelData.Timeline t -> list(pen, y, t.events().stream().map(e -> nz(e.date()) + "   " + nz(e.label())
                    + (e.detail() == null || e.detail().isBlank() ? "" : " — " + e.detail())).toList());
            case PanelData.Pivot pv -> pivot(pen, y, pv);
            default -> placeholder(pen, y, p.kind());
        };
    }

    private int line(Pen pen, int y, String text, int color) {
        pen.text(M + 6, y + 12, fit(text, width - 2 * M - 12, 12), 12, false, color);
        return y + ROW;
    }

    private int fields(Pen pen, int y, List<Cell> cells, int cap) {
        int colW = (width - 2 * M) / 2;
        int n = Math.min(cells.size(), cap);
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            int x = M + 6 + (i % 2) * colW;
            int yy = y + (i / 2) * ROW;
            int labelW = colW * 2 / 5;
            pen.text(x, yy + 12, fit(nz(c.label()), labelW - 6, 11), 11, false, GREY);
            pen.text(x + labelW, yy + 12, fit(nz(c.text()), colW - labelW - 12, 12), 12, c.emphasis(), tone(c.tone()));
        }
        int end = y + ((n + 1) / 2) * ROW;
        if (cells.size() > n) {
            pen.text(M + 6, end + 12, "… " + (cells.size() - n) + " more fields", 10, false, GREY);
            end += ROW;
        }
        return end + 4;
    }

    private int table(Pen pen, int y, PanelData.Table t) {
        List<String> cols = t.columns() == null ? List.of() : t.columns();
        List<PanelData.Row> rows = new ArrayList<>(t.rows() == null ? List.of() : t.rows());
        int n = Math.min(cols.size(), 8);
        if (n == 0) {
            return line(pen, y, "No data available", GREY);
        }
        List<Boolean> num = t.numeric() == null ? List.of() : t.numeric();
        int colW = (width - 2 * M - 12) / n;
        for (int c = 0; c < n; c++) {
            cell(pen, M + 6 + c * colW, y + 12, colW, cols.get(c), num.size() > c && num.get(c), 11, true, INK);
        }
        pen.items.add(new SnapshotModel.Line(M, y + 17, width - M, y + 17, RULE));
        y += ROW + 2;
        int shown = Math.min(rows.size(), maxRows);
        for (int r = 0; r < shown; r++) {
            y = tableRow(pen, y, rows.get(r), n, colW, num, false);
        }
        if (t.total() != null) {
            y = tableRow(pen, y, t.total(), n, colW, num, true);
        }
        if (rows.size() > shown || (t.more() != null && !t.more().isBlank())) {
            pen.text(M + 6, y + 12, rows.size() > shown ? "… " + (rows.size() - shown) + " more rows" : t.more(), 10, false, GREY);
            y += ROW;
        }
        return y + 4;
    }

    private int tableRow(Pen pen, int y, PanelData.Row row, int n, int colW, List<Boolean> num, boolean bold) {
        for (int c = 0; c < n && c < row.cells().size(); c++) {
            Cell cell = row.cells().get(c);
            cell(pen, M + 6 + c * colW, y + 12, colW, nz(cell.text()), num.size() > c && num.get(c), 11, bold || cell.emphasis(), tone(cell.tone()));
        }
        return y + ROW;
    }

    private void cell(Pen pen, int x, int baseline, int colW, String text, boolean right, int size, boolean bold, int color) {
        String s = fit(text, colW - 8, size);
        int tx = right ? x + colW - 8 - (int) (s.length() * size * 0.58) : x;
        pen.text(Math.max(x, tx), baseline, s, size, bold, color);
    }

    private int tabs(Pen pen, int y, PanelData.Tabs t) {
        int n = Math.min(t.tabs().size(), 4);
        for (int i = 0; i < n; i++) {
            PanelData.Tab tab = t.tabs().get(i);
            pen.text(M + 6, y + 12, fit(nz(tab.title()), width - 2 * M - 12, 12), 12, true, GREY);
            y = fields(pen, y + ROW, tab.fields() == null ? List.of() : tab.fields(), 6);
        }
        return y;
    }

    private int wrapped(Pen pen, int y, String text) {
        int per = Math.max(20, (int) ((width - 2 * M - 12) / (12 * 0.58)));
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            String s = para;
            while (s.length() > per) {
                int cut = s.lastIndexOf(' ', per);
                cut = cut < per / 2 ? per : cut;
                out.add(s.substring(0, cut));
                s = s.substring(cut).stripLeading();
            }
            out.add(s);
        }
        int shown = Math.min(out.size(), maxRows);
        for (int i = 0; i < shown; i++) {
            pen.text(M + 6, y + 12 + i * ROW, out.get(i), 12, false, INK);
        }
        y += shown * ROW;
        if (out.size() > shown) {
            pen.text(M + 6, y + 12, "…", 12, false, GREY);
            y += ROW;
        }
        return y + 4;
    }

    private int metric(Pen pen, int y, PanelData.Metric m) {
        String v = m.value() == null ? "" : nz(m.value().text()) + (m.unit() == null ? "" : " " + m.unit());
        pen.text(M + 6, y + 24, fit(v, width / 2, 26), 26, true, m.value() == null ? INK : tone(m.value().tone()));
        if (m.delta() != null && m.delta().text() != null) {
            pen.text(width / 2 + 20, y + 24, fit(m.delta().text(), width / 2 - 40, 16), 16, true, tone(m.delta().tone()));
        }
        int end = y + 34;
        if (m.caption() != null && !m.caption().isBlank()) {
            pen.text(M + 6, end + 10, fit(m.caption(), width - 2 * M - 12, 11), 11, false, GREY);
            end += ROW;
        }
        return end + 4;
    }

    private int gauge(Pen pen, int y, PanelData.Gauge g) {
        pen.text(M + 6, y + 12, fit(nz(g.label()) + "  " + nz(g.text()), width - 2 * M - 12, 13), 13, true, INK);
        int w = width - 2 * M - 12;
        double f = g.max() <= 0 ? 0 : Math.max(0, Math.min(1, g.value() / g.max()));
        pen.items.add(new SnapshotModel.Box(M + 6, y + 20, w, 10, RULE, false));
        pen.items.add(new SnapshotModel.Box(M + 6, y + 20, (int) (w * f), 10, BAR, true));
        return y + 38;
    }

    private record Bar(String label, double value, String text, String tone) {}

    private int bars(Pen pen, int y, List<Bar> bars) {
        int n = Math.min(bars.size(), maxRows);
        double max = bars.stream().limit(n).mapToDouble(b -> Math.abs(b.value())).max().orElse(1);
        max = max <= 0 ? 1 : max;
        int labelW = 150;
        int span = width - 2 * M - labelW - 110;
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            int yy = y + i * ROW;
            pen.text(M + 6, yy + 12, fit(nz(b.label()), labelW - 8, 11), 11, false, INK);
            int len = (int) (span * Math.abs(b.value()) / max);
            pen.items.add(new SnapshotModel.Box(M + labelW, yy + 3, Math.max(1, len), 11, b.value() < 0 ? NEG : tone(b.tone(), BAR), true));
            pen.text(M + labelW + len + 6, yy + 12, fit(b.text() == null ? Double.toString(b.value()) : b.text(), 100, 11), 11, false, GREY);
        }
        int end = y + n * ROW;
        if (bars.size() > n) {
            pen.text(M + 6, end + 12, "… " + (bars.size() - n) + " more", 10, false, GREY);
            end += ROW;
        }
        return end + 4;
    }

    private int histogram(Pen pen, int y, PanelData.Histogram h) {
        List<PanelData.Bin> bins = h.bins();
        int w = width - 2 * M - 12;
        int max = bins.stream().mapToInt(PanelData.Bin::count).max().orElse(1);
        max = max <= 0 ? 1 : max;
        int bw = Math.max(2, w / Math.max(1, bins.size()));
        for (int i = 0; i < bins.size(); i++) {
            int bh = (int) (70.0 * bins.get(i).count() / max);
            pen.items.add(new SnapshotModel.Box(M + 6 + i * bw, y + 70 - bh, Math.max(1, bw - 1), bh, BAR, true));
        }
        if (!bins.isEmpty()) {
            pen.text(M + 6, y + 86, fit(nz(bins.get(0).label()), 140, 10), 10, false, GREY);
            String last = nz(bins.get(bins.size() - 1).label());
            pen.text(width - M - 6 - (int) (Math.min(last.length(), 24) * 5.8), y + 86, fit(last, 140, 10), 10, false, GREY);
        }
        return y + 96;
    }

    private int chart(Pen pen, int y, PanelData.Chart c) {
        List<PanelData.Series> series = c.series() == null ? List.of() : c.series();
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        int len = 0;
        for (PanelData.Series s : series) {
            len = Math.max(len, s.values().size());
            for (Double d : s.values()) {
                if (d != null) {
                    lo = Math.min(lo, d);
                    hi = Math.max(hi, d);
                }
            }
        }
        if (len < 2 || lo > hi) {
            return placeholder(pen, y, "chart");
        }
        double span = hi - lo == 0 ? 1 : hi - lo;
        int w = width - 2 * M - 12;
        int h = 80;
        pen.items.add(new SnapshotModel.Box(M + 6, y, w, h, RULE, false));
        int color = BAR;
        int legendX = M + 6;
        for (PanelData.Series s : series) {
            List<Double> vals = s.values();
            int[] xs = new int[vals.size()];
            int[] ys = new int[vals.size()];
            double prev = lo;
            for (int i = 0; i < vals.size(); i++) {
                prev = vals.get(i) == null ? prev : vals.get(i);
                xs[i] = M + 6 + (int) ((double) w * i / (len - 1));
                ys[i] = y + h - (int) (h * (prev - lo) / span);
            }
            pen.items.add(new SnapshotModel.Poly(xs, ys, color));
            pen.text(legendX, y + h + 14, fit(nz(s.label()), 140, 10), 10, true, color);
            legendX += 150;
            color = 0xD9822B;
        }
        List<String> x = c.x() == null ? List.of() : c.x();
        if (!x.isEmpty()) {
            String last = nz(x.get(x.size() - 1));
            pen.text(width - M - 6 - (int) (Math.min(last.length(), 20) * 5.8), y + h + 14, fit(last, 120, 10), 10, false, GREY);
        }
        return y + h + 24;
    }

    private int list(Pen pen, int y, List<String> rows) {
        int n = Math.min(rows.size(), maxRows);
        for (int i = 0; i < n; i++) {
            pen.text(M + 6, y + 12 + i * ROW, fit(rows.get(i), width - 2 * M - 12, 11), 11, false, INK);
        }
        int end = y + n * ROW;
        if (rows.size() > n) {
            pen.text(M + 6, end + 12, "… " + (rows.size() - n) + " more", 10, false, GREY);
            end += ROW;
        }
        return end + 4;
    }

    private int pivot(Pen pen, int y, PanelData.Pivot p) {
        List<String> cols = new ArrayList<>();
        cols.add(nz(p.by()));
        cols.addAll(p.columns() == null ? List.of() : p.columns());
        List<PanelData.Row> rows = new ArrayList<>();
        for (PanelData.PivotRow r : p.rows() == null ? List.<PanelData.PivotRow>of() : p.rows()) {
            List<Cell> cells = new ArrayList<>();
            cells.add(Cell.of(null, r.label()));
            cells.addAll(r.cells() == null ? List.of() : r.cells());
            rows.add(new PanelData.Row(cells, false, null, null));
        }
        List<Boolean> numeric = new ArrayList<>();
        numeric.add(false);
        cols.stream().skip(1).forEach(c -> numeric.add(true));
        return table(pen, y, new PanelData.Table(cols, numeric, rows, null, null));
    }

    private int placeholder(Pen pen, int y, String kind) {
        pen.items.add(new SnapshotModel.Box(M + 6, y, width - 2 * M - 12, 40, RULE, false));
        pen.text(M + 16, y + 24, "The " + nz(kind) + " panel is a chart: open the view in Drishti to see it", 12, false, GREY);
        return y + 48;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** Cuts {@code s} with an ellipsis so that its estimated width fits {@code px}. */
    static String fit(String s, int px, int size) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}]+", " ");
        int max = Math.max(3, (int) (px / (size * 0.58)));
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static int tone(String t) {
        return tone(t, INK);
    }

    private static int tone(String t, int dflt) {
        return "pos".equals(t) ? POS : "neg".equals(t) ? NEG : dflt;
    }

    /** Collects items with the text shorthand. */
    private static final class Pen {
        final List<SnapshotModel.Item> items = new ArrayList<>();

        void text(int x, int y, String s, int size, boolean bold, int color) {
            items.add(new SnapshotModel.Text(x, y, s, size, bold, false, color, 0, 1f));
        }
    }
}
