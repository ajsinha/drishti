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

import static com.ash.drishti.rachana.el.Fx.dec;
import static com.ash.drishti.rachana.el.Fx.eval;
import static com.ash.drishti.rachana.el.Fx.evalError;
import static com.ash.drishti.rachana.el.Fx.masked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * The scalars of 2.9.11.e beside the earlier families (RACHANA_REFERENCE.md sections F1 to 3): math (round, round_sig, log, fact, combin,
 * permut, gcd, lcm, even, odd, pi, the trigonometric functions, base, decimal), text (len, proper, rept, exact, char, code, find, search,
 * text_before, text_after, regex_replace, reverse, clean, value, text), dates (date, year … second, weekday, days360, datedif, shift_date)
 * and information (is_number … type_of, choose), on Excel's documented examples where Excel has one; empty, masked and wrong-typed
 * arguments; and the null rule (a missing value is not equal to a text, so {@code null != 'X'} is true).
 */
class MoreScalarsTest {

    @Test
    void roundingAndWholeNumbersInDecimal() {
        assertThat(eval("round(2.15, 1)")).isEqualTo(dec("2.2"));                // ROUND: half away from zero
        assertThat(eval("round(2.149, 1)")).isEqualTo(dec("2.1"));
        assertThat(eval("round(-1.475, 2)")).isEqualTo(dec("-1.48"));
        assertThat(eval("round(21.5, -1)")).isEqualTo(20L);
        assertThat(eval("round(626.3, -3)")).isEqualTo(1000L);
        assertThat(eval("round(2.675, 2)")).isEqualTo(dec("2.68"));             // decimal, never 2.67 from binary
        assertThat(eval("round(2.5)")).isEqualTo(3L);
        assertThat(eval("round_sig(123456, 2)")).isEqualTo(120000L);
        assertThat(eval("round_sig(0.0012345, 3)")).isEqualTo(dec("0.00123"));
        assertThat(eval("round_sig(-0.0012355, 3)")).isEqualTo(dec("-0.00124"));
        assertThat(eval("round_sig(0, 3)")).isEqualTo(0L);
        assertThat(eval("fact(5)")).isEqualTo(120L);                             // FACT
        assertThat(eval("fact(1.9)")).isEqualTo(1L);
        assertThat(eval("fact(0)")).isEqualTo(1L);
        assertThat(eval("fact(25)")).isEqualTo(dec("15511210043330985984000000"));   // exact past a long
        assertThat(eval("combin(8, 2)")).isEqualTo(28L);                         // COMBIN
        assertThat(eval("permut(100, 3)")).isEqualTo(970200L);                   // PERMUT
        assertThat(eval("permut(3, 2)")).isEqualTo(6L);
        assertThat(eval("gcd(5, 2)")).isEqualTo(1L);                             // GCD
        assertThat(eval("gcd(24, 36)")).isEqualTo(12L);
        assertThat(eval("gcd(7, 1)")).isEqualTo(1L);
        assertThat(eval("gcd(5, 0)")).isEqualTo(5L);
        assertThat(eval("lcm(5, 2)")).isEqualTo(10L);                            // LCM
        assertThat(eval("lcm(24, 36)")).isEqualTo(72L);
        assertThat(eval("even(1.5)")).isEqualTo(2L);                             // EVEN
        assertThat(eval("even(3)")).isEqualTo(4L);
        assertThat(eval("even(2)")).isEqualTo(2L);
        assertThat(eval("even(-1)")).isEqualTo(-2L);
        assertThat(eval("odd(1.5)")).isEqualTo(3L);                              // ODD
        assertThat(eval("odd(3)")).isEqualTo(3L);
        assertThat(eval("odd(2)")).isEqualTo(3L);
        assertThat(eval("odd(-1)")).isEqualTo(-1L);
        assertThat(eval("odd(-2)")).isEqualTo(-3L);
        assertThat(eval("odd(0)")).isEqualTo(1L);
        evalError("fact(-1)", "fact", "not from 0");
        evalError("combin(2, 3)", "combin", "more than n");
        evalError("gcd(-1, 2)", "gcd", "negative");
        assertThat(eval("round($.NOPE, 2)")).isNull();
        assertThat(masked("round($.MTM)")).isTrue();
        evalError("round('x')", "round", "'x' is not a number");
    }

