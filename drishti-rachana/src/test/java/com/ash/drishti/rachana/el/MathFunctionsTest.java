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

import static com.ash.drishti.rachana.el.Fx.compileError;
import static com.ash.drishti.rachana.el.Fx.dec;
import static com.ash.drishti.rachana.el.Fx.eval;
import static com.ash.drishti.rachana.el.Fx.evalError;
import static com.ash.drishti.rachana.el.Fx.masked;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The math functions (FUNCTION_CATALOGUE.md section 1): decimal results, empty in empty out, masked in masked out, clear problems. */
class MathFunctionsTest {

    @Test
    void roundUpAndDownWorkInDecimal() {
        assertThat(eval("round_up(2.671, 2)")).isEqualTo(dec("2.68"));
        assertThat(eval("round_up(-2.671, 2)")).isEqualTo(dec("-2.68"));
        assertThat(eval("round_up(3.2)")).isEqualTo(4L);
        assertThat(eval("round_up(31415.92654, -2)")).isEqualTo(31500L);
        assertThat(eval("round_up($.QUANTITY / 100)")).isEqualTo(2420000L);
        assertThat(eval("round_down(2.679, 2)")).isEqualTo(dec("2.67"));
        assertThat(eval("round_down(-2.679, 2)")).isEqualTo(dec("-2.67"));
        assertThat(eval("round_down(1234.5, -2)")).isEqualTo(1200L);
        assertThat(eval("round_up(0.1 + 0.2, 1)")).isEqualTo(dec("0.3"));    // binary noise is not rounded up
        assertThat(eval("round_down(2.675, 2)")).isEqualTo(dec("2.67"));
        assertThat(eval("round_up($.TEXTNUM)")).isEqualTo(13L);             // number-like text reads as a number
        assertThat(eval("fmt(round_down(1234.567, 0), 'amount0')")).isEqualTo("1,234");
    }

    @Test
    void roundingEdgesNullsMasksAndTypes() {
        assertThat(eval("round_up($.NOPE, 2)")).isNull();
        assertThat(eval("round_up(2.5, $.NOPE)")).isNull();
        assertThat(eval("round_up($.QUANTITY * $.NOPE)")).isNull();           // NaN from a missing field is empty
        assertThat(masked("round_up($.MTM, 2)")).isTrue();
        assertThat(masked("round_down(1, $.MTM)")).isTrue();
        evalError("round_up('abc')", "round_up", "'abc' is not a number");
        evalError("round_down($.LEGS, 1)", "round_down", "a list is not a number");
        evalError("round_up(true)", "true is not a number");
        evalError("round_up(1, 200)", "places 200");
        assertThat(eval("round_up(2.5, 1.9)")).isEqualTo(dec("2.5"));       // places are cut to a whole number
        assertThat(eval("round_down(0)")).isEqualTo(0L);
    }

    @Test
    void mroundCeilingAndFloorGoToMultiples() {
        assertThat(eval("mround(10, 3)")).isEqualTo(9L);
        assertThat(eval("mround(-10, -3)")).isEqualTo(-9L);
        assertThat(eval("mround(1.3, 0.2)")).isEqualTo(dec("1.4"));
        assertThat(eval("mround(7.5, 5)")).isEqualTo(10L);                    // a half goes away from zero
        assertThat(eval("mround($.PRICE, 0.25)")).isEqualTo(0L);
        assertThat(eval("mround(5, 0)")).isEqualTo(0L);
        evalError("mround(5, -2)", "mround", "different signs");
        assertThat(eval("mround($.NOPE, 2)")).isNull();
        assertThat(masked("mround($.MTM, 2)")).isTrue();
        assertThat(eval("ceiling(2.5)")).isEqualTo(3L);
        assertThat(eval("ceiling(2.5, 2)")).isEqualTo(4L);
        assertThat(eval("ceiling(-2.5, 2)")).isEqualTo(-2L);                 // towards +infinity, as CEILING.MATH
        assertThat(eval("ceiling(0.234, 0.01)")).isEqualTo(dec("0.24"));
        assertThat(eval("ceiling(size($.LEGS), 2)")).isEqualTo(2L);
        assertThat(eval("ceiling(5, 0)")).isEqualTo(0L);
        assertThat(eval("floor(2.5)")).isEqualTo(2L);
        assertThat(eval("floor(-2.5)")).isEqualTo(-3L);
        assertThat(eval("floor(-2.5, 2)")).isEqualTo(-4L);
        assertThat(eval("floor(1.58, 0.1)")).isEqualTo(dec("1.5"));
        assertThat(eval("floor(7, -2)")).isEqualTo(6L);                       // the multiple's sign does not matter
        evalError("floor('x')", "floor", "'x' is not a number");
        assertThat(eval("ceiling($.NOPE)")).isNull();
    }

