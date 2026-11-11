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
package com.ash.drishti.bicompute.lang;

import com.ash.drishti.bicompute.context.FilterContext;
import com.ash.drishti.rachana.el.Expr;
import java.util.List;

/**
 * What the one compiler makes of a checked Rachana expression (HOW_IT_FITS.md §A28 2.6): a row expression becomes the engine's row
 * expression ({@link Row}: Rachana's tree, evaluated in Java over a live table's rows, and its SQL for a scan); an aggregate the
 * engine's aggregate function ({@link Aggregate}, the same {@code AggFunction} a pivot cell runs); a measure of measures a
 * {@link Shape} over its parts; a filter-context function the engine's context sub-plan ({@link Context}, decided by
 * {@link FilterContext}); a window or a time function a computation over the answer ({@link Window}, {@link Time}). Immutable.
 */
public sealed interface Lowered {

    /**
     * A row expression.
     *
     * @param expr Rachana's tree (the Java form)
     * @param sql the same in SQL over the source's columns
     * @param masked true when it reads a masked column (its Java value is masked; its SQL reads the column as null)
     */
    record Row(Expr expr, String sql, boolean masked) implements Lowered {}

    /**
     * An aggregate of the engine.
     *
     * @param fn the engine's function ({@code sum}, {@code count}, {@code distinct}, {@code percentile} ...)
     * @param inputs the rows' inputs (empty: {@code count(table)}, the rows themselves)
     * @param where the measure's own condition (layer 4), or null
     * @param numbers the function's literal options ({@code 0.95} of a percentile)
     */
    record Aggregate(String fn, List<Row> inputs, Row where, List<Double> numbers) implements Lowered {
        public Aggregate {
            inputs = List.copyOf(inputs);
            numbers = List.copyOf(numbers);
        }
    }

    /**
     * A measure of the model ({@code measure('MTM')}), or a compute engine's for a scope ({@code measure('MEASURE', 'GROUP:G-1')}).
     *
     * @param scope the engine's scope, or null for the model's own measure
     */
    record Measure(String name, String scope) implements Lowered {}

    /**
     * A value computed from other values of the same group ({@code ratio(sum(a), sum(b)) * 1000000}, {@code round(measure('MTM'))}):
     * Rachana's tree with each part read as the local {@code __h0}, {@code __h1} ..., and its SQL with each part written {@code {{h0}}}.
     */
    record Shape(Expr expr, String sql, List<Lowered> parts) implements Lowered {
        public Shape {
            parts = List.copyOf(parts);
        }

        /** The name a part is read by in the tree. */
        public static String local(int i) {
            return "__h" + i;
        }

        /** The placeholder of a part in the SQL. */
        public static String placeholder(int i) {
            return "{{h" + i + "}}";
        }
    }

    /**
     * A filter-context function: the aggregate {@code inner} in an edited filter context (BI_DESIGN 3.3.5).
     *
     * @param fn {@code calc}, {@code share_of}, {@code parent_of}, {@code avg_by}, {@code avg_over}, {@code as_of}
     * @param edit what the context edits (the date of {@code as_of} is worked out before the query runs, see {@code date})
     * @param where {@code calc}'s condition read per row, or null
     * @param date {@code as_of}'s date, a row-free expression ({@code shift_date(asof(), -1)}), or null
     * @param hierarchy {@code parent_of}'s hierarchy, or null
     */
    record Context(String fn, FilterContext.Edit edit, Lowered inner, Row where, Expr date, String hierarchy) implements Lowered {}

    /**
     * A window over the answer ({@code lag}, {@code lead}, {@code rank}, {@code dense_rank}, {@code percent_rank}, {@code moving_*}).
     *
     * @param n the offset or the window's length, or null
     * @param order the field ordering the window, or null (ranks order by the value)
     * @param by the fields partitioning it
     * @param desc ranks: largest first
     */
    record Window(String fn, Lowered inner, Integer n, String order, List<String> by, boolean desc) implements Lowered {
        public Window {
            by = List.copyOf(by);
        }
    }

    /**
     * Time intelligence and balances ({@code ytd}, {@code mtd}, {@code qtd}, {@code prior_period}, {@code same_period_last_year},
     * {@code running}, {@code fiscal_ytd}, {@code latest}, {@code earliest}, {@code opening}, {@code closing}).
     *
     * @param date the date field
     * @param period the period of {@code opening} and {@code closing}, else null
     */
    record Time(String fn, Lowered inner, String date, String period) implements Lowered {}

    /** {@code convert(agg, to = 'USD', at = 'as-of')}: the aggregate in one currency (the currency guard lifted). */
    record Convert(Lowered inner, String to, String at) implements Lowered {}
}
