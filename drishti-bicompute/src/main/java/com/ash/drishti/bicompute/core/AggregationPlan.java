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
package com.ash.drishti.bicompute.core;

import com.ash.drishti.bicompute.spi.AggFunction;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a provider is asked (PIVOT_ENGINE.md section 6.1): the cell sets, the functions (each with its kind and state), the filters, the date,
 * the as-of version and the shard. A provider answers {@link Partials}: per cell key and function, a partial state.
 *
 * @param source the provider's source ({@code trade}, {@code var})
 * @param date the business date (null: the source's latest)
 * @param asOf a data version or snapshot (null: the latest)
 * @param filters dimension id to its filter, applied to every set (after the person's row rules and masks)
 * @param dims every dimension the plan names, by id
 * @param functions the functions, in the answer's measure order
 * @param sets the cell sets
 * @param shard this node's part of the rows (null: all of them)
 * @param maxGroups the most groups a query may return before the view is refused (the cell budget's guard; 0: no guard)
 * @param options the view's provider options (the VaR provider's scope and lens), by name
 */
public record AggregationPlan(String source, String date, String asOf, Map<String, Filter> filters, Map<String, Dim> dims,
        List<FunctionCall> functions, List<CellSet> sets, Shard shard, int maxGroups, Money money, Map<String, String> options) {

    /** A plan without provider options. */
    public AggregationPlan(String source, String date, String asOf, Map<String, Filter> filters, Map<String, Dim> dims,
            List<FunctionCall> functions, List<CellSet> sets, Shard shard, int maxGroups, Money money) {
        this(source, date, asOf, filters, dims, functions, sets, shard, maxGroups, money, Map.of());
    }

    /**
     * Money in the plan (PIVOT_ENGINE.md section 5.4).
     *
     * @param currencyField the field of a row's currency (null: the source has none)
     * @param fields the money fields
     * @param conversion the view's explicit conversion (null: none, so a cell mixing currencies is refused for money measures)
     */
    public record Money(String currencyField, java.util.Set<String> fields, PivotView.Currency conversion) {
        public Money {
            fields = java.util.Set.copyOf(fields);
        }

        public static Money none() {
            return new Money(null, java.util.Set.of(), null);
        }

        /** True when the function's inputs are money (its figures are in a currency). */
        public boolean money(FunctionCall f) {
            return f.fn().numeric() && f.inputs().stream().anyMatch(fields::contains) && !"count".equals(f.fn().name())
                    && !"count_distinct".equals(f.fn().name());
        }

        /** True when the provider must say, per cell, whether its rows are in one currency. */
        public boolean guard() {
            return currencyField != null && conversion == null && !fields.isEmpty();
        }
    }

    public AggregationPlan {
        money = money == null ? Money.none() : money;
        options = java.util.Collections.unmodifiableMap(new TreeMap<>(options == null ? Map.of() : options));
        filters = java.util.Collections.unmodifiableMap(new TreeMap<>(filters == null ? Map.of() : filters));
        dims = Map.copyOf(dims);
        functions = List.copyOf(functions);
        sets = List.copyOf(sets);
    }

    /** The same plan for other sets (the cache's misses) or other functions. */
    public AggregationPlan with(List<CellSet> s, List<FunctionCall> f) {
        return new AggregationPlan(source, date, asOf, filters, dims, f, s, shard, maxGroups, money, options);
    }

    /** The same plan for one shard of the rows. */
    public AggregationPlan on(Shard s) {
        return new AggregationPlan(source, date, asOf, filters, dims, functions, sets, s, maxGroups, money, options);
    }

    /** The canonical text of the filters (part of every cache key). */
    public String filterSignature() {
        StringBuilder b = new StringBuilder();
        filters.forEach((k, f) -> b.append(k).append(f.signature()).append(';'));
        return b.toString();
    }

    /**
     * A function and its inputs, as a view names it.
     *
     * @param id the canonical text ({@code sum(NOTIONAL_USD)}, {@code percentile(PNL_1D, 0.99)}): part of every cache key
     * @param label what a column header says
     * @param fn the function
     * @param inputs the input fields, in the function's order
     */
    public record FunctionCall(String id, String label, AggFunction<Object> fn, List<String> inputs) {
        public FunctionCall {
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * A filter on a dimension: the values kept ({@code in}, empty: all) and the values left out ({@code notIn}).
     */
    public record Filter(List<String> in, List<String> notIn) {
        public Filter {
            in = in == null ? List.of() : List.copyOf(in);
            notIn = notIn == null ? List.of() : List.copyOf(notIn);
        }

        String signature() {
            return "in" + new java.util.TreeSet<>(in) + "out" + new java.util.TreeSet<>(notIn);
        }

        /** In words ({@code in RATES, FX}; {@code not MATURED}). */
        public String words() {
            String a = in.isEmpty() ? "" : "in " + String.join(", ", in);
            String b = notIn.isEmpty() ? "" : "not " + String.join(", ", notIn);
            return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b;
        }
    }

    /**
     * One node's part of the rows (scatter and gather, PIVOT_ENGINE.md section 9.3): part {@code index} of {@code count}; every row is in
     * exactly one part.
     */
    public record Shard(int index, int count) {
        public Shard {
            if (count < 1 || index < 0 || index >= count) {
                throw new IllegalArgumentException("shard " + index + " of " + count);
            }
        }
    }
}
