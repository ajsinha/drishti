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
package com.ash.drishti.tables.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.el.Printer;
import com.ash.drishti.tables.lang.Trees;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Q1, decided as Rachana's rule (docs/architecture/BI_DESIGN.md section 8): Rachana says {@code null != 'X'} is true, DEL (and SQL) that it is
 * false; the migrator rewrites every comparison whose meaning would change, so BI's figures stay the same. Each case states the value DEL
 * gave for a missing operand and checks the rewritten text gives it under Rachana's rule.
 */
class NullRuleTest {

    /** {@code trades.VALUATION_ID} is a key (never missing); everything else may be. */
    static String rule(String rachana) {
        List<String> notes = new ArrayList<>();
        return Printer.print(Rewrites.nullRule(Trees.parse(rachana), Rewrites.missingUnless(Set.of("trades.VALUATION_ID", "$.VALUATION_ID")), notes));
    }

    @Test
    void notEqualGainsAGuardOnTheSideThatMayBeMissing() {
        // DEL: a missing status is not counted (the comparison is false); Rachana alone would count it
        assertThat(rule("sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED')"))
                .isEqualTo("sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED' && trades.TRADE_STATUS != null)");
        assertThat(rule("'MATURED' != trades.TRADE_STATUS")).isEqualTo("'MATURED' != trades.TRADE_STATUS && trades.TRADE_STATUS != null");
        assertThat(rule("a != b")).isEqualTo("a != b && a != null && b != null");
        assertThat(rule("$.DESK != 'X'")).isEqualTo("$.DESK != 'X' && $.DESK != null");
    }

    @Test
    void aKeyOrALiteralIsNeverMissing() {
        assertThat(rule("trades.VALUATION_ID != 'V-1'")).isEqualTo("trades.VALUATION_ID != 'V-1'");
        assertThat(rule("$.VALUATION_ID != 'V-1'")).isEqualTo("$.VALUATION_ID != 'V-1'");
        assertThat(rule("1 != 2")).isEqualTo("1 != 2");
        assertThat(rule("count(trades) != 0")).isEqualTo("count(trades) != 0");
    }

    @Test
    void equalNeedsAGuardOnlyWhenBothSidesMayBeMissing() {
        // null == 'X' is false in both; null == null is true in Rachana, false in DEL
        assertThat(rule("trades.DESK == 'FX'")).isEqualTo("trades.DESK == 'FX'");
        assertThat(rule("trades.DESK == books.DESK")).isEqualTo("trades.DESK == books.DESK && trades.DESK != null");
    }

    @Test
    void notInGainsAGuardAndInDoesNot() {
        assertThat(rule("trades.DESK not in ['A', 'B']")).isEqualTo("trades.DESK not in ['A', 'B'] && trades.DESK != null");
        assertThat(rule("$.DESK in user.desks")).isEqualTo("$.DESK in user.desks");          // strict in: a desk-head with no desks sees nothing
    }

    @Test
    void aNullTestMeansTheSameInBoth() {
        assertThat(rule("x == null")).isEqualTo("x == null");
        assertThat(rule("x != null")).isEqualTo("x != null");
    }

    @Test
    void aNegationIsPushedInwardFirst() {
        // NOT(a = 'X') with a missing is unknown, so false; !(a == 'X') would be true in Rachana
        assertThat(rule("!(trades.DESK == 'FX')")).isEqualTo("trades.DESK != 'FX' && trades.DESK != null");
        assertThat(rule("!(trades.DESK != 'FX')")).isEqualTo("trades.DESK == 'FX'");
        assertThat(rule("!(x < 3)")).isEqualTo("x >= 3");
        assertThat(rule("!(a == 'X' || b == 'Y')")).isEqualTo("a != 'X' && a != null && b != 'Y' && b != null");
        assertThat(rule("!!(a != 'X')")).isEqualTo("a != 'X' && a != null");
        assertThat(rule("!trades.IS_OPEN")).isEqualTo("!trades.IS_OPEN && trades.IS_OPEN != null");
    }

    @Test
    void worksInsideOrConditionalsAndCalls() {
        assertThat(rule("a != 'X' || b == 'Y'")).isEqualTo("a != 'X' && a != null || b == 'Y'");
        assertThat(rule("sum(p.AMOUNT * (p.CCY != 'USD' ? 2 : 1))")).isEqualTo("sum(p.AMOUNT * (p.CCY != 'USD' && p.CCY != null ? 2 : 1))");
        assertThat(rule("calc(measure('MTM'), where = desks.NAME != 'FX')")).isEqualTo("calc(measure('MTM'), where = desks.NAME != 'FX' && desks.NAME != null)");
    }

    @Test
    void isIdempotent() {
        for (String s : List.of("sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED')", "a != b", "trades.DESK == books.DESK",
                "!(a == 'X' || b == 'Y')", "a != 'X' || b == 'Y'", "x not in ['A'] && y != 'B'", "!trades.IS_OPEN")) {
            String once = rule(s);
            assertThat(rule(once)).as(s).isEqualTo(once);
        }
    }

    @Test
    void theNotesSayWhatWasKept() {
        List<String> notes = new ArrayList<>();
        Rewrites.nullRule(Trees.parse("a != 'X'"), Rewrites.missingUnless(Set.of()), notes);
        assertThat(notes).singleElement().asString().contains("a != 'X' && a != null").contains("Q1");
    }
}
