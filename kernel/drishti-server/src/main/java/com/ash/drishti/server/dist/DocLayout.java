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
package com.ash.drishti.server.dist;

import com.ash.drishti.server.collab.snapshot.SnapshotModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays an {@link ExportDoc} out as a {@link SnapshotModel} (a tall picture of text, boxes and lines) that the PNG painter rasterises and the
 * {@link PdfWriter} cuts into pages. With a {@code slice} (the PDF page's height in pixels) no table row or block straddles a page edge: a
 * block that does not fit starts the next page, and a table that continues repeats its header row. Without one (a PNG) the picture is as
 * tall as it needs, to {@code maxHeight}, after which a line says how much was left out.
 */
public final class DocLayout {

    private static final int M = 24;
    private static final int ROW = 18;
    private static final int INK = 0x1B2330;
    private static final int GREY = 0x6B7686;
    private static final int RULE = 0xD5DAE1;
    private static final int BAND = 0xEEF1F5;
    private static final int BAR = 0x4F7CAC;
    private static final int NEG = 0xC5221F;

    private final int width;
    private final int slice;
    private final int maxRows;
    private final int chartRows;
    private final int maxHeight;

    /**
     * @param width the picture's width in pixels
     * @param slice a PDF page's height in pixels (blocks stay inside it), or 0 for one continuous picture
     * @param maxRows most rows drawn per table
     * @param maxHeight the tallest continuous picture (ignored with a slice)
     */
    public DocLayout(int width, int slice, int maxRows, int maxHeight) {
        this(width, slice, maxRows, maxRows, maxHeight);
    }

    /** @param chartRows most rows of the table that follows a chart (the chart shows the shape; the table is a reference, and Excel holds the rest) */
    public DocLayout(int width, int slice, int maxRows, int chartRows, int maxHeight) {
        this.chartRows = Math.max(1, chartRows);
        this.width = width;
        this.slice = slice;
        this.maxRows = Math.max(1, maxRows);
        this.maxHeight = maxHeight;
    }

    public SnapshotModel build(ExportDoc doc) {
        Pen pen = new Pen();
        pen.text(M, 28, fit(doc.title(), width - 2 * M, 20), 20, true, INK);
        pen.y = 40;
        if (doc.subtitle() != null && !doc.subtitle().isBlank()) {
            pen.text(M, pen.y + 12, fit(doc.subtitle(), width - 2 * M, 12), 12, false, GREY);
            pen.y += 18;
        }
        for (String n : doc.notes()) {
            pen.text(M, pen.y + 12, fit(n, width - 2 * M, 11), 11, false, GREY);
            pen.y += 16;
        }
        pen.y += 6;
        pen.items.add(new SnapshotModel.Line(M, pen.y, width - M, pen.y, RULE));
        pen.y += 12;
        int left = 0;
        for (int i = 0; i < doc.sections().size(); i++) {
            if (!section(pen, doc.sections().get(i))) {
                left = doc.sections().size() - i;
                break;
            }
        }
        if (left > 0) {
            pen.text(M, pen.y + 12, "… " + left + (left == 1 ? " more section" : " more sections") + " not shown: the picture is at its height limit", 11, false, GREY);
            pen.y += ROW;
        }
        int height = slice > 0 ? Math.max(slice, (int) Math.ceil((pen.y + M) / (double) slice) * slice) : Math.min(maxHeight, Math.max(120, pen.y + M));
        return new SnapshotModel(width, height, pen.items);
    }

    /** @return false when the picture is full and nothing more fits */
    private boolean section(Pen pen, ExportDoc.Section s) {
        int need = 30 + (s.note() == null ? 0 : 16) + 2 * ROW + (ExportDoc.CARD.equals(s.type()) ? 40 : 0);
        if (!room(pen, need)) {
            return false;
        }
        pen.items.add(new SnapshotModel.Box(M, pen.y, width - 2 * M, 22, BAND, true));
        pen.text(M + 6, pen.y + 16, fit(s.title(), width - 2 * M - 12, 13), 13, true, INK);
        pen.y += 28;
        if (s.note() != null && !s.note().isBlank()) {
            pen.text(M + 6, pen.y + 11, fit(s.note(), width - 2 * M - 12, 10), 10, false, GREY);
            pen.y += 16;
        }
        if (s.rows().isEmpty() || s.columns().isEmpty()) {
            pen.text(M + 6, pen.y + 12, "No data", 11, false, GREY);
            pen.y += ROW + 8;
            return true;
        }
        if (ExportDoc.CARD.equals(s.type())) {
            cards(pen, s);
        } else {
            boolean chart = ExportDoc.CHART.equals(s.type());
            if (chart) {
                if ("line".equals(s.kind()) || "area".equals(s.kind())) {
                    lines(pen, s);
                } else {
                    bars(pen, s);
                }
            }
            return table(pen, s, chart ? Math.min(maxRows, chartRows) : maxRows);
        }
        return true;
    }

    private void cards(Pen pen, ExportDoc.Section s) {
        List<Object> row = s.rows().get(0);
        int n = Math.min(s.columns().size(), 4);
        int w = (width - 2 * M - 12) / n;
        for (int c = 0; c < n; c++) {
            int x = M + 6 + c * w;
            pen.text(x, pen.y + 12, fit(s.columns().get(c), w - 8, 10), 10, false, GREY);
            pen.text(x, pen.y + 36, fit(DocHtml.cardValue(s, row, c), w - 8, 20), 20, true, INK);
        }
        pen.y += 52;
    }

    private void bars(Pen pen, ExportDoc.Section s) {
        int value = -1;
        for (int c = 1; c < s.columns().size() && value < 0; c++) {
            if (s.isNumeric(c)) {
                value = c;
            }
        }
        if (value < 0) {
            return;
        }
        int n = Math.min(s.rows().size(), 12);
        double max = 0;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, Math.abs(num(s.rows().get(i), value)));
        }
        if (max == 0 || !room(pen, n * 16 + 8)) {
            return;
        }
        int labelW = (width - 2 * M) / 4;
        int barMax = width - 2 * M - labelW - 110;
        for (int i = 0; i < n; i++) {
            List<Object> r = s.rows().get(i);
            double v = num(r, value);
            pen.text(M + 6, pen.y + 11, fit(DocFmt.cell(r.get(0)), labelW - 10, 10), 10, false, INK);
            int w = (int) Math.max(1, Math.abs(v) / max * barMax);
            pen.items.add(new SnapshotModel.Box(M + labelW, pen.y + 2, w, 11, v < 0 ? NEG : BAR, true));
            pen.text(M + labelW + w + 6, pen.y + 11, DocFmt.cell(r.get(value)), 10, false, GREY);
            pen.y += 16;
        }
        if (s.rows().size() > n) {
            pen.text(M + 6, pen.y + 10, "the first " + n + " of " + s.rows().size() + " rows are drawn as bars", 10, false, GREY);
            pen.y += 14;
        }
        pen.y += 8;
    }

    private static final int[] SERIES = {0x4F7CAC, 0xE07B39, 0x3C9D6B, 0x8E6BB5};
    private static final int LINE_POINTS = 200;

    /** A line or area chart: every numeric column (up to four) as a line over the whole series, sampled to {@value #LINE_POINTS} points, with the range and the ends of the axis. */
    private void lines(Pen pen, ExportDoc.Section s) {
        List<Integer> cols = new ArrayList<>();
        for (int c = 1; c < s.columns().size() && cols.size() < SERIES.length; c++) {
            if (s.isNumeric(c)) {
                cols.add(c);
            }
        }
        int total = s.rows().size();
        int h = 110;
        if (cols.isEmpty() || total < 2 || !room(pen, h + 44)) {
            return;
        }
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (List<Object> r : s.rows()) {
            for (int c : cols) {
                if (c < r.size() && r.get(c) instanceof Number n) {
                    lo = Math.min(lo, n.doubleValue());
                    hi = Math.max(hi, n.doubleValue());
                }
            }
        }
        if (lo == Double.MAX_VALUE) {
            return;
        }
        if (hi == lo) {
            hi = lo + 1;
        }
        int left = M + 70;
        int right = width - M - 6;
        int top = pen.y + 4;
        int bottom = top + h;
        pen.items.add(new SnapshotModel.Line(left, bottom, right, bottom, RULE));
        pen.items.add(new SnapshotModel.Line(left, top, left, bottom, RULE));
        pen.text(M + 6, top + 9, fit(DocFmt.cell(hi), 62, 10), 10, false, GREY);
        pen.text(M + 6, bottom, fit(DocFmt.cell(lo), 62, 10), 10, false, GREY);
        int points = Math.min(total, LINE_POINTS);
        for (int k = 0; k < cols.size(); k++) {
            int px = -1;
            int py = -1;
            for (int i = 0; i < points; i++) {
                int idx = points == total ? i : (int) Math.round(i * (total - 1) / (double) (points - 1));
                List<Object> r = s.rows().get(idx);
                int col = cols.get(k);
                if (col >= r.size() || !(r.get(col) instanceof Number n)) {
                    px = -1;
                    continue;
                }
                int x = left + (int) Math.round((right - left) * i / (double) (points - 1));
                int y = bottom - (int) Math.round((n.doubleValue() - lo) / (hi - lo) * (h - 6)) - 2;
                if (px >= 0) {
                    pen.items.add(new SnapshotModel.Line(px, py, x, y, SERIES[k]));
                }
                px = x;
                py = y;
            }
        }
        pen.text(left, bottom + 13, fit(DocFmt.cell(s.rows().get(0).get(0)), (right - left) / 2 - 10, 10), 10, false, GREY);
        String last = fit(DocFmt.cell(s.rows().get(total - 1).get(0)), (right - left) / 2 - 10, 10);
        pen.text(right - (int) (last.length() * 10 * 0.56), bottom + 13, last, 10, false, GREY);
        int lx = left;
        for (int k = 0; k < cols.size(); k++) {
            String name = fit(s.columns().get(cols.get(k)), 120, 10);
            pen.items.add(new SnapshotModel.Box(lx, bottom + 20, 10, 4, SERIES[k], true));
            pen.text(lx + 14, bottom + 25, name, 10, false, INK);
            lx += 24 + (int) (name.length() * 10 * 0.56);
        }
        pen.y = bottom + 34;
    }

    private boolean table(Pen pen, ExportDoc.Section s, int cap) {
        int n = s.columns().size();
        int[] w = widths(s);
        int shown = Math.min(s.rows().size(), cap);
        header(pen, s, w);
        for (int r = 0; r < shown; r++) {
            if (slice > 0 && pen.y % slice + ROW > slice - 8) {
                pen.y = (pen.y / slice + 1) * slice + 8;
                header(pen, s, w);
            } else if (slice == 0 && pen.y + ROW > maxHeight - 40) {
                pen.text(M + 6, pen.y + 12, "… " + (s.rows().size() - r) + " more rows not shown: the picture is at its height limit", 10, false, GREY);
                pen.y += ROW;
                return false;
            }
            List<Object> row = s.rows().get(r);
            int x = M + 6;
            for (int c = 0; c < n; c++) {
                Object v = s.shown(row, c);
                cell(pen, x, pen.y + 12, w[c], DocFmt.cell(v), v instanceof Number, false);
                x += w[c];
            }
            pen.y += ROW;
        }
        if (s.rows().size() > shown) {
            pen.text(M + 6, pen.y + 12, "… " + (s.rows().size() - shown) + " more rows in the Excel/CSV export (the first " + shown + " are drawn here)", 10, false, GREY);
            pen.y += ROW;
        }
        pen.y += 12;
        return true;
    }

    private void header(Pen pen, ExportDoc.Section s, int[] w) {
        int x = M + 6;
        for (int c = 0; c < s.columns().size(); c++) {
            cell(pen, x, pen.y + 12, w[c], s.columns().get(c), s.isNumeric(c), true);
            x += w[c];
        }
        pen.items.add(new SnapshotModel.Line(M, pen.y + 17, width - M, pen.y + 17, RULE));
        pen.y += ROW + 2;
    }

    private void cell(Pen pen, int x, int baseline, int colW, String text, boolean right, boolean bold) {
        String t = fit(text, colW - 8, 11);
        int tx = right ? x + colW - 8 - (int) (t.length() * 11 * 0.55) : x;
        pen.text(Math.max(x, tx), baseline, t, 11, bold, INK);
    }

    /** Column widths in proportion to what the columns hold (header and the first rows), none under 48 pixels. */
    private int[] widths(ExportDoc.Section s) {
        int n = s.columns().size();
        double[] weight = new double[n];
        double total = 0;
        for (int c = 0; c < n; c++) {
            int len = s.columns().get(c).length();
            for (int r = 0; r < Math.min(60, s.rows().size()); r++) {
                List<Object> row = s.rows().get(r);
                len = Math.max(len, c < row.size() ? DocFmt.cell(row.get(c)).length() : 0);
            }
            weight[c] = Math.max(6, Math.min(30, len)) + 2;
            total += weight[c];
        }
        int avail = width - 2 * M - 12;
        int[] w = new int[n];
        for (int c = 0; c < n; c++) {
            w[c] = Math.max(48, (int) (avail * weight[c] / total));
        }
        return w;
    }

    private boolean room(Pen pen, int need) {
        if (slice > 0) {
            if (pen.y % slice + need > slice - 8) {
                pen.y = (pen.y / slice + 1) * slice + 8;
            }
            return true;
        }
        return pen.y + need <= maxHeight - 40;
    }

    private static double num(List<Object> row, int c) {
        return c < row.size() && row.get(c) instanceof Number n ? n.doubleValue() : 0;
    }

    static String fit(String s, int px, int size) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}]+", " ");
        int max = Math.max(3, (int) (px / (size * 0.56)));
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static final class Pen {
        final List<SnapshotModel.Item> items = new ArrayList<>();
        int y;

        void text(int x, int baseline, String s, int size, boolean bold, int color) {
            items.add(new SnapshotModel.Text(x, baseline, s, size, bold, false, color, 0, 1f));
        }
    }
}