    @Test
    void logarithmsTrigonometryAndRadix() {
        assertThat(eval("log(10)")).isEqualTo(1L);                               // LOG
        assertThat(eval("log(8, 2)")).isEqualTo(3L);
        assertThat((Double) eval("log(86, 2.7182818)")).isCloseTo(4.4543473, within(1e-7));
        evalError("log(0)", "log", "not above zero");
        evalError("log(8, 1)", "log", "base");
        assertThat((Double) eval("pi()")).isEqualTo(Math.PI);
        assertThat((Double) eval("sin(pi() / 6)")).isCloseTo(0.5, within(1e-15));   // SIN
        assertThat((Double) eval("cos(radians(60))")).isCloseTo(0.5, within(1e-15));
        assertThat(eval("degrees(pi())")).isEqualTo(180L);
        assertThat((Double) eval("atan2(1, 1)")).isCloseTo(0.785398163, within(1e-9));   // ATAN2(x, y), Excel's order
        assertThat((Double) eval("atan2(-1, -1)")).isCloseTo(-2.35619449, within(1e-8));
        assertThat((Double) eval("tanh(0.5)")).isCloseTo(0.462117157, within(1e-9));
        evalError("asin(2)", "asin", "not from −1 to 1");
        evalError("atan2(0, 0)", "atan2", "no angle");
        assertThat(eval("base(7, 2)")).isEqualTo("111");                           // BASE
        assertThat(eval("base(100, 16)")).isEqualTo("64");
        assertThat(eval("base(15, 2, 10)")).isEqualTo("0000001111");
        assertThat(eval("decimal('FF', 16)")).isEqualTo(255L);                    // DECIMAL
        assertThat(eval("decimal(111, 2)")).isEqualTo(7L);
        assertThat(eval("decimal('zap', 36)")).isEqualTo(45745L);
        evalError("decimal('12', 2)", "decimal", "radix 2");
        evalError("base(5, 40)", "base", "radix 40");
    }

