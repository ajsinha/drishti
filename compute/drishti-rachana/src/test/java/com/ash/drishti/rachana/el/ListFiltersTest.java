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

import static com.ash.drishti.rachana.el.Fx.eval;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The list filters (contains / any of / all of): {@code has}, {@code any_of}, {@code all_of} over a list attribute, whether it comes as
 * an array (a document) or as its JSON text (a promoted {@code array<string>} column read back); a single value is a list of one; no
 * value chosen holds everything; a missing list holds nothing.
 */
class ListFiltersTest {

    @Test
    void containsAnyOfAndAllOfOverAnArray() {
        assertThat(eval("has(['fx', 'rates'], 'fx')")).isEqualTo(true);
        assertThat(eval("has(['fx', 'rates'], 'credit')")).isEqualTo(false);
        assertThat(eval("any_of(['fx', 'rates'], ['credit', 'rates'])")).isEqualTo(true);
        assertThat(eval("any_of(['fx', 'rates'], ['credit', 'equity'])")).isEqualTo(false);
        assertThat(eval("all_of(['fx', 'rates', 'hedge'], ['fx', 'hedge'])")).isEqualTo(true);
        assertThat(eval("all_of(['fx', 'rates'], ['fx', 'hedge'])")).isEqualTo(false);
        assertThat(eval("has([2025, 2026], 2026)")).isEqualTo(true);            // numbers numerically
        assertThat(eval("has([2025, 2026], '2026')")).isEqualTo(true);
    }

    @Test
    void aListsJsonTextReadsAsItsListAndASingleValueAsAListOfOne() {
        assertThat(eval("has('[\"fx\",\"rates\"]', 'rates')")).isEqualTo(true);
        assertThat(eval("all_of('[\"fx\",\"rates\"]', ['rates', 'fx'])")).isEqualTo(true);
        assertThat(eval("has('fx', 'fx')")).isEqualTo(true);
        assertThat(eval("has('fx, rates', 'fx')")).isEqualTo(false);            // text is not split: a list is a list
    }

    @Test
    void nothingChosenHoldsEverythingAndAMissingListHoldsNothing() {
        assertThat(eval("any_of(['fx'], [])")).isEqualTo(true);
        assertThat(eval("all_of(['fx'], [])")).isEqualTo(true);
        assertThat(eval("has($.NOPE, 'fx')")).isEqualTo(false);
        assertThat(eval("any_of($.NOPE, ['fx'])")).isEqualTo(false);
        assertThat(eval("all_of([], ['fx'])")).isEqualTo(false);
    }
}
