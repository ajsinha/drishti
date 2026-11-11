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
package com.ash.drishti.rachana.el;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The functions Rachana's table holds from {@link FunctionProvider}s (docs/architecture/ARCHITECTURE.md parts 1 and 2): found once, by
 * {@link ServiceLoader}, when the table is built. Rachana implements no aggregate: these names are the ones it asks a provider for,
 * so that an expression using one compiles whether or not the provider is on the class path; without it, evaluating one is refused
 * with the reason ({@code DRS-2102}), never computed another way.
 */
final class Aggregates {

    /** The names Rachana asks the pivot engine for (the engine may add more): aggregates over a list, and figures of other engines. */
    static final List<String> EXPECTED = List.of("sum", "count", "avg", "min", "max", "median", "percentile", "distinct", "wavg",
            "variance", "stddev", "variance_pop", "stddev_pop", "sum_once", "count_once", "avg_once", "measure", "cell");

    /**
     * The functions of a model's measures the engine computes over an answer, never over a list (docs/architecture/ARCHITECTURE.md §28 2.5, runs E):
     * the filter context ({@code calc}, {@code share_of}, {@code parent_of}, {@code as_of}, {@code avg_by}, {@code avg_over}), time
     * intelligence, windows, balances, conversion and lookup. Rachana parses and checks them; the engine's compiler lowers them.
     */
    static final List<String> MEASURE_LEVEL = List.of("ratio", "convert", "related", "related_rows", "fx", "avg_by", "sum_by", "min_by", "max_by", "avg_over", "calc", "share_of",
            "parent_of", "as_of", "ytd", "mtd", "qtd", "prior_period", "same_period_last_year", "running", "fiscal_ytd", "lag", "lead",
            "rank", "dense_rank", "percent_rank", "moving_avg", "moving_sum", "moving_min", "moving_max", "latest", "earliest", "opening",
            "closing");

    /** Names whose one-list form a provider computes while Rachana keeps their several-argument form (old least/greatest). */
    static final List<String> SHARED = List.of("min", "max");

    private static final Map<String, FunctionProvider.Provided> PROVIDED;
    private static final Map<String, String> SOURCE;
    private static final FunctionProvider.Grouping GROUPING;
    private static final String GROUPING_SOURCE;

    static {
        Map<String, FunctionProvider.Provided> provided = new LinkedHashMap<>();
        Map<String, String> source = new LinkedHashMap<>();
        FunctionProvider.Grouping grouping = null;
        String groupingSource = null;
        for (FunctionProvider p : ServiceLoader.load(FunctionProvider.class, Aggregates.class.getClassLoader())) {
            for (FunctionProvider.Provided f : p.functions()) {
                if (provided.putIfAbsent(f.name(), f) != null) {
                    throw new IllegalStateException("Rachana-EL function " + f.name() + " is provided twice (" + source.get(f.name())
                            + ", " + p.source() + ")");
                }
                source.put(f.name(), p.source());
            }
            if (grouping == null && p.grouping() != null) {
                grouping = p.grouping();
                groupingSource = p.source();
            }
        }
        PROVIDED = Collections.unmodifiableMap(provided);
        SOURCE = Collections.unmodifiableMap(source);
        GROUPING = grouping;
        GROUPING_SOURCE = groupingSource;
    }

    private Aggregates() {}

    /**
     * Registers the provided functions into the table, and every expected name no provider gives as a refusal; {@code min} and
     * {@code max} stay Rachana's entries ({@link MathFunctions}), which hand their one-list form to {@link #call}.
     */
    static void install(Functions.Table t) {
        t.family("provided");
        for (FunctionProvider.Provided f : PROVIDED.values()) {
            if (SHARED.contains(f.name())) {
                continue;
            }
            t.fn(f.name(), f.minArgs(), f.maxArgs(), f.function());
        }
        for (String name : EXPECTED) {
            if (!PROVIDED.containsKey(name) && !SHARED.contains(name)) {
                t.fn(name, 0, 64, (a, c) -> {
                    throw refused(name);
                });
            }
        }
        for (String name : MEASURE_LEVEL) {
            if (!PROVIDED.containsKey(name)) {
                t.fn(name, 0, 64, (a, c) -> {
                    throw refused(name);
                });
            }
        }
    }

    /** A provided function, evaluated; refused with the reason when no provider gives it. */
    static Object call(String name, List<Object> args, EvalContext c) {
        FunctionProvider.Provided f = PROVIDED.get(name);
        if (f == null) {
            throw refused(name);
        }
        return f.function().apply(args, c);
    }

    /** The grouping behind {@code group(...)}; refused with the reason when no provider groups. */
    static FunctionProvider.Grouping grouping() {
        if (GROUPING == null) {
            throw refused("group");
        }
        return GROUPING;
    }

    /** True when {@code name} is a provided aggregate (one {@code group(list, key, aggregate(@...))} may name). */
    static boolean provided(String name) {
        return PROVIDED.containsKey(name);
    }

    /** The provided functions, by name, with who provides them. */
    static Map<String, FunctionProvider.Provided> all() {
        return PROVIDED;
    }

    /** Who provides {@code name} ({@code group}: the grouping's provider); null when none does. */
    static String source(String name) {
        return "group".equals(name) ? GROUPING_SOURCE : SOURCE.get(name);
    }

    private static ElException refused(String name) {
        return Args.error(name, "an aggregate (and a figure of another engine) comes only from the pivot engine (drishti-bicompute), "
                + "which is not on the class path here; Rachana computes no aggregate itself (docs/architecture/ARCHITECTURE.md)");
    }
}
