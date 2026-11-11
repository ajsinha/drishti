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
package com.ash.drishti.bicompute.rachana;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.kernel.api.pivot.FigureSource;
import com.ash.drishti.rachana.el.FunctionProvider;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.el.check.Signatures;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The pivot engine as Rachana's provider of every aggregate (docs/architecture/ARCHITECTURE.md parts 1 to 3), found by {@link java.util.ServiceLoader}
 * ({@code META-INF/services/com.ash.drishti.rachana.el.FunctionProvider}): the aggregates over a list ({@link ListAggregates}, the same
 * {@code AggFunction}s a pivot cell runs), the grouping behind {@code group(...)} ({@link ListGrouping}), and {@code measure(...)} and
 * {@code cell(...)}, answered by the server's {@link FigureSource} (registered with {@link #figures(FigureSource)}; refused with the
 * reason where none is, a command-line tool or a test without the server).
 */
public final class EngineFunctions implements FunctionProvider {

    /** Who provides, as the catalogue and a refusal name it. */
    public static final String SOURCE = "the pivot engine (drishti-bicompute)";

    private static final AtomicReference<FigureSource> FIGURES = new AtomicReference<>();

    /** The server's figures for {@code measure(...)} and {@code cell(...)}, registered once at start (null: none). */
    public static void figures(FigureSource source) {
        FIGURES.set(source);
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public Grouping grouping() {
        return new ListGrouping();
    }

    @Override
    public List<Provided> functions() {
        List<Provided> out = new ArrayList<>();
        agg(out, "sum", 1, 2, "list, [field]", "mergeable", "the total (compensated); 0 when there is no number",
                "sum($.LEGS[*].NOTIONAL)");
        agg(out, "count", 0, 2, "[list, [field]]", "mergeable", "the elements (or the field's values) that are not empty; count() with no argument "
                + "is the rows of a pivot cell (a pivot formula) or of a measure's table",
                "count($.EVENTS[?@.EVENT_TYPE == 'BOOKED'])");
        agg(out, "avg", 1, 2, "list, [field]", "mergeable", "the mean, Σx / n from the list's own sum and count", "avg($.LEGS, 'NOTIONAL')");
        agg(out, "min", 1, 2, "list, [field]", "mergeable", "the smallest number (several arguments: least)", "min($.LEGS[*].NOTIONAL)");
        agg(out, "max", 1, 2, "list, [field]", "mergeable", "the largest number (several arguments: greatest)", "max($.TRADES[*].NOTIONAL)");
        agg(out, "median", 1, 2, "list, [field]", "holistic", "the exact middle value", "median($.TRADES, 'NOTIONAL')");
        agg(out, "percentile", 2, 3, "list, [field], p", "holistic", "the exact p-quantile (linear interpolation), p in 0..1",
                "percentile($.TRADES[*].NOTIONAL, 0.95)");
        agg(out, "distinct", 1, 2, "list, [field]", "holistic", "how many different values (exact)", "distinct($.LEGS[*].CURRENCY)");
        agg(out, "wavg", 2, 3, "list, xField, wField | xs, ws", "mergeable", "Σwx / Σw, the weighted mean",
                "wavg($.TRADES, 'PRICE', 'QUANTITY')");
        agg(out, "variance", 1, 2, "list, [field]", "mergeable", "the sample variance (Welford)", "variance($.TRADES, 'PNL')");
        agg(out, "stddev", 1, 2, "list, [field]", "mergeable", "the sample standard deviation", "stddev($.TRADES[*].PNL)");
        agg(out, "variance_pop", 1, 2, "list, [field]", "mergeable", "the population variance", "variance_pop($.TRADES[*].PNL)");
        agg(out, "stddev_pop", 1, 2, "list, [field]", "mergeable", "the population standard deviation", "stddev_pop($.TRADES[*].PNL)");
        agg(out, "sum_once", 2, 3, "list, field, parentField | xs, parents", "holistic", "Σx counting each parent once",
                "sum_once($.LEGS, 'QUANTITY', 'TRADE_ID')");
        agg(out, "count_once", 2, 3, "list, field, parentField | xs, parents", "holistic", "the parents with a value, each once",
                "count_once($.LEGS, 'QUANTITY', 'TRADE_ID')");
        agg(out, "avg_once", 2, 3, "list, field, parentField | xs, parents", "holistic", "the mean over the parents, each once",
                "avg_once($.LEGS, 'QUANTITY', 'TRADE_ID')");
        agg(out, "nmiss", 1, 2, "list, [field]", "mergeable", "how many values are missing (null or absent); 0 for an empty list",
                "nmiss($.LEGS, 'DISCOUNT_INDEX')");
        agg(out, "range", 1, 2, "list, [field]", "mergeable", "max − min; empty when there is no number", "range($.TRADES[*].PRICE)");
        agg(out, "stderr", 1, 2, "list, [field]", "mergeable", "the standard error of the mean, s / √n; empty under two numbers",
                "stderr($.TRADES[*].PNL)");
        agg(out, "cv", 1, 2, "list, [field]", "mergeable", "the coefficient of variation, s / mean; empty under two numbers or a mean of 0",
                "cv($.TRADES[*].PNL)");
        agg(out, "skewness", 1, 2, "list, [field]", "mergeable", "the sample skewness (Excel's SKEW); empty under three numbers or no spread",
                "skewness($.TRADES[*].PNL)");
        agg(out, "kurtosis", 1, 2, "list, [field]", "mergeable",
                "the sample excess kurtosis (Excel's KURT); empty under four numbers or no spread", "kurtosis($.TRADES[*].PNL)");
        agg(out, "geomean", 1, 2, "list, [field]", "mergeable", "the geometric mean; empty when a number is 0 or less (Excel's #NUM!)",
                "geomean($.RETURNS[*].GROWTH)");
        agg(out, "harmean", 1, 2, "list, [field]", "mergeable", "the harmonic mean; empty when a number is 0 or less (Excel's #NUM!)",
                "harmean($.LEGS[*].PRICE)");
        agg(out, "corr", 2, 3, "list, xField, yField | xs, ys", "mergeable", "Pearson's correlation over the pairs where both are numbers",
                "corr($.TRADES, 'PNL', 'NOTIONAL')");
        agg(out, "covar", 2, 3, "list, xField, yField | xs, ys", "mergeable", "the sample covariance (Excel's COVARIANCE.S)",
                "covar($.TRADES, 'PNL', 'NOTIONAL')");
        agg(out, "slope", 2, 3, "list, yField, xField | ys, xs", "mergeable", "the least-squares slope of y on x (Excel's SLOPE, y first)",
                "slope($.TRADES, 'PNL', 'QUANTITY')");
        agg(out, "intercept", 2, 3, "list, yField, xField | ys, xs", "mergeable", "the least-squares intercept of y on x (Excel's INTERCEPT)",
                "intercept($.TRADES, 'PNL', 'QUANTITY')");
        agg(out, "rsq", 2, 3, "list, yField, xField | ys, xs", "mergeable", "r², the square of the correlation (Excel's RSQ)",
                "rsq($.TRADES, 'PNL', 'QUANTITY')");
        agg(out, "mode", 1, 2, "list, [field]", "holistic",
                "the most frequent value, a number or a name; ties to the smallest; empty when no value repeats", "mode($.LEGS[*].CURRENCY)");
        agg(out, "iqr", 1, 2, "list, [field]", "holistic", "p75 − p25 (Excel's QUARTILE.INC)", "iqr($.TRADES[*].PRICE)");
        agg(out, "trimmean", 2, 3, "list, [field], pct", "holistic", "the mean without ⌊n·pct/2⌋ values at each end (Excel's TRIMMEAN)",
                "trimmean($.TRADES[*].PRICE, 0.1)");
        agg(out, "kth_largest", 2, 3, "list, [field], k", "holistic", "the k-th largest number (Excel's LARGE); empty when fewer than k",
                "kth_largest($.TRADES[*].NOTIONAL, 3)");
        agg(out, "kth_smallest", 2, 3, "list, [field], k", "holistic", "the k-th smallest number (Excel's SMALL); empty when fewer than k",
                "kth_smallest($.TRADES[*].NOTIONAL, 3)");
        agg(out, "string_agg", 2, 4, "list, [field], sep, [distinct]", "holistic",
                "the values joined by sep in text order (distinct true: each once); empty past 1,000 values",
                "string_agg($.LEGS[*].CURRENCY, ', ', true)");
        agg(out, "skewness_pop", 1, 2, "list, [field]", "mergeable", "the population skewness (Excel's SKEW.P); empty without spread",
                "skewness_pop($.TRADES[*].PNL)");
        agg(out, "devsq", 1, 2, "list, [field]", "mergeable", "Σ(x − mean)² (Excel's DEVSQ, SAS's CSS)", "devsq($.TRADES[*].PNL)");
        agg(out, "covar_pop", 2, 3, "list, aField, bField | as, bs", "mergeable", "the population covariance (Excel's COVARIANCE.P)",
                "covar_pop($.TRADES, 'PNL', 'NOTIONAL')");
        agg(out, "steyx", 2, 3, "list, yField, xField | ys, xs", "mergeable",
                "the standard error of y predicted from x (Excel's STEYX); empty under three pairs", "steyx($.TRADES, 'PNL', 'QUANTITY')");
        agg(out, "sumproduct", 2, 3, "list, aField, bField | as, bs", "mergeable", "Σ a·b over the pairs both present (Excel's SUMPRODUCT)",
                "sumproduct($.LEGS, 'NOTIONAL', 'RATE')");
        agg(out, "sumsq", 1, 2, "list, [field]", "mergeable", "Σ x² (Excel's SUMSQ, SAS's USS)", "sumsq($.TRADES[*].PNL)");
        agg(out, "product", 1, 2, "list, [field]", "mergeable", "Π x (Excel's PRODUCT); empty for no number",
                "product(map($.RETURNS, 1 + @)) - 1");
        agg(out, "avedev", 1, 2, "list, [field]", "holistic", "the mean absolute deviation from the mean (Excel's AVEDEV)",
                "avedev($.TRADES[*].PRICE)");
        agg(out, "percentile_exc", 2, 3, "list, [field], p", "holistic",
                "the p-quantile at rank p·(n + 1) (Excel's PERCENTILE.EXC; QUARTILE.EXC at k/4); empty outside 1..n",
                "percentile_exc($.TRADES[*].PNL, 0.25)");
        agg(out, "rank_avg", 2, 4, "list, [field], value, [ascending]", "holistic",
                "the value's rank among the list's, ties averaged, largest first unless ascending is 1 (Excel's RANK.AVG)",
                "rank_avg($.TRADES[*].PNL, $.PNL)");
        agg(out, "percent_rank_of", 2, 4, "list, [field], value, [digits]", "holistic",
                "the value's rank as a fraction 0..1, cut to 3 significant digits (Excel's PERCENTRANK.INC)",
                "percent_rank_of($.TRADES[*].PNL, 1000)");
        agg(out, "mode_multi", 1, 2, "list, [field]", "holistic", "every most frequent value, ascending, as a list (Excel's MODE.MULT)",
                "mode_multi($.LEGS[*].CURRENCY)");
        ListAnalytics.register(out);
        measureLevel(out);
        out.add(new Provided("measure", 1, 3, "name, [scope], [options]", "measure",
                "a compute engine's measure for one scope (VaR, ES …), exactly from its engine, never summed",
                "measure('MEASURE', 'GROUP:G-1')", (a, c) -> measure(a)));
        out.add(new Provided("cell", 1, 33, "view, [field, member] ...", "measure", "a cell of a saved pivot view, as the view computes it",
                "cell('desk-pnl', 'DESK', 'DESK-COMM', 'month', '2026-09')", (a, c) -> cell(a)));
        return out;
    }

    /**
     * The functions of a model's measures the engine computes over an answer (docs/architecture/ARCHITECTURE.md §28 2.5, runs E): the filter context,
     * time intelligence, windows, balances, {@code convert} and {@code ratio}. Their signatures are checked mode's; the one compiler
     * ({@code lang.Compiler}) lowers them. Over a list they are refused in words, except {@code ratio}, which is a division (null on 0).
     */
    private static void measureLevel(List<Provided> out) {
        java.util.Set<String> done = new java.util.HashSet<>();
        out.forEach(p -> done.add(p.name()));
        for (Signatures.Signature s : Signatures.get().all()) {
            if (!"E".equals(s.runs()) || done.contains(s.name()) || "measure".equals(s.name()) || "related".equals(s.name()) || "related_rows".equals(s.name())) {
                continue;
            }
            long positional = s.params().stream().filter(p -> !p.keywordOnly()).count();
            long required = s.params().stream().filter(p -> !p.keywordOnly() && !p.optional()).count();
            String args = String.join(", ", s.params().stream().map(p -> p.keywordOnly() ? p.name() + " = …" : p.name()).toList());
            if ("ratio".equals(s.name())) {
                out.add(new Provided("ratio", 2, 2, args, "measure", s.description(), s.examples().get(0).expr(), (a, c) -> {
                    double x = Values.number(a.get(0));
                    double y = Values.number(a.get(1));
                    return Values.isNull(a.get(0)) || Values.isNull(a.get(1)) || y == 0 ? null : Values.normalise(x / y);
                }));
                continue;
            }
            String name = s.name();
            out.add(new Provided(name, (int) required, (int) positional, args, "measure", s.description(), s.examples().get(0).expr(), (a, c) -> {
                throw ListAggregates.error(name, "belongs to a measure of a model: the engine computes it over a query's answer (its groups, dates "
                        + "or filters), never over a list");
            }));
        }
    }

    private static void agg(List<Provided> out, String name, int min, int max, String args, String form, String description,
            String example) {
        out.add(new Provided(name, min, max, args, form, description, example, (a, c) -> ListAggregates.apply(name, a)));
    }

    private static FigureSource figures(String fn) {
        FigureSource f = FIGURES.get();
        if (f == null) {
            throw ListAggregates.error(fn, "figures of a measure or a saved view come from the server, which is not running here");
        }
        return f;
    }

    private static Object measure(List<Object> a) {
        String name = Values.text(a.get(0));
        String scope = a.size() > 1 ? Values.text(a.get(1)) : "";
        Map<String, String> options = a.size() > 2 ? options(a.get(2)) : Map.of();
        try {
            return figure(figures("measure").measure(name, scope, options));
        } catch (IllegalArgumentException e) {
            throw ListAggregates.error("measure", e.getMessage());
        }
    }

    private static Object cell(List<Object> a) {
        if (a.size() % 2 == 0) {
            throw ListAggregates.error("cell", "after the view, write pairs of a field and its member: cell('desk-pnl', 'DESK', 'DESK-COMM')");
        }
        Map<String, String> coordinates = new LinkedHashMap<>();
        for (int i = 1; i < a.size(); i += 2) {
            coordinates.put(Values.text(a.get(i)), Values.text(a.get(i + 1)));
        }
        try {
            return figure(figures("cell").cell(Values.text(a.get(0)), coordinates));
        } catch (IllegalArgumentException e) {
            throw ListAggregates.error("cell", e.getMessage());
        }
    }

    /** Options as an object ({@code $.OPTIONS}) or as text: {@code 'date=2026-09-30, confidence=0.99'}. */
    static Map<String, String> options(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        Object v = Values.simplify(raw);
        if (v instanceof DataNode.Obj o) {
            o.fields().forEach((k, x) -> out.put(k, Values.text(x)));
        } else if (v != null) {
            for (String part : Values.text(v).split("[,;]")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    out.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
                } else if (!part.isBlank()) {
                    throw ListAggregates.error("measure", "the option '" + part.strip() + "' is not written name=value");
                }
            }
        }
        return out;
    }

    private static Object figure(Object v) {
        return v instanceof Number n && !(v instanceof java.math.BigDecimal) ? Values.normalise(n.doubleValue()) : v;
    }
}
