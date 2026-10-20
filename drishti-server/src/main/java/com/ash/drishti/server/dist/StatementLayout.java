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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.report.StatementSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lays a statement's bands out on paper, page by page (docs/guides/STATEMENTS.md section 3). Rows of bands go top to bottom; the bands of one row share it
 * by their grid columns. A table runs over as many pages as it needs: it never leaves its title and column titles alone at the foot of a page, starts each
 * page it runs onto with its column titles ({@code repeatHeader}, the default), keeps the rows of one value of {@code groupBy} on one page when they fit, and
 * moves to the next page whole when it is {@code keepTogether} and fits on one. {@code pageBreak} starts a band on a new page, or a new page after it. The
 * header and footer are written last, when the number of pages is known ({@code {page}} and {@code {pages}}).
 *
 * <p>Text is measured with the widths of the standard Helvetica fonts (the fonts the PDF uses), so a cell is cut with an ellipsis exactly where the paper ends.
 * One instance lays out one statement; it is not shared between threads.
 */
final class StatementLayout {

    /** A table to lay out: column titles, cells as text, which columns are numbers (drawn to the right), the column rows are grouped by (or -1). */
    record Table(List<String> headers, List<List<String>> rows, boolean[] numeric, int groupColumn) {

        Table {
            numeric = numeric.clone();
        }
    }

    /**
     * One band. {@code kind} is {@code text}, {@code card} or {@code table}; {@code x} and {@code w} are the band's place across the content width (0 to 1);
     * {@code pageBreak} is {@code before}, {@code after} or {@code none}.
     */
    record Band(String kind, String id, String title, String text, boolean heading, String value, String note, Table table, double x, double w, String pageBreak,
            boolean keepTogether, boolean repeatHeader) {}

    static final int INK = 0x1F2733;
    static final int GREY = 0x6B7686;
    static final int RULE = 0xD9DEE5;
    static final int HEAD_FILL = 0xEEF1F5;
    static final int GROUP_FILL = 0xF6F8FA;
    static final double GAP = 10;
    static final double PAD = 4;
    static final String MASK = ExportDoc.MASK;

    private final StatementSpec spec;
    private final int maxPages;
    private final double width;
    private final double height;
    private final double left;
    private final double right;
    private final double top;
    private final double bottom;
    private final double fs;
    private final double rowH;
    private final List<List<StatementPages.Op>> pages = new ArrayList<>();
    private List<StatementPages.Op> ops;
    private double y;
    private boolean breakNext;

    StatementLayout(StatementSpec spec, int maxPages) {
        this.spec = spec;
        this.maxPages = maxPages;
        double[] wh = spec.pagePoints();
        this.width = wh[0];
        this.height = wh[1];
        this.fs = spec.fontSize();
        this.rowH = Math.round(fs * 1.75 * 100) / 100.0;
        this.left = spec.marginPoints(3);
        this.right = width - spec.marginPoints(1);
        double headH = spec.header().isEmpty() ? 0 : fs + 10;
        double footH = spec.footer().isEmpty() ? 0 : fs + 10;
        this.top = height - spec.marginPoints(0) - headH;
        this.bottom = spec.marginPoints(2) + footH;
        if (top - bottom < 6 * rowH || right - left < 120) {
            throw new DrishtiException(ErrorCode.STATEMENT_INVALID, "the margins leave too little of the " + spec.paper() + " page to write on: make them smaller");
        }
    }