    @Test
    void textFunctions() {
        assertThat(eval("len('Phoenix, AZ')")).isEqualTo(11L);                     // LEN
        assertThat(eval("len($.NOPE)")).isNull();
        assertThat(eval("proper('this is a TITLE')")).isEqualTo("This Is A Title");  // PROPER
        assertThat(eval("proper('2-way street')")).isEqualTo("2-Way Street");
        assertThat(eval("proper('76BudGet')")).isEqualTo("76Budget");
        assertThat(eval("rept('*-', 3)")).isEqualTo("*-*-*-");                     // REPT
        assertThat(eval("exact('word', 'word')")).isEqualTo(true);                 // EXACT
        assertThat(eval("exact('Word', 'word')")).isEqualTo(false);
        assertThat(eval("exact($.NOPE, 'X')")).isEqualTo(false);
        assertThat(eval("exact($.NOPE, $.STRATEGY)")).isEqualTo(true);
        assertThat(eval("char(65)")).isEqualTo("A");                               // UNICHAR
        assertThat(eval("char(8364)")).isEqualTo("€");
        assertThat(eval("code('A')")).isEqualTo(65L);                              // UNICODE
        assertThat(eval("code('€uro')")).isEqualTo(8364L);
        evalError("char(0)", "char", "not a Unicode character");
        evalError("code('')", "code", "empty");
        assertThat(eval("find('M', 'Miriam McGovern')")).isEqualTo(1L);            // FIND
        assertThat(eval("find('m', 'Miriam McGovern')")).isEqualTo(6L);
        assertThat(eval("find('M', 'Miriam McGovern', 3)")).isEqualTo(8L);
        assertThat(eval("find('z', 'Miriam')")).isEqualTo(0L);                     // not there: 0, SAS's INDEX rule
        assertThat(eval("search('e', 'Statements', 6)")).isEqualTo(7L);            // SEARCH
        assertThat(eval("search('MARGIN', 'Profit Margin')")).isEqualTo(8L);
        evalError("find('a', 'abc', 9)", "find", "start 9");
        assertThat(eval("text_before('Red riding hood\\'s, red hood', 'hood')")).isEqualTo("Red riding ");   // TEXTBEFORE
        assertThat(eval("text_after('Red riding hood\\'s, red hood', 'hood')")).isEqualTo("'s, red hood");   // TEXTAFTER
        assertThat(eval("text_after('a-b-c', '-', -1)")).isEqualTo("c");
        assertThat(eval("text_before('a-b-c', '-', 2)")).isEqualTo("a-b");
        assertThat(eval("text_before('abc', '-')")).isNull();
        assertThat(eval("regex_replace($.INSTRUMENT, '[0-9]', '#')")).isEqualTo("USD ####-## swap");   // REGEXREPLACE
        assertThat(eval("regex_replace('2026-10-16', '([0-9]+)-([0-9]+)-([0-9]+)', '$3/$2/$1')")).isEqualTo("16/10/2026");
        assertThat(eval("regex_replace('a b  c', ' +', ' ')")).isEqualTo("a b c");
        assertThat(eval("regex_replace('abc', 'x*', '-')")).isEqualTo("-a-b-c-");
        assertThat(eval("reverse('swap')")).isEqualTo("paws");
        assertThat(eval("clean(concat('a', char(9), 'b', char(10)))")).isEqualTo("ab");   // CLEAN
        assertThat(masked("len($.MTM)")).isTrue();
    }

    @Test
    void valueAndTextReadAndWriteNumbers() {
        assertThat(eval("value('$1,000')")).isEqualTo(1000L);                       // VALUE
        assertThat(eval("value(' 12.5 ')")).isEqualTo(dec("12.5"));
        assertThat(eval("value('(1,250.50)')")).isEqualTo(dec("-1250.5"));
        assertThat(eval("value('3.5%')")).isEqualTo(dec("0.035"));
        assertThat(eval("value('2.500,27', ',', '.')")).isEqualTo(dec("2500.27"));  // NUMBERVALUE
        assertThat(eval("value(7)")).isEqualTo(7L);
        evalError("value('2026-10-16')", "value", "is not a number");
        evalError("value('12abc')", "value", "is not a number");
        assertThat(eval("text(1234.567, '#,##0.00')")).isEqualTo("1,234.57");       // TEXT
        assertThat(eval("text(0.285, '0.0%')")).isEqualTo("28.5%");
        assertThat(eval("text(2.675, '0.00')")).isEqualTo("2.68");
        assertThat(eval("text(-1234, '#,##0;(#,##0)')")).isEqualTo("(1,234)");
        assertThat(eval("text(0, '#,##0;(#,##0);\"-\"')")).isEqualTo("-");
        assertThat(eval("text(12200000, '0.00E+00')")).isEqualTo("1.22E+07");
        assertThat(eval("text(1234, '\"USD \"#,##0')")).isEqualTo("USD 1,234");
        assertThat(eval("text('2026-10-16', 'dd-mmm-yyyy')")).isEqualTo("16-Oct-2026");
        assertThat(eval("text('2026-10-16', 'dddd, mmmm d, yyyy')")).isEqualTo("Friday, October 16, 2026");
        assertThat(eval("text('2026-10-16T09:05:07Z', 'yyyy-mm-dd hh:mm:ss')")).isEqualTo("2026-10-16 09:05:07");
        assertThat(eval("text('2026-10-16', 'mm/dd/yy')")).isEqualTo("10/16/26");
        assertThat(eval("text($.DESK, '@')")).isEqualTo("DESK-COMM");
        evalError("text('abc', '0.00')", "text", "a number or a date");
        assertThat(eval("text($.NOPE, '0.00')")).isNull();
    }

