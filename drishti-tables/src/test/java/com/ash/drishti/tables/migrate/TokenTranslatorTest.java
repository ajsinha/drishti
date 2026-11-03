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

import com.ash.drishti.tables.check.ExpressionChecker.Scope;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The stand-in DEL translator: the mechanical part, and a refusal by name for what part A's translator does. */
class TokenTranslatorTest {

    final TokenTranslator t = new TokenTranslator();

    String ok(String del, Scope scope) {
        DelTranslator.Result r = t.translate(del, scope);
        assertThat(r.refusal()).as(del).isNull();
        return r.rachana();
    }

    @Test
    void mapsTheWordsQuotesAndNumbers() {
        assertThat(ok("True", Scope.RULE)).isEqualTo("true");
        assertThat(ok("None", Scope.ROW)).isEqualTo("null");
        assertThat(ok("a > 1 and b < 2 or c == \"X\"", Scope.ROW)).isEqualTo("(a > 1 && b < 2) || c == 'X'");
        assertThat(ok("x is None", Scope.ROW)).isEqualTo("x == null");
        assertThat(ok("x is not None", Scope.ROW)).isEqualTo("x != null");
        assertThat(ok("DESK not in ['A', 'B']", Scope.RULE)).isEqualTo("DESK not in ['A', 'B']");
        assertThat(ok("NOTIONAL > 1_000_000", Scope.ROW)).isEqualTo("NOTIONAL > 1000000");
        assertThat(ok("sum(trades.MTM, where=trades.MTM < 0)", Scope.AGGREGATE)).isEqualTo("sum(trades.MTM, where = trades.MTM < 0)");
    }

    @Test
    void expandsChainsAndRenamesFunctions() {
        assertThat(ok("0 < x <= 10", Scope.ROW)).isEqualTo("0 < x && x <= 10");
        assertThat(ok("startswith(NAME, 'A')", Scope.ROW)).isEqualTo("starts_with(NAME, 'A')");
        assertThat(ok("last(sum(a.BALANCE), a.business_date)", Scope.AGGREGATE)).isEqualTo("latest(sum(a.BALANCE), a.business_date)");
        assertThat(ok("first(sum(a.BALANCE), a.business_date)", Scope.AGGREGATE)).isEqualTo("earliest(sum(a.BALANCE), a.business_date)");
        assertThat(ok("cumulative(sum(t.MTM), t.TRADE_DATE)", Scope.AGGREGATE)).isEqualTo("running(sum(t.MTM), t.TRADE_DATE)");
    }

    @Test
    void refusesWhatNeedsDelsOwnParserByName() {
        assertThat(t.translate("1 if x else 2", Scope.ROW).refusal()).contains("conditional");
        assertThat(t.translate("not x == 1", Scope.ROW).refusal()).contains("'not'");
        assertThat(t.translate("x ** 2", Scope.ROW).refusal()).contains("power");
        assertThat(t.translate("x // 2", Scope.ROW).refusal()).contains("floor");
        assertThat(t.translate("x  # note", Scope.ROW).refusal()).contains("comment");
        assertThat(t.translate("x == '#1'", Scope.ROW).refused()).isFalse();                 // a # inside a text is text
    }

    @Test
    void theWholeStepSubstitutesColumnsAndKeepsDelsNullMeaning() {
        Translate step = new Translate(t);
        Translate.Out row = step.table("MTM / 1000000", Scope.ROW, Map.of("MTM", "$.MTM"), Set.of());
        assertThat(row.text()).isEqualTo("$.MTM / 1000000");
        Translate.Out rule = step.table("DESK in user.desks", Scope.RULE, Map.of("DESK", "$.DESK"), Set.of());
        assertThat(rule.text()).isEqualTo("$.DESK in user.desks");
        Translate.Out filter = step.table("HIERARCHY != 'NSET'", Scope.ROW, Map.of("HIERARCHY", "$.HIERARCHY"), Set.of());
        assertThat(filter.text()).isEqualTo("$.HIERARCHY != 'NSET' && $.HIERARCHY != null");
        Translate.Out measure = step.model("sum(trades.MTM, where=trades.TRADE_DATE.year == 2026)", Scope.AGGREGATE, Set.of("trades.MTM", "trades.TRADE_DATE"),
                Set.of());
        assertThat(measure.text()).isEqualTo("sum(trades.MTM, where = year(trades.TRADE_DATE) == 2026)");
    }
}
