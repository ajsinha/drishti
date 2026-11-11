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
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A visual's answer is never cut silently. When the rows reach the visual's limit:
 *
 * <ul>
 *   <li>a time series (a line, area, column or combo over a date axis) is asked again by period, week, then month, quarter and year, until the whole
 *       range fits; the answer says the period ({@code period}) and the axis column keeps its name, so the visual draws it as it would the days;</li>
 *   <li>any other visual (or a time series whose author fixed {@code format.period: day}) says how many rows there are in all ({@code total}, with
 *       {@code totalMore} when there are more than {@link #PROBE}), so the client shows "first 500 of N" with a way to see them all.</li>
 * </ul>
 *
 * A visual's {@code format.period} fixes the period: {@code auto} (the default for a time series), {@code day} (never bucketed), {@code week},
 * {@code month}, {@code quarter} or {@code year} (always that period).
 */
final class VisualFit {

    /** The rows counted at most to say how many there are in all. */
    static final int PROBE = 50_000;

    private static final Set<String> SERIES = Set.of("line", "area", "column", "combo");
    private static final List<String> PERIODS = List.of("week", "month", "quarter", "year");

    /** The answer to show: what was asked, the query it was asked as, the period it is bucketed by (or null) and the count of rows in all (or null). */
    record Fitted(DatasetService.Asked asked, QueryRequest query, Integer limit, String period, Integer total, boolean totalMore) {}

    private VisualFit() {}

    /**
     * @param ask asks the dataset a query (as the reader)
     */
    static Fitted fit(Report.Visual v, Dataset ds, QueryRequest q, Function<QueryRequest, DatasetService.Asked> ask) {
        String fixed = period(v);
        String axis = SERIES.contains(v.type()) && !q.fields().isEmpty() && !v.well("axis").isEmpty() && q.fields().get(0).equals(v.well("axis").get(0))
                && plainDate(ds, q.fields().get(0)) ? q.fields().get(0) : null;
        if (axis != null && fixed != null && PERIODS.contains(fixed)) {
            QueryRequest qb = bucketed(q, axis, fixed, q.limit());
            return new Fitted(renamed(ask.apply(qb), axis + "." + fixed + "_start", axis), qb, q.limit(), fixed, null, false);
        }
        DatasetService.Asked a = ask.apply(q);
        Integer limit = q.limit();
        if (limit == null || a.result().rows() < limit || q.detail()) {
            return new Fitted(a, q, limit, null, null, false);
        }
        if (axis != null && !"day".equals(fixed)) {
            for (String p : PERIODS) {
                QueryRequest qb = bucketed(q, axis, p, limit + 1);
                DatasetService.Asked ab = ask.apply(qb);
                if (ab.result().rows() <= limit) {
                    return new Fitted(renamed(ab, axis + "." + p + "_start", axis), qb, limit, p, null, false);
                }
            }
        }
        try {
            QueryRequest all = new QueryRequest(q.fields(), q.measures(), q.filters(), q.from(), q.to(), q.orderBy(), PROBE + 1, q.layer(), q.detail());
            int n = ask.apply(all).result().rows();
            return new Fitted(a, q, limit, null, Math.min(n, PROBE), n > PROBE);
        } catch (DrishtiException e) {
            return new Fitted(a, q, limit, null, limit, true);                       // too many to count here: "first 500 of more than 500"
        }
    }

    private static String period(Report.Visual v) {
        Object p = v.format() == null ? null : v.format().get("period");
        return p == null ? null : String.valueOf(p).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean plainDate(Dataset ds, String field) {
        try {
            Resolver r = new Resolver(ds);
            Resolver.ColRef ref = r.resolve(List.of(field.split("\\.")), null);
            if (ref.part() != null || ref.column() == null) {
                return false;
            }
            Dataset.Column c = r.column(ref.table(), ref.column());
            return c != null && ("date".equals(c.type()) || "timestamp".equals(c.type()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static QueryRequest bucketed(QueryRequest q, String axis, String period, Integer limit) {
        String b = axis + "." + period + "_start";
        List<String> fields = new ArrayList<>(q.fields());
        fields.set(0, b);
        List<QueryRequest.Sort> sorts = q.orderBy().stream().map(s -> s.by().equals(axis) ? new QueryRequest.Sort(b, s.desc()) : s).toList();
        if (sorts.isEmpty()) {
            sorts = List.of(new QueryRequest.Sort(b, false));
        }
        return new QueryRequest(fields, q.measures(), q.filters(), q.from(), q.to(), sorts, limit, q.layer(), q.detail());
    }

    /** The bucketed column under the axis's own name, so the visual (and a pushed live answer) finds it where it looks. */
    private static DatasetService.Asked renamed(DatasetService.Asked a, String from, String to) {
        QueryService.Result r = a.result();
        List<ArrowIpcWriter.Column> cols = r.columns().stream().map(c -> c.name().equals(from) ? new ArrowIpcWriter.Column(to, c.kind(), c.values()) : c).toList();
        return new DatasetService.Asked(new QueryService.Result(cols, r.rows(), r.meta()), a.query());
    }

    /** What the answer says of its fitting. */
    static void describe(Fitted f, Map<String, Object> out) {
        out.put("limit", f.limit());
        if (f.period() != null) {
            out.put("period", f.period());
        }
        if (f.total() != null) {
            out.put("total", f.total());
            out.put("totalMore", f.totalMore());
            out.put("truncated", true);
        }
    }
}