    /**
     * The pages: each statement page of the report (a list of rows of bands) starts a new sheet of paper.
     *
     * @param tokens the header's and footer's values ({@code title}, {@code period}, {@code reader}, ...); {@code page} and {@code pages} are added here
     */
    StatementPages lay(List<List<List<Band>>> statementPages, Map<String, String> tokens) {
        for (List<List<Band>> rows : statementPages) {
            newPage();
            for (List<Band> row : rows) {
                row(row);
            }
        }
        if (pages.isEmpty()) {
            newPage();
        }
        List<StatementPages.Page> out = new ArrayList<>();
        int n = pages.size();
        for (int i = 0; i < n; i++) {
            java.util.Map<String, String> t = new java.util.HashMap<>(tokens);
            t.put("page", String.valueOf(i + 1));
            t.put("pages", String.valueOf(n));
            List<StatementPages.Op> all = new ArrayList<>(numbered(pages.get(i), t));
            margins(all, spec.header(), height - spec.marginPoints(0) - fs, true, t);
            margins(all, spec.footer(), spec.marginPoints(2), false, t);
            out.add(new StatementPages.Page(i + 1, all));
        }
        return new StatementPages(width, height, out);
    }

    /** The roles whose text an author wrote (a text band, a title, a card's label): {@code {page}} and {@code {pages}} work there as in the header (QA2-02). */
    private static final java.util.Set<String> AUTHORED = java.util.Set.of("text", "title", "label");

    /** A page's ops with {@code {page}} and {@code {pages}} filled in the author's own text, once the number of pages is known; data cells are left as they are. */
    static List<StatementPages.Op> numbered(List<StatementPages.Op> ops, Map<String, String> tokens) {
        List<StatementPages.Op> out = new ArrayList<>(ops.size());
        for (StatementPages.Op op : ops) {
            if (op instanceof StatementPages.Text t && AUTHORED.contains(t.role()) && t.text().indexOf('{') >= 0) {
                out.add(new StatementPages.Text(t.x(), t.y(), t.size(), t.bold(), t.rgb(), StatementSpec.fill(t.text(), Map.of("page", tokens.get("page"), "pages", tokens.get("pages"))), t.role()));
            } else {
                out.add(op);
            }
        }
        return out;
    }

    // ---- pages ---------------------------------------------------------------------------------------------------

    private void newPage() {
        if (pages.size() >= maxPages) {
            throw new DrishtiException(ErrorCode.STATEMENT_INVALID, "the statement runs to more than " + maxPages
                    + " pages (drishti.distribution.statements.max-pages): narrow it with filters or parameters, or split it");
        }
        ops = new ArrayList<>();
        pages.add(ops);
        y = top;
        breakNext = false;
    }

    private boolean atTop() {
        return y >= top - 0.01;
    }

    private double room() {
        return y - bottom;
    }

    private double capacity() {
        return top - bottom;
    }

    private void margins(List<StatementPages.Op> all, Map<String, String> line, double base, boolean header, Map<String, String> tokens) {
        if (line.isEmpty()) {
            return;
        }
        double size = fs - 0.5;
        String role = header ? "header" : "footer";
        double third = (right - left) / 3;
        String l = StatementSpec.fill(line.get("left"), tokens);
        String c = StatementSpec.fill(line.get("center"), tokens);
        String r = StatementSpec.fill(line.get("right"), tokens);
        if (!l.isBlank()) {
            all.add(new StatementPages.Text(left, base, size, header, header ? INK : GREY, fit(l, third * (c.isBlank() ? 2 : 1) - PAD, size, header), role));
        }
        if (!c.isBlank()) {
            String t = fit(c, third - PAD, size, false);
            all.add(new StatementPages.Text(left + (right - left - Metrics.width(t, size, false)) / 2, base, size, false, GREY, t, role));
        }
        if (!r.isBlank()) {
            String t = fit(r, third * (c.isBlank() ? 2 : 1) - PAD, size, false);
            all.add(new StatementPages.Text(right - Metrics.width(t, size, false), base, size, false, GREY, t, role));
        }
        double ruleY = header ? base - 5 : base + size + 3;
        all.add(new StatementPages.Rule(left, ruleY, right, ruleY, 0.5, RULE));
    }

    // ---- rows of bands -------------------------------------------------------------------------------------------

