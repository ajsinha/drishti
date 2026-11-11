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
package com.ash.drishti.bicompute.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.rachana.el.CheckedMode;
import com.ash.drishti.rachana.el.check.Signatures;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Java evaluator and the SQL lowering agree value for value (docs/architecture/ARCHITECTURE.md §28 2.6): every operator, every function Rachana
 * computes in a row (R), every aggregate the engine computes, with and without a measure's own condition, and the shipped models' row
 * expressions and measures written in Rachana, over a table of edges (nulls, empty and mixed-case text, zeros, negatives, dates
 * across a year end, a masked column).
 */
class DifferentialTest {

    static Differential d;

    @BeforeAll
    static void open() throws Exception {
        d = new Differential();
    }

    @AfterAll
    static void close() throws Exception {
        d.close();
    }

    /** Operators and the null rule. */
    static final List<String> OPERATORS = List.of(
            "MTM + 1", "MTM - NOTIONAL", "MTM * 2.5", "MTM / N", "MTM % 7", "N % 4", "-MTM", "MTM / 0", "N / 2",
            "MTM == 0", "STATUS == 'LIVE'", "STATUS != 'LIVE'", "STATUS != null", "STATUS == null", "MTM > 0", "MTM <= NOTIONAL", "N >= 0",
            "MTM > 0 && N > 0", "MTM > 0 || STATUS == 'LIVE'", "!(MTM > 0)", "!LIVE", "LIVE && N > 0", "LIVE == true", "LIVE != false",
            "MTM > 0 ? MTM : 0", "STATUS == null ? 'none' : STATUS", "LIVE ? N : null",
            "STATUS in ['LIVE', 'MATURED']", "STATUS not in ['LIVE', '']", "STATUS in [null, 'Pending']", "STATUS in []",
            "STATUS not in []", "N in [0, 1, -1]", "CCY not in ['USD', null]",
            "TRADE_DATE > '2026-01-01'", "TRADE_DATE == '2026-01-05'", "TRADE_DATE != MATURITY", "MATURITY - TRADE_DATE",
            "trades.MTM / 1000000", "$.NOTIONAL * 2", "SECRET * 2", "SECRET + MTM");

    /** Every R function of the signatures with the shapes its arguments take. */
    static final List<String> FUNCTIONS = List.of(
            "abs(MTM)", "abs(N)", "round(MTM)", "round(MTM, 2)", "round(MTM / 3, n = 3)", "round(MTM, -3)", "round(2.675, 2)", "round(-2.5)",
            "coalesce(STATUS, 'none')", "coalesce(MTM, NOTIONAL, 0)", "upper(STATUS)", "lower(STATUS)", "upper(DESK)", "len(STATUS)", "len(CCY)",
            "contains(STATUS, 'iv')", "contains(DESK, 'comm')", "contains(STATUS, '')", "starts_with(STATUS, 'li')", "starts_with(DESK, '')",
            "ends_with(DESK, 'FX')", "ends_with(STATUS, 'ed')", "date('2026-10-09')", "timestamp('2026-10-09T09:30')",
            "year(TRADE_DATE)", "quarter(TRADE_DATE)", "month(MATURITY)", "week(TRADE_DATE)", "week(MATURITY)", "day(TRADE_DATE)",
            "days_between(TRADE_DATE, MATURITY)", "days_between(MATURITY, TRADE_DATE)", "business_days_between(TRADE_DATE, MATURITY)",
            "business_days_between(MATURITY, TRADE_DATE)", "business_days_between(TRADE_DATE, '2026-02-17', calendar = 'GBLO')",
            "shift_date(asof(), -1)", "shift_date('2026-01-02', -1)", "shift_date(TRADE_DATE, 1, unit = 'month')",
            "shift_date(TRADE_DATE, -2, unit = 'week')", "shift_date(MATURITY, 1, unit = 'quarter')", "shift_date(TRADE_DATE, 3, unit = 'day')",
            "shift_date(TRADE_DATE, -1, unit = 'year')", "asof()", "power(N, 2)", "power(abs(MTM), 0.5)", "power(1.1, 2)", "power(N, -1)", "floor(MTM)",
            "floor(MTM / 1000)", "floor(MTM, 1000)", "floor(N, 0)", "floor(-2.5)", "if(MTM > 0, MTM, 0)", "if(STATUS == 'LIVE', 1)",
            "switch(CCY, 'JPY', 1 / 148.2, 'EUR', 1.174, 'GBP', 1.348, 1)", "switch(STATUS, 'LIVE', 'L', 'MATURED', 'M')",
            "least(MTM, 0)", "least(MTM, NOTIONAL, N)", "greatest(MTM, 0)", "greatest(N, 1, null)");

    /** The shipped models' and datasets' row expressions, as the migration writes them (docs/architecture/ARCHITECTURE.md §28 2.7). */
    static final List<String> SHIPPED_ROWS = List.of(
            "$.MTM / 1000000", "'1D'", "STATUS == 'NSET'", "DESK in ['DESK-RATES', 'DESK-FX']",
            "NOTIONAL * (CCY == 'JPY' ? 1 / 148.2 : CCY == 'EUR' ? 1.174 : CCY == 'GBP' ? 1.348 : CCY == 'USD' ? 1 : null)",
            "abs(NOTIONAL * switch(CCY, 'JPY', 1 / 148.2, 'EUR', 1.174, 'GBP', 1.348, 'USD', 1, null))");

