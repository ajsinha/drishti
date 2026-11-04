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
package com.ash.drishti.bicompute.spi;

import java.util.List;

/**
 * An aggregation function (PIVOT_ENGINE.md section 8.1): four steps, start, add (a columnar batch), merge, finish, and a {@link Kind}. A
 * state crosses threads, nodes and the cache, so it is serialisable ({@link #encode}, {@link #decode}) and weighed ({@link #bytes}).
 *
 * <p>A function may also say how a SQL engine makes its state ({@link #sqlState}, {@link #fromSql}): the tabular provider then computes the
 * states of every cell and total in one {@code GROUP BY GROUPING SETS} query, and the core merges and finishes them exactly as it would the
 * states {@link #add} made. A holistic function's SQL form is its final figure for that grouping set, computed by the engine from the
 * set's own rows (never a merge of other sets).
 *
 * @param <S> the state
 */
public interface AggFunction<S> {

    /** The function's name as a view writes it ({@code sum}, {@code avg}, {@code median}). */
    String name();

    Kind kind();

    /** The number of input columns ({@code count()} has none, {@code wavg(x, w)} two). */
    int inputs();

    /** True when the inputs are read as numbers (false: as text, a distinct count of names). */
    default boolean numeric() {
        return true;
    }

    /** An empty state. Pure; no data. */
    S start();

    /** The state with a batch of the cell's rows added; may return a new object or the one given. */
    S add(S state, Batch batch);

    /**
     * Two partial states of the same cell as one (two batches, two threads, two nodes, a cached cell and a new one). Mergeable: exact,
     * associative and commutative within the declared tolerance. Holistic: the union of the members' values, exact only on inputs.
     */
    S merge(S a, S b);

    /** The figure; null when the cell has nothing to say (no values), with the reason given by {@link #emptyNote}. */
    Double finish(S state);

    /**
     * The figure as the cell shows it: {@link #finish}'s number, or text for a function whose answer is text ({@code string_agg}, the
     * {@code mode} of names). Null as {@link #finish} is null. Everything that does arithmetic on a figure reads {@link #finish}.
     */
    default Object result(S state) {
        return finish(state);
    }

    /**
     * The unit of the figure when it is not the input's: {@code count} for a count, the empty text for a pure number (a correlation, a
     * skewness, a ratio), null (the default) for the input's own unit or currency.
     */
    default String resultUnit() {
        return null;
    }

    /** Why {@link #finish} is null for a state (shown in the cell, never a blank without a reason). */
    default String emptyNote() {
        return "no values in the cell";
    }

    byte[] encode(S state);

    S decode(byte[] bytes);

    /** The bytes a state holds (the cache's weigher). */
    long bytes(S state);

    /**
     * True when a total of this function is the plain sum of its parts' figures (sum, count): an Excel export may then write a total as a
     * {@code SUM} formula of its cells. False for everything else: those totals are values, computed by the engine.
     */
    default boolean plainSum() {
        return false;
    }

    /**
     * The SQL aggregate expressions whose values make the state for one grouping set ({@code inputs}: the quoted input expressions);
     * empty when the function cannot be computed in SQL.
     */
    List<String> sqlState(List<String> inputs);

    /** The state from the values of {@link #sqlState}'s expressions, in order. */
    S fromSql(Object[] parts);

    /** True when the state made by {@link #fromSql} may be merged with another (false for a holistic function's final figure). */
    default boolean sqlStateMergeable() {
        return kind() != Kind.HOLISTIC;
    }
}
