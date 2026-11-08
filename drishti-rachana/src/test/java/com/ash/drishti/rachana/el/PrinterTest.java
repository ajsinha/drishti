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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The one spelling, printed (docs/architecture/BI_DESIGN.md Q4): printing a parsed expression gives its text, parentheses only where needed. */
class PrinterTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED')",
        "ratio(sum(trades.MTM), sum(trades.NOTIONAL)) * 1000000",
        "payments.AMOUNT * (payments.CURRENCY == 'JPY' ? 1 / 148.2 : payments.CURRENCY == 'EUR' ? 1.174 : null)",
        "$.DESK in ['DESK-COMM', 'DESK-RATES'] && !($.QUANTITY > 0)",
        "$.DESK not in []",
        "(a + b) * c - d / (e - f)",
        "a - (b - c)",
        "a || b && c",
        "(a || b) && c",
        "-$.MTM % 7",
        "$.LEGS[?^.DESK == 'X'][*].LEG_ID",
        "^^.TRADE_ID",
        "calc(measure('MTM'), ignore = ALL, via = 'r_5')",
        "rank(sum(trades.PNL), desc = false, by = [trades.DESK])",
        "let(q = $.QUANTITY, q * 2)",
        "'it\\'s'",
        "size($.LEGS) > 0 ? 'legs' : 'none'",
    })
    void printsWhatItParses(String text) {
        Expr e = new ElCompiler().compile(text);
        assertThat(Printer.print(e)).isEqualTo(text);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "((a + b))         | a + b",
        "a + (b * c)       | a + b * c",
        "(a == b) == c     | a == b == c",
        "!(a)              | !a",
        "f( 1 ,2 )         | f(1, 2)",
    })
    void dropsParenthesesTheGrammarDoesNotNeed(String in, String out) {
        String text = in.strip().replace("f(", "coalesce(");
        assertThat(Printer.print(new ElCompiler().compile(text))).isEqualTo(out.strip().replace("f(", "coalesce("));
    }
}
