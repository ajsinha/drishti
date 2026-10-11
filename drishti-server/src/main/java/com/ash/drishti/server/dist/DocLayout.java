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
    private final int maxHeight;

    /**
     * @param width the picture's width in pixels
     * @param slice a PDF page's height in pixels (blocks stay inside it), or 0 for one continuous picture
     * @param maxRows most rows drawn per table
     * @param maxHeight the tallest continuous picture (ignored with a slice)
     */
    public DocLayout(int width, int slice, int maxRows, int maxHeight) {
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
            if (ExportDoc.CHART.equals(s.type())) {
                bars(pen, s);
            }
            return table(pen, s);
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
            pen.text(x, pen.y + 36, fit(DocFmt.cell(c < row.size() ? row.get(c) : null), w - 8, 20), 20, true, INK);
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
        pen.y += 8;
    }

    private boolean table(Pen pen, ExportDoc.Section s) {
        int n = s.columns().size();
        int[] w = widths(s);
        int shown = Math.min(s.rows().size(), maxRows);
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
                Object v = c < row.size() ? row.get(c) : null;
                cell(pen, x, pen.y + 12, w[c], DocFmt.cell(v), v instanceof Number, false);
                x += w[c];
            }
            pen.y += ROW;
        }
        if (s.rows().size() > shown) {
            pen.text(M + 6, pen.y + 12, "… " + (s.rows().size() - shown) + " more rows not shown (the first " + shown + " are drawn; Excel holds them all)", 10, false, GREY);
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