    private void row(List<Band> row) {
        if (row.isEmpty()) {
            return;
        }
        boolean before = breakNext || row.stream().anyMatch(b -> "before".equals(b.pageBreak()));
        if (before && !atTop()) {
            newPage();
        }
        breakNext = false;
        if (row.size() == 1 && "table".equals(row.get(0).kind())) {
            table(row.get(0));
        } else if (row.size() == 1 && "text".equals(row.get(0).kind())) {
            text(row.get(0));
        } else {
            boxes(row);
        }
        y -= GAP;
        if (row.stream().anyMatch(b -> "after".equals(b.pageBreak()))) {
            breakNext = true;
        }
    }

    /** Text and cards side by side: the row is as tall as its tallest band and is never split. */
    private void boxes(List<Band> row) {
        double cw = right - left;
        double h = 0;
        List<List<String>> wrapped = new ArrayList<>();
        for (Band b : row) {
            List<String> lines = "text".equals(b.kind()) ? wrap(b.text(), cw * b.w() - PAD, textSize(b), b.heading()) : List.of();
            wrapped.add(lines);
            h = Math.max(h, "text".equals(b.kind()) ? lines.size() * textSize(b) * 1.35 + 2 : cardHeight(b));
        }
        if (h > room() && !atTop()) {
            newPage();
        }
        double top0 = y;
        for (int i = 0; i < row.size(); i++) {
            Band b = row.get(i);
            double x = left + cw * b.x();
            double w = cw * b.w() - (i < row.size() - 1 ? PAD * 2 : 0);
            if ("text".equals(b.kind())) {
                double yy = top0;
                for (String line : wrapped.get(i)) {
                    yy -= textSize(b) * 1.35;
                    ops.add(new StatementPages.Text(x, yy + textSize(b) * 0.3, textSize(b), b.heading(), INK, line, "text"));
                }
            } else {
                card(b, x, w, top0);
            }
        }
        y = top0 - h;
    }

    private double cardHeight(Band b) {
        return fs * 1.3 + (fs + 7) * 1.3 + (b.note() == null ? 0 : fs * 1.3) + 10;
    }

    private void card(Band b, double x, double w, double top0) {
        double h = cardHeight(b);
        ops.add(new StatementPages.Fill(x, top0 - h, w, h, GROUP_FILL));
        double yy = top0 - 4 - fs;
        ops.add(new StatementPages.Text(x + PAD + 2, yy, fs - 0.5, false, GREY, fit(b.title(), w - 2 * PAD, fs - 0.5, false), "label"));
        yy -= (fs + 7) * 1.25;
        ops.add(new StatementPages.Text(x + PAD + 2, yy, fs + 7, true, INK, fit(b.value(), w - 2 * PAD, fs + 7, true), "value"));
        if (b.note() != null) {
            yy -= fs * 1.3;
            ops.add(new StatementPages.Text(x + PAD + 2, yy, fs - 1.5, false, GREY, fit(b.note(), w - 2 * PAD, fs - 1.5, false), "note"));
        }
    }

    private double textSize(Band b) {
        return b.heading() ? fs + 5 : fs + 0.5;
    }

    /** A text band alone in its row: it flows line by line over pages when it is longer than one. */
    private void text(Band b) {
        double size = textSize(b);
        double lh = size * 1.35;
        List<String> lines = wrap(b.text(), right - left, size, b.heading());
        double h = lines.size() * lh + 2;
        if (h > room() && !atTop() && (b.keepTogether() || h <= capacity())) {
            newPage();
        }
        for (String line : lines) {
            if (lh > room()) {
                newPage();
            }
            y -= lh;
            ops.add(new StatementPages.Text(left, y + size * 0.3, size, b.heading(), INK, line, "text"));
        }
        y -= 2;
    }

    // ---- tables ----------------------------------------------------------------------------------------------------

    private double titleH() {
        return (fs + 2) * 1.6;
    }

