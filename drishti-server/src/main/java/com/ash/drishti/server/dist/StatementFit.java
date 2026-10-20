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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a statement's figures fit the paper whole (docs/guides/STATEMENTS.md section 4, QA3B-01): a number is never cut with an ellipsis, because a cut figure
 * reads as another number ({@code -428,350,58…} reads a thousand times smaller than it is). A table's number columns get the width of their widest figure; text
 * columns share what is left and may still be cut. When the columns do not fit, the table's font shrinks step by step to a floor (three quarters of the
 * statement's font, at least 6 pt); when they still do not fit, the number columns go on in further parts of the table below it, each repeating the text
 * columns (the row labels). A card's value shrinks the same way, then reads in compact units ({@code 3.62tn}) with the exact figure in a line under it.
 *
 * <p>Stateless; all widths in PDF points, measured with the Helvetica widths of {@link StatementLayout.Metrics}.
 */
final class StatementFit {

    /** The width a text column keeps at least, in points at the statement's font (its text may be cut with an ellipsis). */
    static final double MIN_TEXT = 48;
    /** The widest a text column grows to before the space is shared out. */
    static final double MAX_TEXT = 260;

    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,3}(,\\d{3})*(\\.\\d+)?|-?\\d+(\\.\\d+)?");

    private StatementFit() {}

    /** One part of a table across the paper: the table's columns it draws (indices, the group column among them) and their widths (0 for the group column). */
    record Part(int[] columns, double[] widths) {}

    /** How a table is drawn: at {@code size} points, in one part or more (its number columns going on below). */
    record Plan(double size, List<Part> parts) {}

    /** The smallest font a table or a card shrinks to. */
    static double floor(double fs) {
        return Math.max(6, Math.round(fs * 0.75 * 2) / 2.0);
    }

    /** The plan for {@code t} across {@code width} points, the statement's font being {@code fs}. */
    static Plan plan(StatementLayout.Table t, double width, double fs) {
        Measured m = new Measured(t);
        int n = t.headers().size();
        List<Integer> all = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            if (c != t.groupColumn()) {
                all.add(c);
            }
        }
        double floor = floor(fs);
        // 1. one part, the font shrinking step by step to the floor, the text columns keeping room to be read
        for (double size = fs; size >= floor - 1e-9; size -= 0.5) {
            double[] w = widths(t, m, all, width, size, MIN_TEXT * size / fs);
            if (w != null) {
                return new Plan(size, List.of(new Part(withGroup(t, all), full(t, all, w))));
            }
        }
        // 2. the number columns going on in parts below, at the statement's font, then at the floor
        for (double size : new double[] {fs, floor}) {
            List<Part> parts = split(t, m, all, width, size, MIN_TEXT * size / fs);
            if (parts != null) {
                return new Plan(size, parts);
            }
        }
        // 3. at the floor, the text columns down to a few letters (they are cut with an ellipsis; the figures stay whole)
        double tiny = 2 * StatementLayout.PAD + 12 * floor / fs;
        double[] squeezedText = widths(t, m, all, width, floor, tiny);
        if (squeezedText != null) {
            return new Plan(floor, List.of(new Part(withGroup(t, all), full(t, all, squeezedText))));
        }
        List<Part> tinyParts = split(t, m, all, width, floor, tiny);
        if (tinyParts != null) {
            return new Plan(floor, tinyParts);
        }
        // 4. a figure wider than the paper itself: one number column a part, as wide as the paper lets it be (the layout then notes the cut)
        List<Integer> keys = all.stream().filter(c -> !t.numeric()[c]).toList();
        List<Part> parts = new ArrayList<>();
        for (int c : all) {
            if (t.numeric()[c]) {
                List<Integer> cols = new ArrayList<>(keys);
                cols.add(c);
                cols.sort(Integer::compare);
                parts.add(new Part(withGroup(t, cols), full(t, cols, squeezed(t, m, cols, width, floor, tiny))));
            }
        }
        return new Plan(floor, parts.isEmpty() ? List.of(new Part(withGroup(t, all), full(t, all, squeezed(t, m, all, width, floor, tiny)))) : parts);
    }

    /** The table cut down to some of its columns (and its group column), for a part of it. */
    static StatementLayout.Table sub(StatementLayout.Table t, int[] columns) {
        List<String> headers = new ArrayList<>(columns.length);
        boolean[] numeric = new boolean[columns.length];
        int group = -1;
        for (int i = 0; i < columns.length; i++) {
            headers.add(t.headers().get(columns[i]));
            numeric[i] = t.numeric()[columns[i]];
            if (columns[i] == t.groupColumn()) {
                group = i;
            }
        }
        List<List<String>> rows = new ArrayList<>(t.rows().size());
        for (List<String> r : t.rows()) {
            List<String> row = new ArrayList<>(columns.length);
            for (int c : columns) {
                row.add(c < r.size() ? r.get(c) : "");
            }
            rows.add(row);
        }
        return new StatementLayout.Table(headers, rows, numeric, group);
    }

    /** True for text that is a figure as the statement writes it ({@code -1,234.50}, {@code 120}). */
    static boolean isNumber(String s) {
        return s != null && NUMBER.matcher(s).matches();
    }

    /** A figure in compact units: {@code 3.62tn}, {@code 428.4m}, {@code 12.5k}; the text as it is when it is not a figure or is under a thousand. */
    static String compact(String s) {
        if (!isNumber(s)) {
            return s;
        }
        double v = Double.parseDouble(s.replace(",", ""));
        double a = Math.abs(v);
        String[] units = {"tn", "bn", "m", "k"};
        double[] scale = {1e12, 1e9, 1e6, 1e3};
        for (int i = 0; i < units.length; i++) {
            if (a >= scale[i]) {
                double x = v / scale[i];
                double ax = Math.abs(x);
                return String.format(Locale.ROOT, ax < 10 ? "%.2f" : ax < 1000 ? "%.1f" : "%,.0f", x) + units[i];
            }
        }
        return s;
    }

    // ---- widths --------------------------------------------------------------------------------------------------

    /** Widths of each column's widest text at 1000 points (widths scale with the size), measured once. */
    private static final class Measured {
        final double[] head;
        final double[] cells;

        Measured(StatementLayout.Table t) {
            int n = t.headers().size();
            head = new double[n];
            cells = new double[n];
            for (int c = 0; c < n; c++) {
                head[c] = StatementLayout.Metrics.width(t.headers().get(c), 1000, true);
                int limit = t.numeric()[c] ? t.rows().size() : Math.min(500, t.rows().size());     // every figure is measured; text from the first 500 rows
                double w = 0;
                for (int r = 0; r < limit; r++) {
                    List<String> row = t.rows().get(r);
                    if (c < row.size() && row.get(c) != null) {
                        w = Math.max(w, StatementLayout.Metrics.width(row.get(c), 1000, false));
                    }
                }
                cells[c] = w;
            }
        }
    }

    /** The width a column must have: a number column its widest figure, a text column a little (its text may be cut). */
    private static double need(StatementLayout.Table t, Measured m, int c, double size, double minText) {
        double pad = 2 * StatementLayout.PAD;
        if (t.numeric()[c]) {
            return m.cells[c] * size / 1000 + pad;
        }
        return Math.min(natural(t, m, c, size), minText + pad);
    }

    /** The width a column would like: its widest text or title (a text column at most {@link #MAX_TEXT}). */
    private static double natural(StatementLayout.Table t, Measured m, int c, double size) {
        double w = Math.max(m.head[c], m.cells[c]) * size / 1000;
        return (t.numeric()[c] ? w : Math.min(w, MAX_TEXT)) + 2 * StatementLayout.PAD;
    }

    /** The widths of {@code cols} filling {@code width} exactly, every figure whole; null when the figures alone need more. */
    private static double[] widths(StatementLayout.Table t, Measured m, List<Integer> cols, double width, double size, double minText) {
        double[] need = new double[cols.size()];
        double[] nat = new double[cols.size()];
        double sumNeed = 0;
        double sumNat = 0;
        for (int i = 0; i < cols.size(); i++) {
            need[i] = need(t, m, cols.get(i), size, minText);
            nat[i] = Math.max(need[i], natural(t, m, cols.get(i), size));
            sumNeed += need[i];
            sumNat += nat[i];
        }
        if (sumNeed > width + 1e-6) {
            return null;
        }
        double[] w = new double[cols.size()];
        if (sumNat <= width) {
            double scale = sumNat <= 0 ? 1 : width / sumNat;
            for (int i = 0; i < w.length; i++) {
                w[i] = nat[i] * scale;
            }
            return w;
        }
        double share = (width - sumNeed) / (sumNat - sumNeed);
        for (int i = 0; i < w.length; i++) {
            w[i] = need[i] + (nat[i] - need[i]) * share;
        }
        return w;
    }

    /** The widths of {@code cols} when even their figures do not fit: as {@link #widths} would like them, scaled down to {@code width}. */
    private static double[] squeezed(StatementLayout.Table t, Measured m, List<Integer> cols, double width, double size, double minText) {
        double[] w = new double[cols.size()];
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = need(t, m, cols.get(i), size, minText);
            sum += w[i];
        }
        for (int i = 0; i < w.length; i++) {
            w[i] = sum <= 0 ? width / w.length : w[i] * width / sum;
        }
        return w;
    }

    /**
     * The table in parts across: each part the text columns (the row labels) and as many number columns, in order, as fit with every figure whole; null when
     * one number column does not fit beside the text columns at this size.
     */
    private static List<Part> split(StatementLayout.Table t, Measured m, List<Integer> all, double width, double size, double minText) {
        List<Integer> keys = all.stream().filter(c -> !t.numeric()[c]).toList();
        List<Integer> numbers = all.stream().filter(c -> t.numeric()[c]).toList();
        if (numbers.size() < 2) {
            return null;
        }
        double keysNeed = 0;
        double keysNat = 0;
        for (int c : keys) {
            keysNeed += need(t, m, c, size, minText);
            keysNat += natural(t, m, c, size);
        }
        double kw = Math.min(keysNat, Math.max(keysNeed, width * 0.4));    // the row labels keep room to be read where they can
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        double used = kw;
        for (int c : numbers) {
            double n = need(t, m, c, size, minText);
            if (keysNeed + n > width + 1e-6) {
                return null;
            }
            if (!current.isEmpty() && used + n > width + 1e-6) {
                groups.add(current);
                current = new ArrayList<>();
                used = kw;
            }
            current.add(c);
            used += n;
        }
        groups.add(current);
        if (groups.size() < 2) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        for (List<Integer> g : groups) {
            List<Integer> cols = new ArrayList<>(keys);
            cols.addAll(g);
            cols.sort(Integer::compare);
            double[] w = widths(t, m, cols, width, size, minText);
            if (w == null) {
                return null;
            }
            parts.add(new Part(withGroup(t, cols), full(t, cols, w)));
        }
        return parts;
    }

    /** The columns with the table's group column added (in order), as a part draws them. */
    private static int[] withGroup(StatementLayout.Table t, List<Integer> cols) {
        List<Integer> out = new ArrayList<>(cols);
        if (t.groupColumn() >= 0 && !out.contains(t.groupColumn())) {
            out.add(t.groupColumn());
        }
        out.sort(Integer::compare);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The widths aligned to {@link #withGroup}'s columns: 0 for the group column (drawn as a row of its own). */
    private static double[] full(StatementLayout.Table t, List<Integer> cols, double[] w) {
        int[] with = withGroup(t, cols);
        double[] out = new double[with.length];
        for (int i = 0; i < with.length; i++) {
            int at = cols.indexOf(with[i]);
            out[i] = at < 0 ? 0 : w[at];
        }
        return out;
    }
}
