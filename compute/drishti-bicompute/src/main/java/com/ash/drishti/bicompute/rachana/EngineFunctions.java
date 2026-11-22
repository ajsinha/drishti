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
import com.ash.rachana.el.FunctionProvider;
import com.ash.rachana.el.Values;
import com.ash.rachana.el.check.Signatures;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The pivot engine's functions for Rachana (docs/architecture/ARCHITECTURE.md parts 1 to 3), found by {@link java.util.ServiceLoader}
 * ({@code META-INF/services/com.ash.rachana.el.FunctionProvider}): only what the engine alone computes. Every list aggregate, the
 * list analytics and the grouping behind {@code group(...)} are Core's ({@code com.ash.rachana.agg}, the one aggregate kernel, SPLIT_PLAN
 * 8.3h; a profile may not give a Core name again). Here: the {@code _once} family over a list ({@link OnceAggregates}, the same
 * {@code OncePerParent} a pivot cell runs), the functions of a model's measures (refused over a list in words), and {@code measure(...)}
 * and {@code cell(...)}, answered by the server's {@link FigureSource} (registered with {@link #figures(FigureSource)}; refused with the
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
        return null;                           // Core's own grouping (com.ash.rachana.agg), the one implementation (8.3h)
    }

    @Override
    public List<Provided> functions() {
        List<Provided> out = new ArrayList<>();
        agg(out, "sum_once", 2, 3, "list, field, parentField | xs, parents", "holistic", "Σx counting each parent once",
                "sum_once($.LEGS, 'QUANTITY', 'TRADE_ID')");
        agg(out, "count_once", 2, 3, "list, field, parentField | xs, parents", "holistic", "the parents with a value, each once",
                "count_once($.LEGS, 'QUANTITY', 'TRADE_ID')");
        agg(out, "avg_once", 2, 3, "list, field, parentField | xs, parents", "holistic", "the mean over the parents, each once",
                "avg_once($.LEGS, 'QUANTITY', 'TRADE_ID')");
        measureLevel(out);
        out.add(new Provided("measure", 1, 3, "name, [scope], [options]", "measure",
                "a compute engine's measure for one scope (VaR, ES …), exactly from its engine, never summed",
                "measure('MEASURE', 'GROUP:G-1')", (a, c) -> measure(a)));
        out.add(new Provided("cell", 1, 33, "view, [field, member] ...", "measure", "a cell of a saved pivot view, as the view computes it",
                "cell('desk-pnl', 'DESK', 'DESK-COMM', 'month', '2026-09')", (a, c) -> cell(a)));
        // a provider may not give a name Core gives (SPLIT_PLAN 8.3h): the list aggregates and list functions are Core's now (the aggregate
        // kernel); what stays here is the _once family, the measure-level functions, measure and cell
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
        com.ash.rachana.agg.CoreAggregates.INSTANCE.functions().forEach(p -> done.add(p.name()));   // Core's: never given again
        done.add("group");
        for (Signatures.Signature s : Signatures.get().all()) {
            if (!"E".equals(s.runs()) || done.contains(s.name()) || "measure".equals(s.name()) || "related".equals(s.name()) || "related_rows".equals(s.name())) {
                continue;
            }
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
            // at most every parameter: one written by place where its name is wanted is Core's catalogue check to refuse, in Core's words
            // (level 1.1 and later: "takes at most 1 argument by place before its named ones"), as on the reference instance
            out.add(new Provided(name, (int) required, s.params().size(), args, "measure", s.description(), s.examples().get(0).expr(), (a, c) -> {
                throw OnceAggregates.error(name, "belongs to a measure of a model: the engine computes it over a query's answer (its groups, dates "
                        + "or filters), never over a list");
            }));
        }
    }

    private static void agg(List<Provided> out, String name, int min, int max, String args, String form, String description,
            String example) {
        out.add(new Provided(name, min, max, args, form, description, example, (a, c) -> OnceAggregates.apply(name, a)));
    }

    private static FigureSource figures(String fn) {
        FigureSource f = FIGURES.get();
        if (f == null) {
            throw OnceAggregates.error(fn, "figures of a measure or a saved view come from the server, which is not running here");
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
            throw OnceAggregates.error("measure", e.getMessage());
        }
    }

    private static Object cell(List<Object> a) {
        if (a.size() % 2 == 0) {
            throw OnceAggregates.error("cell", "after the view, write pairs of a field and its member: cell('desk-pnl', 'DESK', 'DESK-COMM')");
        }
        Map<String, String> coordinates = new LinkedHashMap<>();
        for (int i = 1; i < a.size(); i += 2) {
            coordinates.put(Values.text(a.get(i)), Values.text(a.get(i + 1)));
        }
        try {
            return figure(figures("cell").cell(Values.text(a.get(0)), coordinates));
        } catch (IllegalArgumentException e) {
            throw OnceAggregates.error("cell", e.getMessage());
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
                    throw OnceAggregates.error("measure", "the option '" + part.strip() + "' is not written name=value");
                }
            }
        }
        return out;
    }

    private static Object figure(Object v) {
        return v instanceof Number n && !(v instanceof java.math.BigDecimal) ? Values.normalise(n.doubleValue()) : v;
    }
}