    @Test
    void dateParts() {
        assertThat(eval("date(2026, 10, 16)")).isEqualTo("2026-10-16");             // DATE
        assertThat(eval("date(2008, 14, 2)")).isEqualTo("2009-02-02");              // months past 12 carry over, as Excel's
        assertThat(eval("date(2008, 1, 35)")).isEqualTo("2008-02-04");
        assertThat(eval("date(2008, -3, 2)")).isEqualTo("2007-09-02");
        assertThat(eval("date('2026-10-16T09:30:00Z')")).isEqualTo("2026-10-16");
        evalError("date(2026, 10)", "date", "date(2026, 10, 16)");
        evalError("date('16/10/2026')", "date", "is not a date");
        assertThat(eval("year($.TRADE_DATE)")).isEqualTo(2025L);
        assertThat(eval("quarter($.TRADE_DATE)")).isEqualTo(4L);
        assertThat(eval("month($.TRADE_DATE)")).isEqualTo(11L);
        assertThat(eval("day($.TRADE_DATE)")).isEqualTo(21L);
        assertThat(eval("week('2027-01-01')")).isEqualTo(53L);                      // ISO weeks (ISOWEEKNUM)
        assertThat(eval("week('2012-03-09')")).isEqualTo(10L);
        assertThat(eval("hour('2026-10-16T09:30:15Z')")).isEqualTo(9L);
        assertThat(eval("minute('2026-10-16T09:30:15Z')")).isEqualTo(30L);
        assertThat(eval("second('2026-10-16T09:30:15Z')")).isEqualTo(15L);
        assertThat(eval("hour('18:45')")).isEqualTo(18L);
        assertThat(eval("hour('2026-10-16')")).isEqualTo(0L);
        evalError("hour('noon')", "hour", "a timestamp or a time");
        assertThat(eval("weekday('2008-02-14')")).isEqualTo(5L);                    // WEEKDAY: Thursday
        assertThat(eval("weekday('2008-02-14', 2)")).isEqualTo(4L);
        assertThat(eval("weekday('2008-02-14', 3)")).isEqualTo(3L);
        assertThat(eval("weekday('2008-02-14', 14)")).isEqualTo(1L);                // a week from Thursday
        evalError("weekday('2008-02-14', 4)", "weekday", "type 4");
        assertThat(eval("days360('2011-01-30', '2011-12-31')")).isEqualTo(330L);   // DAYS360
        assertThat(eval("days360('2011-01-30', '2011-12-31', true)")).isEqualTo(330L);
        assertThat(eval("days360('2011-01-01', '2011-01-31')")).isEqualTo(30L);
        assertThat(eval("days360('2011-01-01', '2011-01-31', true)")).isEqualTo(29L);
        assertThat(eval("datedif('2001-01-01', '2003-01-01', 'Y')")).isEqualTo(2L);   // DATEDIF
        assertThat(eval("datedif('2001-06-01', '2002-08-15', 'D')")).isEqualTo(440L);
        assertThat(eval("datedif('2001-06-01', '2002-08-15', 'YD')")).isEqualTo(75L);
        assertThat(eval("datedif('2001-06-01', '2002-08-15', 'YM')")).isEqualTo(2L);
        assertThat(eval("datedif('2001-06-01', '2002-08-15', 'MD')")).isEqualTo(14L);
        assertThat(eval("datedif('2001-06-01', '2002-08-15', 'M')")).isEqualTo(14L);
        evalError("datedif('2002-01-01', '2001-01-01', 'Y')", "datedif", "before the start");
        assertThat(eval("shift_date('2026-10-16', 1)")).isEqualTo("2026-10-19");     // Friday + 1 business day
        assertThat(eval("shift_date('2026-11-25', 1, 'business_day', 'USNY')")).isEqualTo("2026-11-27");   // over Thanksgiving
        assertThat(eval("shift_date('2026-10-19', -1)")).isEqualTo("2026-10-16");
        assertThat(eval("shift_date('2026-01-31', 1, 'month')")).isEqualTo("2026-02-28");
        assertThat(eval("shift_date('2026-10-16', 2, 'quarter')")).isEqualTo("2027-04-16");
        evalError("shift_date('2026-10-16', 1, 'month', 'USNY')", "shift_date", "a calendar moves only 'business_day'");
        assertThat(eval("year($.NOPE)")).isNull();
        assertThat(masked("month($.MTM)")).isTrue();
    }

