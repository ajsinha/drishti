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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.QueryRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * True totals for a matrix and a table (RUPAKA.md section 8). A total of a measure that adds up (a sum, a count) is the sum of the figures shown; a total
 * of one that does not (a ratio, an average, a distinct count, a percentile, a minimum, a maximum, a time-intelligence measure) is a different number, which
 * only the engine can give: the measure computed over the rows of the whole group, not over the figures shown. This class plans the queries for those levels;
 * {@link BiReportService} runs them as the reader and the answer carries them as {@code totals}.
 */
public final class Totals {

    private static final Pattern ADDS_UP = Pattern.compile("^\\s*(sum|count)\\s*\\(.*", Pattern.DOTALL);
    private static final Pattern DOES_NOT = Pattern.compile(".*(avg|ratio|distinct|percentile|min\\(|max\\(|/|\\[|ytd|mtd|qtd|prior_period|same_period|running).*", Pattern.DOTALL);

    /** One level of totals to ask the engine for: the fields it groups by and the query. */
    public record Level(List<String> by, QueryRequest query) {}

    private Totals() {}

    /** True for a measure whose total is the sum of its parts: a sum or a count and nothing else. */
    public static boolean adds(Dataset ds, String measure) {
        Dataset.Measure m = ds.measures().get(measure);
        String e = m == null || m.expr() == null ? "" : m.expr();
        return ADDS_UP.matcher(e).matches() && !DOES_NOT.matcher(e).matches();
    }

    /**
     * The levels a matrix with subtotals needs for its measures that do not add up: the grand total, the total of each value of the first row field, and
     * (with a column field) the total of each row across the columns, each also per column where there is one.
     */
    public static List<Level> matrix(Dataset ds, Report.Visual v, QueryRequest base, int maxRows) {
        List<String> measures = v.well("values").stream().filter(m -> !adds(ds, m)).toList();
        List<Level> out = new ArrayList<>();
        if (measures.isEmpty() || !Boolean.TRUE.equals(v.format().get("subtotals"))) {
            return out;
        }
        List<String> rows = v.well("rows");
        List<String> cols = v.well("columns");
        List<List<String>> by = new ArrayList<>();
        by.add(List.of());
        if (rows.size() > 1) {
            by.add(rows.subList(0, 1));
        }
        List<List<String>> withCol = new ArrayList<>();
        for (List<String> b : by) {
            if (!cols.isEmpty()) {
                List<String> c = new ArrayList<>(b);
                c.add(cols.get(0));
                withCol.add(c);
            }
        }
        by.addAll(withCol);
        if (!cols.isEmpty()) {
            by.add(rows);                                           // each row across the columns
        }
        for (List<String> b : by) {
            out.add(new Level(b, new QueryRequest(b, measures, base.filters(), base.from(), base.to(), List.of(), maxRows, base.layer())));
        }
        return out;
    }

    /** The one level of a table with {@code format.totals}: every measure of its columns over all the rows (not just the rows shown). */
    public static List<Level> table(Dataset ds, Report.Visual v, QueryRequest base, int maxRows) {
        List<String> measures = v.well("columns").stream().filter(ds.measures()::containsKey).toList();
        if (measures.isEmpty() || !Boolean.TRUE.equals(v.format().get("totals"))) {
            return List.of();
        }
        return List.of(new Level(List.of(), new QueryRequest(List.of(), measures, base.filters(), base.from(), base.to(), List.of(), maxRows, base.layer())));
    }
}