    @ParameterizedTest
    @org.junit.jupiter.params.provider.FieldSource({"OPERATORS", "FUNCTIONS", "SHIPPED_ROWS"})
    void rowExpressionsAgree(String text) throws Exception {
        assertThat(d.rowDifferences(text)).as(text).isEmpty();
    }

    /** Every R function of the catalogue is covered above (the differential is "over every function"). */
    @Test
    void everyRachanaFunctionOfTheCatalogueIsCovered() {
        Set<String> covered = new LinkedHashSet<>();
        for (String t : FUNCTIONS) {
            covered.add(t.substring(0, t.indexOf('(')));
        }
        Set<String> refused = Set.of("fx");                                   // the FX source comes with the table layer (Q15)
        for (Signatures.Signature s : Signatures.get().all()) {
            if ("R".equals(s.runs()) && !refused.contains(s.name())) {
                assertThat(covered).as(s.name()).contains(s.name());
            }
        }
    }

    /** The engine's aggregates, with and without the measure's own condition (layer 4), and the measures of the shipped models. */
    @ParameterizedTest
    @ValueSource(strings = {
        "sum(MTM)", "sum(trades.MTM)", "avg(MTM)", "min(MTM)", "max(NOTIONAL)", "count(MTM)", "count(trades)", "distinct(CCY)", "distinct(N)",
        "percentile(MTM, 0.9)", "percentile(NOTIONAL, 0.5)", "percentile(N, 0.25)",
        "sum(MTM, where = STATUS == 'LIVE')", "sum(MTM, where = STATUS != 'MATURED')", "count(trades, where = MTM < 0)",
        "avg(NOTIONAL, where = CCY in ['USD', 'EUR'])", "max(MTM, where = TRADE_DATE >= '2026-01-01')", "distinct(DESK, where = LIVE)",
        "count(trades, where = STATUS in [])", "sum(SECRET)", "count(SECRET)",
        "ratio(sum(MTM), sum(NOTIONAL)) * 1000000", "sum(MTM) / count(trades)", "round(sum(MTM), 2)", "sum(MTM) - sum(MTM, where = MTM < 0)",
        "ratio(sum(MTM), sum(MTM, where = MTM == 12345))", "abs(min(MTM))", "sum(MTM) > 0 ? sum(MTM) : 0",
        // the shipped models' measures (config/bi/models), in Rachana
        "sum(trades.NOTIONAL)", "count(trades)", "sum(trades.MTM, where = trades.MTM < 0)", "min(trades.MTM)", "max(trades.MTM)",
        "distinct(trades.DESK)", "ratio(sum(trades.MTM), sum(trades.NOTIONAL)) * 1000000", "max(trades.MTM, where = trades.DESK == 'DESK-FX')",
        "avg(trades.NOTIONAL * (trades.CCY == 'JPY' ? 1 / 148.2 : trades.CCY == 'EUR' ? 1.174 : trades.CCY == 'GBP' ? 1.348 : 1))",
        "max(abs(trades.NOTIONAL * (trades.CCY == 'JPY' ? 1 / 148.2 : trades.CCY == 'EUR' ? 1.174 : 1)))",
    })
    void measuresAgree(String text) throws Exception {
        Object[] v = d.measure(text);
        assertThat(Differential.same(v[0], v[1])).as(text + ": Java " + v[0] + ", SQL " + v[1]).isTrue();
    }

    /** A function with no SQL form is refused, never computed a second way (2.8). */
    @Test
    void whatHasNoSqlFormIsRefused() {
        assertThatThrownBy(() -> d.compiler.row(CheckedMode.parse("shift_date(TRADE_DATE, 1)").expr()))
                .hasMessageContaining("business days need a fixed date");
        assertThatThrownBy(() -> d.compiler.row(CheckedMode.parse("fx(CCY, 'USD')").expr())).hasMessageContaining("FX source");
        assertThatThrownBy(() -> d.compiler.row(CheckedMode.parse("sum(MTM)").expr())).hasMessageContaining("belongs in a measure");
        assertThatThrownBy(() -> d.compiler.row(CheckedMode.parse("STATUS in user.desks").expr())).hasMessageContaining("bound when the query runs");
        assertThatThrownBy(() -> new Compiler(d.compiler == null ? null : (p) -> "x", SqlLowering.Options.of(null))
                .row(CheckedMode.parse("asof()").expr())).hasMessageContaining("as-of date");
    }

    /** What needs an answer has no Java form over rows in memory (a live table), in words. */
    @Test
    void answerFunctionsAreRefusedOverRowsInMemory() {
        Lowered l = d.compiler.measure(CheckedMode.parse("ytd(sum(MTM), TRADE_DATE)").expr());
        assertThatThrownBy(() -> d.memory.measure(l, d.rows, java.util.Map.of())).hasMessageContaining("ytd() is computed by the query over an answer");
    }
}