    private void table(Band b) {
        Table t = b.table();
        double[] cols = columns(t);
        boolean hasTitle = b.title() != null && !b.title().isBlank();
        double head = (hasTitle ? titleH() : 0) + (b.note() == null ? 0 : fs * 1.4) + rowH;
        double whole = head + rowsHeight(t, 0, t.rows().size());
        if (b.keepTogether() && whole <= capacity() && whole > room() && !atTop()) {
            newPage();
        }
        int firstGroupEnd = t.groupColumn() >= 0 ? groupEnd(t, 0) : Math.min(1, t.rows().size());
        double needed = head + rowsHeight(t, 0, Math.max(firstGroupEnd, Math.min(1, t.rows().size())));
        if (needed > room() && !atTop() && (needed <= capacity() || t.groupColumn() < 0)) {
            newPage();                                                          // never a title and column titles alone at the foot of a page
        }
        if (hasTitle) {
            y -= titleH();
            ops.add(new StatementPages.Text(left, y + fs * 0.55, fs + 2, true, INK, fit(b.title(), right - left, fs + 2, true), "title"));
        }
        if (b.note() != null) {
            y -= fs * 1.4;
            ops.add(new StatementPages.Text(left, y + fs * 0.35, fs - 1, false, GREY, fit(b.note(), right - left, fs - 1, false), "note"));
        }
        headRow(t, cols);
        if (t.rows().isEmpty()) {
            y -= rowH;
            ops.add(new StatementPages.Text(left + PAD, y + rowH * 0.32, fs, false, GREY, "No rows.", "cell"));
            return;
        }
        int i = 0;
        while (i < t.rows().size()) {
            if (t.groupColumn() >= 0) {
                int end = groupEnd(t, i);
                double gh = rowH + rowsHeight(t, i, end);
                if (gh > room() && gh <= capacity() - rowH - titleH()) {
                    continued(b, t, cols);                                     // the group moves to the next page whole (keep-together group)
                }
                if (rowH * 2 > room()) {
                    continued(b, t, cols);
                }
                groupRow(t, cols, t.rows().get(i).get(t.groupColumn()));
                for (int r = i; r < end; r++) {
                    if (rowH > room()) {
                        continued(b, t, cols);
                    }
                    dataRow(t, cols, t.rows().get(r));
                }
                i = end;
            } else {
                if (rowH > room()) {
                    continued(b, t, cols);
                }
                dataRow(t, cols, t.rows().get(i));
                i++;
            }
        }
    }

    /** A new page in the middle of a table: its title marked continued and, unless the band says otherwise, its column titles again. */
    private void continued(Band b, Table t, double[] cols) {
        newPage();
        if (b.repeatHeader()) {
            y -= fs * 1.6;
            String title = (b.title() == null || b.title().isBlank() ? "" : b.title() + " ") + "(continued)";
            ops.add(new StatementPages.Text(left, y + fs * 0.4, fs, false, GREY, fit(title, right - left, fs, false), "title"));
            headRow(t, cols);
        }
    }

    private int groupEnd(Table t, int from) {
        if (t.groupColumn() < 0 || from >= t.rows().size()) {
            return Math.min(from + 1, t.rows().size());
        }
        String v = t.rows().get(from).get(t.groupColumn());
        int i = from;
        while (i < t.rows().size() && Objects.equals(t.rows().get(i).get(t.groupColumn()), v)) {
            i++;
        }
        return i;
    }

    private double rowsHeight(Table t, int from, int to) {
        return (to - from) * rowH;
    }

    private void headRow(Table t, double[] cols) {
        y -= rowH;
        ops.add(new StatementPages.Fill(left, y, right - left, rowH, HEAD_FILL));
        double x = left;
        for (int c = 0; c < cols.length; c++) {
            if (c == t.groupColumn()) {
                continue;
            }
            cell(t.headers().get(c), x, cols[c], t.numeric()[c], true, "head");
            x += cols[c];
        }
    }

    private void groupRow(Table t, double[] cols, String value) {
        y -= rowH;
        ops.add(new StatementPages.Fill(left, y, right - left, rowH, GROUP_FILL));
        String label = t.headers().get(t.groupColumn()) + ": " + (value == null ? "" : value);
        ops.add(new StatementPages.Text(left + PAD, y + rowH * 0.32, fs, true, INK, fit(label, right - left - 2 * PAD, fs, true), "group"));
    }

