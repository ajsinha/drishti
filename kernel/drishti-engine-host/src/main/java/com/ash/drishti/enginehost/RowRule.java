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
package com.ash.drishti.enginehost;

import com.ash.engine.Filter;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A row predicate the host applies before delivery: a role's row rule on a kind ({@code DESK in $user.desks}), or an engine file's
 * {@code where}. The host evaluates it; an engine never sees it.
 */
public interface RowRule {

    /** Every row. */
    RowRule ALL = new RowRule() {
        @Override public boolean all() { return true; }
        @Override public Set<String> fields() { return Set.of(); }
        @Override public boolean test(Function<String, Object> row) { return true; }
        @Override public String text() { return ""; }
        @Override public Optional<Filter> asFilter() { return Optional.of(Filter.ALL); }
    };

    /** True when every row passes (nothing to evaluate). */
    boolean all();

    /** The fields it reads (they are read with the input's columns even when the engine did not ask for them). */
    Set<String> fields();

    /** Whether the row passes; {@code row} gives a field's value (String, Double, Boolean or null). A row it cannot decide does not pass. */
    boolean test(Function<String, Object> row);

    /** The rule as written (part of a visibility's signature). */
    String text();

    /** The rule as an engine filter, when it is made only of equalities and lists; empty otherwise (a shared engine is then refused). */
    default Optional<Filter> asFilter() {
        return all() ? Optional.of(Filter.ALL) : Optional.empty();
    }
}
