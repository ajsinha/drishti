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
import com.ash.drishti.bicompute.spi.FigureSource;
import com.ash.drishti.rachana.el.FunctionProvider;
import com.ash.drishti.rachana.el.Values;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The pivot engine as Rachana's provider of every aggregate (ONE_GRAMMAR.md parts 1 to 3), found by {@link java.util.ServiceLoader}
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
        agg(out, "count", 1, 2, "list, [field]", "mergeable", "the elements (or the field's values) that are not empty",
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
        out.add(new Provided("measure", 1, 3, "name, [scope], [options]", "measure",
                "a model measure or a compute engine's measure for one scope, exactly from its engine (never summed)",
                "measure('VAR', 'DESK:DESK-COMM')", (a, c) -> measure(a)));
        out.add(new Provided("cell", 1, 33, "view, [field, member] ...", "measure", "a cell of a saved pivot view, as the view computes it",
                "cell('desk-pnl', 'DESK', 'DESK-COMM', 'month', '2026-09')", (a, c) -> cell(a)));
        return out;
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
