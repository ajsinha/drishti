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

import static com.ash.drishti.rachana.el.Fx.EL;
import static com.ash.drishti.rachana.el.Fx.compileError;
import static com.ash.drishti.rachana.el.Fx.eval;
import static com.ash.drishti.rachana.el.Fx.list;
import static com.ash.drishti.rachana.el.Fx.masked;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The logic functions (FUNCTION_CATALOGUE.md section 1): only the branch taken is evaluated, and a mask is never seen through. */
class LogicFunctionsTest {

    @Test
    void ifEvaluatesOnlyTheBranchTaken() {
        assertThat(eval("if($.DIRECTION == 'SHORT', -1, 1)")).isEqualTo(-1L);
        assertThat(eval("if($.DIRECTION == 'LONG', -1, 1)")).isEqualTo(1L);
        assertThat(eval("if(false, 1)")).isNull();
        assertThat(eval("if(true, 'ok', round_up('x'))")).isEqualTo("ok");     // the other branch would be a problem
        assertThat(eval("if($.NOPE, 1, 2)")).isEqualTo(2L);
        assertThat(masked("if($.MTM > 0, 1, 2)")).isTrue();
        compileError("if(true)", "if takes 2-3 argument(s), got 1");
    }

    @Test
    void ifsTakesTheFirstTrueCondition() {
        assertThat(eval("ifs($.QUANTITY > 1e6, 'large', $.QUANTITY > 1e5, 'medium', 'small')")).isEqualTo("large");
        assertThat(eval("ifs(false, 1, true, 2, 3)")).isEqualTo(2L);
        assertThat(eval("ifs(false, 1, false, 2)")).isNull();
        assertThat(eval("ifs(false, 1, 'else')")).isEqualTo("else");
        assertThat(eval("ifs(true, 1, $.MTM > 0, 2)")).isEqualTo(1L);           // a masked condition never reached
        assertThat(masked("ifs(false, 1, $.MTM > 0, 2, 3)")).isTrue();
        compileError("ifs(true)", "ifs takes 2-64 argument(s), got 1");
    }

    @Test
    void switchMatchesAValue() {
        assertThat(eval("switch($.LEGS[0].LEG_TYPE, 'FIXED', 'F', 'FLOAT', 'V', '?')")).isEqualTo("F");
        assertThat(eval("switch($.LEGS[1].LEG_TYPE, 'FIXED', 'F', 'FLOAT', 'V', '?')")).isEqualTo("V");
        assertThat(eval("switch('X', 'FIXED', 'F', '?')")).isEqualTo("?");
        assertThat(eval("switch('X', 'A', 1)")).isNull();
        assertThat(eval("switch(1, '1.0', 'one')")).isEqualTo("one");            // equality as ==
        assertThat(eval("switch($.NOPE, null, 'none', 'some')")).isEqualTo("none");
        assertThat(eval("switch('A', 'A', 1, 'B', round_up('x'))")).isEqualTo(1L);  // answers not chosen are not evaluated
        assertThat(masked("switch($.MTM, 1, 'a')")).isTrue();
        assertThat(masked("switch('B', $.MTM, 'a', 'B', 'b')")).isTrue();
        compileError("switch('A', 'A')", "switch takes 3-64 argument(s), got 2");
    }

    @Test
    void iferrorGivesAFallback() {
        assertThat(eval("iferror(1 / 0, 0)")).isEqualTo(0L);
        assertThat(eval("iferror(round_up('abc'), -1)")).isEqualTo(-1L);
        assertThat(eval("iferror(sqrt(-1), 'negative')")).isEqualTo("negative");
        assertThat(eval("iferror($.QUANTITY * $.NOPE, 0)")).isEqualTo(0L);       // not a number
        assertThat(eval("iferror($.NOPE, 'n/a')")).isEqualTo("n/a");
        assertThat(eval("iferror(5, round_up('x'))")).isEqualTo(5L);             // the fallback only when needed
        assertThat(eval("iferror(regex_extract('a', $.TRADE_ID + '('), 'bad pattern')")).isEqualTo("bad pattern");
        assertThat(masked("iferror($.MTM, 0)")).isTrue();
    }

    @Test
    void letNamesAPartToReuse() {
        assertThat(eval("let(n = $.LEGS[0].NOTIONAL, q = 2, n * q)")).isEqualTo(484000000L);
        assertThat(eval("let(a = 2, b = a * 3, b + a)")).isEqualTo(8L);
        assertThat(eval("let(QUANTITY = 1, QUANTITY + 1)")).isEqualTo(2L);       // a name hides a field
        assertThat(eval("let(a = 1, let(a = 5, a) + a)")).isEqualTo(6L);
        assertThat(eval("QUANTITY")).isEqualTo(242000000L);                      // and only inside its let
        assertThat(list("map($.LEGS, let(n = @.NOTIONAL, n / 1e6))")).containsExactly(242L, 242L);
        assertThat(eval("let(leg = first($.LEGS), leg.CURRENCY)")).isEqualTo("AUD");
        assertThat(masked("let(m = $.MTM, m * 2)")).isTrue();
        assertThat(eval("let(m = $.MTM, 3)")).isEqualTo(3L);
        TreeSet<String> paths = new TreeSet<>();
        EL.compile("let(n = $.LEGS[0].NOTIONAL, n * 2)").paths(paths::add);
        assertThat(paths).containsExactly("$.LEGS[0].NOTIONAL");
        compileError("let(5)", "let needs a name = value before its result");
        compileError("let(a = 1)", "expected ',' and then the result");
        compileError("let(true = 1, 2)", "let: 'true' cannot be a name");
        compileError("let(a = 1, a = 2, a)", "let: 'a' is named twice");
        compileError("let(a = 1, a, 2)", "only the last argument is the result");
        compileError("a = 1", "unexpected '='");
    }

    @Test
    void theOneNamesAndTheOldReadAlike() {
        assertThat(eval("one_of($.DESK, $.DESKS)")).isEqualTo(eval("oneOf($.DESK, $.DESKS)"));
        assertThat(eval("coalesce($.STRATEGY, 'NONE')")).isEqualTo("NONE");
        assertThat(eval("nonblank($.BLANK, $.TT_BOOK)")).isEqualTo("BOOK-RATES-3");
        assertThat(masked("coalesce($.MTM, 1)")).isTrue();
        assertThat(Functions.arity()).containsKeys("if", "ifs", "switch", "iferror", "let", "least", "greatest", "starts_with",
                "ends_with", "days_between", "matches_case", "one_of", "regex_extract", "yearfrac", "map", "reduce");
    }

    @Test
    void theEditorSchemaOffersTheOneNamesAndChecksTheOld() {
        @SuppressWarnings("unchecked")
        java.util.Map<String, java.util.Map<String, Object>> fns = (java.util.Map<String, java.util.Map<String, Object>>)
                com.ash.drishti.rachana.RachanaSchema.build(java.util.List.of("date"), java.util.List.of("trade")).get("x-rachana-functions");
        assertThat(fns.get("starts_with")).containsEntry("min", 2).doesNotContainKey("oldSpellingOf");
        assertThat(fns.get("startsWith")).containsEntry("oldSpellingOf", "starts_with");
        assertThat(fns.get("let")).containsEntry("min", 2).containsEntry("max", 64);
    }
}
