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
import static com.ash.drishti.rachana.el.Fx.eval;
import static com.ash.drishti.rachana.el.Fx.evalError;
import static com.ash.drishti.rachana.el.Fx.list;
import static com.ash.drishti.rachana.el.Fx.masked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import org.junit.jupiter.api.Test;

/** The text functions (RACHANA_REFERENCE.md section F2) and the one names of the text tests. */
class TextFunctionsTest {

    @Test
    void concatJoinsAndSkipsEmptyParts() {
        assertThat(eval("concat($.DESK, ' / ', $.TT_BOOK)")).isEqualTo("DESK-COMM / BOOK-RATES-3");
        assertThat(eval("concat('a', $.NOPE, 1, 2.5)")).isEqualTo("a12.5");
        assertThat(eval("concat($.STRATEGY)")).isEqualTo("");
        assertThat(masked("concat($.MTM, 'x')")).isTrue();
        evalError("concat($.LEGS)", "concat", "a list is not text");
    }

    @Test
    void leftRightAndMid() {
        assertThat(eval("left($.TRADE_ID, 3)")).isEqualTo("MX-");
        assertThat(eval("right($.TRADE_ID, 8)")).isEqualTo("20000001");
        assertThat(eval("left('ab', 10)")).isEqualTo("ab");
        assertThat(eval("left('abc', 0)")).isEqualTo("");
        assertThat(eval("left(20261016, 4)")).isEqualTo("2026");              // a number reads as the text it prints
        assertThat(eval("left($.NOPE, 2)")).isNull();
        assertThat(eval("right('abc', $.NOPE)")).isNull();
        assertThat(masked("left($.MTM, 2)")).isTrue();
        evalError("left('abc', -1)", "left", "the count -1 is negative");
        evalError("left('abc', 'x')", "left", "'x' is not a number");
        evalError("right($.LEGS, 1)", "right", "a list is not text");
        assertThat(eval("mid('ABCDEF', 2, 3)")).isEqualTo("BCD");
        assertThat(eval("mid('ABC', 2, 10)")).isEqualTo("BC");
        assertThat(eval("mid('ABC', 5, 2)")).isEqualTo("");
        assertThat(eval("mid($.NOPE, 1, 2)")).isNull();
        evalError("mid('ABC', 0, 1)", "mid", "before the first character");
        evalError("mid('ABC', 1, -1)", "mid", "negative");
    }

    @Test
    void replaceSplitAndTrim() {
        assertThat(eval("replace($.INSTRUMENT, ' ', '-')")).isEqualTo("USD-2026-10-swap");
        assertThat(eval("replace('aaa', 'a', '')")).isEqualTo("");
        assertThat(eval("replace('abc', '', 'x')")).isEqualTo("abc");
        assertThat(eval("replace('Abc', 'a', 'x')")).isEqualTo("Abc");       // case counts
        assertThat(eval("replace($.NOPE, 'a', 'b')")).isNull();
        assertThat(masked("replace($.MTM, 'a', 'b')")).isTrue();
        assertThat(list("split($.TAGS, ',')")).containsExactly("ir", "swap", "", "aud");
        assertThat(list("split('a.b', '.')")).containsExactly("a", "b");     // the separator as written, not a pattern
        assertThat(list("split('', ',')")).containsExactly("");
        assertThat(eval("first(split('x|y', '|'))")).isEqualTo("x");
        assertThat(eval("split($.NOPE, ',')")).isNull();
        evalError("split('a,b', '')", "split", "the separator is empty");
        assertThat(eval("trim('  a b  ')")).isEqualTo("a b");
        assertThat(eval("trim($.NOPE)")).isNull();
        assertThat(masked("trim($.MTM)")).isTrue();
        evalError("trim($.LEGS)", "trim", "a list is not text");
    }

    @Test
    void padWidensOnTheLeftByDefault() {
        assertThat(eval("pad($.LEG_ID, 3, '0')")).isEqualTo("007");
        assertThat(eval("pad('ab', 5)")).isEqualTo("   ab");
        assertThat(eval("pad('ab', 4, '*', 'right')")).isEqualTo("ab**");
        assertThat(eval("pad('abcdef', 3)")).isEqualTo("abcdef");
        assertThat(eval("pad(7, 3, '0')")).isEqualTo("007");
        assertThat(eval("pad($.NOPE, 3)")).isNull();
        assertThat(masked("pad($.MTM, 3)")).isTrue();
        compileError("pad('a', 3, '00')", "pad: the fill '00' is not one character");
        compileError("pad('a', 3, ' ', 'middle')", "the side 'middle' is neither 'left' nor 'right'");
        evalError("pad('a', $.QUANTITY)", "pad", "the width 242000000 is outside 0..10000");
    }

    @Test
    void regexExtractTakesTheMatchOrAGroup() {
        assertThat(eval("regex_extract($.INSTRUMENT, '20[0-9]{2}-[0-9]{2}')")).isEqualTo("2026-10");
        assertThat(eval("regex_extract('EUR-GB', '([A-Z]{3})-([A-Z]{2})', 2)")).isEqualTo("GB");
        assertThat(eval("regex_extract('in Usd', 'usd')")).isNull();           // case counts
        assertThat(eval("regex_extract('in Usd', '(?i)usd')")).isEqualTo("Usd");
        assertThat(eval("regex_extract('abc', '[0-9]+')")).isNull();
        assertThat(eval("regex_extract($.NOPE, 'a')")).isNull();
        assertThat(masked("regex_extract($.MTM, 'a')")).isTrue();
        compileError("regex_extract('a', '(a)', 3)", "the pattern '(a)' has 1 group(s); group 3 was asked for");
        compileError("regex_extract('ab', '(?<=a)b')", "is refused");
        assertThatThrownBy(() -> eval("regex_extract('ab', $.TRADE_ID + '(')")).isInstanceOfSatisfying(DrishtiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REGEX_REFUSED));
    }

    @Test
    void theTextTestsHaveOneNameAndTheOldSpellingsStillRead() {
        assertThat(eval("starts_with($.PRODUCT_TYPE, 'com')")).isEqualTo(true);
        assertThat(eval("startsWith($.PRODUCT_TYPE, 'com')")).isEqualTo(true);
        assertThat(eval("ends_with($.TRADE_ID, '001')")).isEqualTo(true);
        assertThat(eval("ends_with($.TRADE_ID, 'MX')")).isEqualTo(false);
        assertThat(eval("ends_with($.NOPE, 'x')")).isEqualTo(false);           // a test on an empty value is false
        assertThat(eval("starts_with($.NOPE, 'x')")).isEqualTo(false);
        assertThat(masked("ends_with($.MTM, 'x')")).isTrue();
        assertThat(eval("matches($.TRADE_ID, '^mx-')")).isEqualTo(true);
        assertThat(eval("matches_case($.TRADE_ID, '^mx-')")).isEqualTo(false);
        assertThat(eval("matchesCase($.TRADE_ID, '^MX-')")).isEqualTo(true);
        assertThat(eval("one_of($.DESK, $.DESKS)")).isEqualTo(true);
        assertThat(eval("oneOf($.DESK, $.LEGS[*].CURRENCY)")).isEqualTo(false);
        assertThat(Functions.oldSpellings()).containsEntry("startsWith", "starts_with").containsEntry("matchesCase", "matches_case")
                .containsEntry("oneOf", "one_of").containsEntry("days", "days_between").containsEntry("today", "asof").hasSize(5);
    }
}
