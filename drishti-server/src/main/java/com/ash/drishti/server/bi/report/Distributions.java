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

import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The figures of the visuals that show a distribution (RUPAKA.md section 8). The query asks the engine for the measure of every member of the axis field
 * (every trade's MTM, every counterparty's exposure), masks and row rules applied as for any query; this class bins those values for a histogram and
 * summarises them (quartiles, whiskers, outliers) for a box plot, so the screen, the export and a subscription all show the same numbers.
 */
public final class Distributions {

    /** The most bars a histogram may have. */
    public static final int MAX_BINS = 100;
    /** The bars of a histogram that does not say. */
    public static final int DEFAULT_BINS = 10;
    /** The most outliers a box plot lists for one box. */
    static final int MAX_OUTLIERS = 25;

    /** A table of the shape the JSON answer has: typed columns, rows, and the names of the columns that are masked. */
    public record Shape(List<Map<String, Object>> columns, List<List<Object>> rows, List<String> masked) {}

    private Distributions() {}

    /** Equal-width bins from the smallest to the largest value; the last bin includes the largest. Columns: {@code bin}, {@code count}, {@code from}, {@code to}. */
    public static Shape histogram(QueryService.Result r, String measure, int bins) {
        double[] values = values(r, measure);
        boolean masked = r.meta().masked().contains(measure);
        List<Map<String, Object>> cols = List.of(column("bin", "string", false), column("count", "integer", masked), column("from", "decimal", false), column("to", "decimal", false));
        if (values.length == 0 || masked) {
            return new Shape(cols, List.of(), masked ? List.of("count") : List.of());
        }
        int n = Math.max(2, Math.min(MAX_BINS, bins));
        double lo = Arrays.stream(values).min().orElse(0);
        double hi = Arrays.stream(values).max().orElse(0);
        if (hi == lo) {
            n = 1;
        }
        double width = n == 1 ? 0 : (hi - lo) / n;
        long[] counts = new long[n];
        for (double v : values) {
            int i = n == 1 ? 0 : Math.min(n - 1, (int) Math.floor((v - lo) / width));
            counts[i]++;
        }
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double from = n == 1 ? lo : lo + i * width;
            double to = n == 1 ? hi : i == n - 1 ? hi : lo + (i + 1) * width;
            rows.add(new ArrayList<>(List.of(label(from) + " to " + label(to), counts[i], from, to)));
        }
        return new Shape(cols, rows, List.of());
    }

    /**
     * One box per value of the category field (or one box for everything): the lower whisker, first quartile, median, third quartile and upper whisker, how
     * many members, and the members beyond the whiskers (1.5 interquartile ranges from the box). Quartiles interpolate linearly, as the engine's
     * {@code percentile} does.
     *
     * @param category the query's category column, or null for a single box
     * @param measure the measure column
     */
    public static Shape box(QueryService.Result r, String category, String measure) {
        List<String> names = r.columns().stream().map(ArrowIpcWriter.Column::name).toList();
        int ci = category == null ? -1 : names.indexOf(category);
        int mi = names.indexOf(measure);
        boolean masked = r.meta().masked().contains(measure);
        String catName = category == null ? "All" : category;
        List<Map<String, Object>> cols = List.of(column(catName, "string", category != null && r.meta().masked().contains(category)), column("min", "decimal", masked),
                column("q1", "decimal", masked), column("median", "decimal", masked), column("q3", "decimal", masked), column("max", "decimal", masked), column("count", "integer", masked),
                column("outliers", "string", masked));
        Map<String, List<Double>> groups = new LinkedHashMap<>();
        if (mi >= 0 && !masked) {
            Object[] vals = r.columns().get(mi).values();
            Object[] cats = ci >= 0 ? r.columns().get(ci).values() : null;
            for (int i = 0; i < r.rows(); i++) {
                if (vals[i] instanceof Number n) {
                    groups.computeIfAbsent(cats == null ? "All" : String.valueOf(cats[i]), k -> new ArrayList<>()).add(n.doubleValue());
                }
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        groups.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            double[] v = e.getValue().stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double q1 = quantile(v, 0.25);
            double med = quantile(v, 0.5);
            double q3 = quantile(v, 0.75);
            double iqr = q3 - q1;
            double lowFence = q1 - 1.5 * iqr;
            double highFence = q3 + 1.5 * iqr;
            double lowWhisker = Arrays.stream(v).filter(x -> x >= lowFence).min().orElse(v[0]);
            double highWhisker = Arrays.stream(v).filter(x -> x <= highFence).max().orElse(v[v.length - 1]);
            List<String> outliers = Arrays.stream(v).filter(x -> x < lowFence || x > highFence).limit(MAX_OUTLIERS).mapToObj(Distributions::label).toList();
            rows.add(new ArrayList<>(List.of(e.getKey(), lowWhisker, q1, med, q3, highWhisker, (long) v.length, String.join(", ", outliers))));
        });
        return new Shape(cols, rows, masked ? List.of("min", "q1", "median", "q3", "max", "count", "outliers") : List.of());
    }

    /** The {@code p}-quantile of sorted values by linear interpolation between the two nearest (the method of {@code quantile_cont}). */
    static double quantile(double[] sorted, double p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double pos = (sorted.length - 1) * p;
        int i = (int) Math.floor(pos);
        double frac = pos - i;
        return i + 1 < sorted.length ? sorted[i] + frac * (sorted[i + 1] - sorted[i]) : sorted[i];
    }

    private static double[] values(QueryService.Result r, String measure) {
        for (ArrowIpcWriter.Column c : r.columns()) {
            if (c.name().equals(measure)) {
                return Arrays.stream(c.values()).filter(x -> x instanceof Number).mapToDouble(x -> ((Number) x).doubleValue()).toArray();
            }
        }
        return new double[0];
    }

    private static Map<String, Object> column(String name, String type, boolean masked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("masked", masked);
        return m;
    }

    /** A number as a label: whole numbers as they are, others to at most four significant digits. */
    static String label(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.format(Locale.ROOT, "%d", (long) v);
        }
        String s = String.format(Locale.ROOT, "%.4g", v);
        return s.contains("e") ? s : s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }
}