    @Test
    void modTakesTheSignOfTheDivisor() {
        assertThat(eval("mod(3, 2)")).isEqualTo(1L);
        assertThat(eval("mod(-3, 2)")).isEqualTo(1L);
        assertThat(eval("mod(3, -2)")).isEqualTo(-1L);
        assertThat(eval("mod(-3, -2)")).isEqualTo(-1L);
        assertThat(eval("mod(5.5, 2)")).isEqualTo(dec("1.5"));
        assertThat(eval("mod(1, 0)")).isNull();                               // a division by zero is empty, as 1 % 0
        assertThat(eval("mod($.NOPE, 2)")).isNull();
        assertThat(masked("mod($.MTM, 7)")).isTrue();
        evalError("mod(1, 'x')", "mod", "'x' is not a number");
    }

    @Test
    void powersRootsAndLogarithms() {
        assertThat(eval("power(2, 10)")).isEqualTo(1024L);
        assertThat(eval("power(1.1, 2)")).isEqualTo(dec("1.21"));             // decimal: not 1.2100000000000002
        assertThat(eval("power(2, -2)")).isEqualTo(dec("0.25"));
        assertThat(eval("power(4, 0.5)")).isEqualTo(2L);
        assertThat(eval("power(10, 0)")).isEqualTo(1L);
        assertThat(eval("power(0, -1)")).isNull();                            // a division by zero
        evalError("power(-8, 1 / 3)", "power", "not a real number");
        evalError("power(10, 400)", "power", "too large");
        assertThat(eval("sqrt(16)")).isEqualTo(4L);
        assertThat(eval("sqrt(0.0144)")).isEqualTo(dec("0.12"));
        assertThat((BigDecimal) eval("sqrt(2)")).isEqualByComparingTo(dec("1.414213562373095"));
        evalError("sqrt(-4)", "sqrt", "-4 is negative");
        assertThat(eval("exp(0)")).isEqualTo(1L);
        assertThat((Double) eval("exp(1)")).isCloseTo(Math.E, org.assertj.core.data.Offset.offset(1e-15));
        assertThat(eval("ln(1)")).isEqualTo(0L);
        assertThat(eval("log10(1000)")).isEqualTo(3L);
        evalError("ln(0)", "ln", "0 is not above zero");
        evalError("log10(-1)", "log10", "-1 is not above zero");
        assertThat(eval("sqrt($.NOPE)")).isNull();
        assertThat(eval("power($.NOPE, 2)")).isNull();
        assertThat(masked("exp($.MTM)")).isTrue();
        assertThat(masked("power(2, $.MTM)")).isTrue();
        evalError("exp('e')", "exp", "'e' is not a number");
    }

    @Test
    void signAndClamp() {
        assertThat(eval("sign(-5)")).isEqualTo(-1L);
        assertThat(eval("sign(0)")).isEqualTo(0L);
        assertThat(eval("sign(0.001)")).isEqualTo(1L);
        assertThat(eval("sign($.QUANTITY)")).isEqualTo(1L);
        assertThat(eval("sign($.NOPE)")).isNull();
        assertThat(masked("sign($.MTM)")).isTrue();
        evalError("sign('up')", "sign", "'up' is not a number");
        assertThat(eval("clamp(5, 0, 1)")).isEqualTo(1L);
        assertThat(eval("clamp(-0.5, 0, 1)")).isEqualTo(0L);
        assertThat(eval("clamp(0.25, 0, 1)")).isEqualTo(dec("0.25"));
        assertThat(eval("clamp($.NOPE, 0, 1)")).isNull();
        assertThat(masked("clamp($.MTM, 0, 1)")).isTrue();
        evalError("clamp(1, 2, 0)", "clamp", "the low bound 2 is above the high bound 0");
    }

    @Test
    void leastAndGreatestCompareNumbersOrDates() {
        assertThat(eval("least(3, 1.5, '7')")).isEqualTo(dec("1.5"));
        assertThat(eval("greatest(3, 1.5, '7')")).isEqualTo(7L);
        assertThat(eval("least($.MATURITY_DATE, $.TRADE_DATE)")).isEqualTo("2025-11-21");
        assertThat(eval("greatest($.MATURITY_DATE, today())")).isEqualTo("2032-06-25");
        assertThat(eval("least($.NOPE, 2)")).isEqualTo(2L);                   // empty arguments are skipped
        assertThat(eval("least($.NOPE, $.STRATEGY)")).isNull();
        assertThat(eval("greatest(5)")).isEqualTo(5L);
        assertThat(masked("least($.MTM, 1)")).isTrue();
        evalError("greatest(3, 'x')", "greatest", "'x' is not a number");
        evalError("least(true, false)", "least", "true is not a number, a date or text");
    }

    @Test
    void minAndMaxKeepTheirResults() {
        assertThat(eval("max(3, '7', 'x')")).isEqualTo(7L);                   // the old spelling skips what is not a number
        assertThat(eval("min($.PRICE, 0.05)")).isEqualTo(0.040829);
        assertThat(eval("max($.NUMS)")).isEqualTo(10L);
        assertThat(eval("min($.NUMS)")).isEqualTo(1.5);
        assertThat(eval("min('a', 'b')")).isNull();
        assertThat(masked("max($.PVS[*].PV)")).isTrue();
    }

    @Test
    void writtenArgumentsAreCountedWhenCompiling() {
        compileError("round_up()", "round_up takes 1-2 argument(s), got 0");
        compileError("clamp(1, 2)", "clamp takes 3 argument(s), got 2");
    }
}
