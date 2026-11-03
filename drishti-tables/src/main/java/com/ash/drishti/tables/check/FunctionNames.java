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
package com.ash.drishti.tables.check;

import com.ash.drishti.bicompute.functions.Functions;
import java.util.HashSet;
import java.util.Set;

/**
 * The function names the scope rules need (BI_FOUNDATION_2911E.md section 2.5, the rows that run in the engine, "E"): aggregates (the
 * pivot engine's built-ins, read from its catalogue, plus DEL's), and the time, window, balance and filter-context functions that only a
 * measure may use. A scalar function runs in Rachana and is not listed here.
 */
public final class FunctionNames {

    /** Aggregates of a group's rows: the engine's built-ins and DEL's aggregate family. */
    public static final Set<String> AGGREGATES;

    /** Functions over an aggregate (time intelligence, windows, balances, conversion): measure scope only. */
    public static final Set<String> OVER_AGGREGATES = Set.of("ytd", "mtd", "qtd", "prior_period", "same_period_last_year", "running", "fiscal_ytd",
            "lag", "lead", "rank", "dense_rank", "percent_rank", "moving_avg", "moving_sum", "moving_min", "moving_max", "cumulative", "latest",
            "earliest", "opening", "closing", "avg_over", "avg_by", "convert", "ratio");

    /** The filter-context functions (section 2.3): measure scope only, at most three deep. */
    public static final Set<String> CONTEXT = Set.of("calc", "share_of", "parent_of", "as_of");

    /** Display concerns of a Sutra, refused in tables and models. */
    public static final Set<String> DISPLAY = Set.of("link", "fmt");

    /** Measure-only names that are not aggregates of rows: {@code measure('name')}. */
    public static final Set<String> MEASURE_REFS = Set.of("measure");

    static {
        Set<String> a = new HashSet<>(Set.of("sum", "avg", "min", "max", "count", "distinct", "percentile"));
        for (Functions.Info i : Functions.BUILT_INS) {
            a.add(i.name());
        }
        AGGREGATES = Set.copyOf(a);
    }

    private FunctionNames() {}

    /** True when {@code f} may only be written in a measure. */
    public static boolean measureOnly(String f) {
        return AGGREGATES.contains(f) || OVER_AGGREGATES.contains(f) || CONTEXT.contains(f) || MEASURE_REFS.contains(f);
    }
}