    private void dataRow(Table t, double[] cols, List<String> row) {
        y -= rowH;
        double x = left;
        for (int c = 0; c < cols.length; c++) {
            if (c == t.groupColumn()) {
                continue;
            }
            cell(c < row.size() ? row.get(c) : "", x, cols[c], t.numeric()[c], false, "cell");
            x += cols[c];
        }
        ops.add(new StatementPages.Rule(left, y, right, y, 0.3, RULE));
    }

    private void cell(String text, double x, double w, boolean number, boolean bold, String role) {
        String s = fit(text == null ? "" : text, w - 2 * PAD, fs, bold);
        if (s.isEmpty()) {
            return;
        }
        double tx = number ? x + w - PAD - Metrics.width(s, fs, bold) : x + PAD;
        ops.add(new StatementPages.Text(tx, y + rowH * 0.32, fs, bold, MASK.equals(s) ? GREY : INK, s, role));
    }

    /** Column widths across the content width: each as wide as its widest text (of the first 500 rows), scaled to fill the width exactly. */
    private double[] columns(Table t) {
        int n = t.headers().size();
        double[] w = new double[n];
        for (int c = 0; c < n; c++) {
            if (c == t.groupColumn()) {
                continue;
            }
            double m = Metrics.width(t.headers().get(c), fs, true);
            for (int r = 0; r < Math.min(500, t.rows().size()); r++) {
                List<String> row = t.rows().get(r);
                if (c < row.size() && row.get(c) != null) {
                    m = Math.max(m, Metrics.width(row.get(c), fs, false));
                }
            }
            w[c] = Math.min(m, 260) + 2 * PAD;
        }
        double total = 0;
        for (double x : w) {
            total += x;
        }
        double scale = total <= 0 ? 1 : (right - left) / total;
        for (int c = 0; c < n; c++) {
            w[c] *= scale;
        }
        return w;
    }

    // ---- text ----------------------------------------------------------------------------------------------------

    /** The text cut to {@code max} points, an ellipsis where it was cut. */
    static String fit(String s, double max, double size, boolean bold) {
        if (s == null) {
            return "";
        }
        if (Metrics.width(s, size, bold) <= max) {
            return s;
        }
        String e = "…";
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (Metrics.width(s.substring(0, mid) + e, size, bold) <= max) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo == 0 ? "" : s.substring(0, lo).stripTrailing() + e;
    }

    /** The text in lines no wider than {@code max} points, broken at spaces (and at line breaks it has). */
    static List<String> wrap(String text, double max, double size, boolean bold) {
        List<String> out = new ArrayList<>();
        for (String para : (text == null ? "" : text).split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (Metrics.width(next, size, bold) <= max || line.isEmpty()) {
                    line.setLength(0);
                    line.append(Metrics.width(next, size, bold) <= max ? next : fit(next, max, size, bold));
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(fit(word, max, size, bold));
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    /** The advance widths of Helvetica and Helvetica-Bold (the standard PDF fonts), in 1/1000 of the font size, for the printable ASCII characters. */
    static final class Metrics {

        private static final int[] REGULAR = {278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556,
            556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944,
            667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500,
            722, 500, 500, 500, 334, 260, 334, 584};
        private static final int[] BOLD = {278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556,
            333, 333, 584, 584, 584, 611, 975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667,
            667, 611, 333, 278, 333, 584, 556, 333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611, 556, 778,
            556, 556, 500, 389, 280, 389, 584};

        private Metrics() {}

        static double width(String s, double size, boolean bold) {
            int[] t = bold ? BOLD : REGULAR;
            double w = 0;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch >= 32 && ch <= 126) {
                    w += t[ch - 32];
                } else if (ch == '•') {
                    w += 350;
                } else if (ch == '…' || ch == '—') {
                    w += 1000;
                } else if (ch == '·') {
                    w += 278;
                } else {
                    w += 556;
                }
            }
            return w * size / 1000;
        }
    }
}