    @Test
    void informationAndChoice() {
        assertThat(eval("is_number($.QUANTITY)")).isEqualTo(true);
        assertThat(eval("is_number($.TEXTNUM)")).isEqualTo(false);                 // ISNUMBER: number-like text is text
        assertThat(eval("is_number($.NOPE)")).isEqualTo(false);
        assertThat(eval("is_text($.DESK)")).isEqualTo(true);
        assertThat(eval("is_text($.TRADE_DATE)")).isEqualTo(true);
        assertThat(eval("is_date($.TRADE_DATE)")).isEqualTo(true);
        assertThat(eval("is_date($.DESK)")).isEqualTo(false);
        assertThat(eval("is_boolean(true)")).isEqualTo(true);
        assertThat(eval("is_blank($.STRATEGY)")).isEqualTo(true);                  // ISBLANK: null, missing, blank text
        assertThat(eval("is_blank($.BLANK)")).isEqualTo(true);
        assertThat(eval("is_blank($.NOPE)")).isEqualTo(true);
        assertThat(eval("is_blank($.DESK)")).isEqualTo(false);
        assertThat(eval("is_blank(1 / 0)")).isEqualTo(true);                       // a division by zero is empty in Rachana
        assertThat(eval("is_error(round_up('abc'))")).isEqualTo(true);             // ISERROR
        assertThat(eval("is_error(1 + 1)")).isEqualTo(false);
        assertThat(eval("is_error(date('x'))")).isEqualTo(true);
        assertThat(masked("is_blank($.MTM)")).isTrue();                             // whether a hidden field is filled is hidden
        assertThat(masked("is_error($.MTM)")).isTrue();
        assertThat(eval("type_of($.QUANTITY)")).isEqualTo("number");                // TYPE, in words
        assertThat(eval("type_of($.LEGS)")).isEqualTo("list");
        assertThat(eval("type_of($.LEGS[0])")).isEqualTo("object");
        assertThat(eval("type_of($.NOPE)")).isEqualTo("empty");
        assertThat(eval("choose(2, 'a', 'b', 'c')")).isEqualTo("b");               // CHOOSE
        assertThat(eval("choose(1, 'a', round_up('abc'))")).isEqualTo("a");         // only the chosen value is evaluated
        evalError("choose(4, 'a', 'b')", "choose", "not from 1 to 2");
        assertThat(eval("choose($.NOPE, 'a')")).isNull();
    }

    @Test
    void theNullRuleHolds() {
        // Rachana's null rule (product owner, 2026-11-03): a missing value is not equal to a text, so null != 'X' is true
        assertThat(eval("$.STRATEGY != 'X'")).isEqualTo(true);
        assertThat(eval("$.STRATEGY == 'X'")).isEqualTo(false);
        assertThat(eval("left($.STRATEGY, 2) != 'X'")).isEqualTo(true);           // a function of a missing value is missing
        assertThat(eval("text_before($.DESK, '#') != 'DESK'")).isEqualTo(true);
        assertThat(eval("year($.NOPE) != 2026")).isEqualTo(true);
    }
}
