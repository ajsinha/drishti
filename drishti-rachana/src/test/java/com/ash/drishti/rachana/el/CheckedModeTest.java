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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.el.check.Checker;
import com.ash.drishti.rachana.el.check.ElType;
import com.ash.drishti.rachana.el.check.Fields;
import com.ash.drishti.rachana.el.check.Problem;
import com.ash.drishti.rachana.el.check.Scope;
import com.ash.drishti.rachana.el.check.Signatures;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checked mode (BI_FOUNDATION_2911E.md 2.1, 2.2 and 2.8): the four scopes and their refusals, types with no implicit casts, names that
 * exist, masked fields in filters, what is not yet built, chained comparisons, paths in measures, display functions in tables and
 * models, nesting of the filter-context functions, each with DEL's code; and every example of the signature catalogue checks.
 */
class CheckedModeTest {

    /** A model of two aliases: trades (MTM decimal, DESK string masked, TRADE_DATE date, STATUS string, N integer) and desks. */
    static final Fields MODEL = new Fields() {
        final Map<String, Field> cols = Map.of("trades.MTM", new Field("trades.MTM", ElType.DECIMAL, false), "trades.DESK",
                new Field("trades.DESK", ElType.STRING, true), "trades.TRADE_DATE", new Field("trades.TRADE_DATE", ElType.DATE, false),
                "trades.STATUS", new Field("trades.STATUS", ElType.STRING, false), "trades.N", new Field("trades.N", ElType.INTEGER, false),
                "desks.NAME", new Field("desks.NAME", ElType.STRING, false), "$.NOTIONAL", new Field("$.NOTIONAL", ElType.DECIMAL, false));

        @Override
        public Optional<Field> field(List<String> parts) {
            return Optional.ofNullable(cols.get(String.join(".", parts)));
        }

        @Override
        public boolean alias(String name) {
            return "trades".equals(name) || "desks".equals(name);
        }

        @Override
        public Optional<String> measure(String nameOrId) {
            return "MTM".equals(nameOrId) || "m_mtm".equals(nameOrId) ? Optional.of("MTM") : Optional.empty();
        }

        @Override
        public List<String> names() {
            return List.of("trades.MTM", "trades.DESK", "trades.TRADE_DATE", "trades.STATUS", "trades.N", "MTM");
        }

        @Override
        public Optional<ElType> param(String name) {
            return "shock".equals(name) ? Optional.of(ElType.DECIMAL) : Optional.empty();
        }
    };

    static Checker.Checked check(String text, Scope scope) {
        return CheckedMode.check(text, scope, MODEL);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "trades.MTM * 2                          | AGGREGATE | DRS-9127 | wrap it: sum(trades.MTM)",
        "sum(trades.MTM)                         | ROW       | DRS-9127 | an aggregate belongs in a measure",
        "sum(trades.MTM, trades.STATUS == 'L')   | AGGREGATE | DRS-9127 | name it: where = ...",
        "sum(trades.MTM, where = trades.MTM)     | AGGREGATE | DRS-9127 | is a boolean",
        "sum(trades.MTX)                         | AGGREGATE | DRS-9152 | did you mean trades.MTM?",
        "measure('MTN')                          | AGGREGATE | DRS-9152 | no measure 'MTN'",
        "trades.MTM && trades.N                  | ROW       | DRS-9127 | takes conditions",
        "trades.STATUS + 1                       | ROW       | DRS-9127 | text cannot be used",
        "trades.TRADE_DATE > '9 Oct'             | ROW       | DRS-9127 | cannot compare a date",
        "trades.DESK == 'RATES'                  | ROW       | DRS-9161 | masked",
        "'RATES' != trades.DESK                  | ROW       | DRS-9161 | masked",
        "trades.DESK in ['RATES']                | ROW       | DRS-9161 | masked",
        "calc(calc(calc(calc(sum(trades.MTM), ignore = ALL), ignore = ALL), ignore = ALL), ignore = ALL) | AGGREGATE | DRS-9127 | nest at most 3 deep",
        "sum(trades.MTM, where = ALL)            | AGGREGATE | DRS-9127 | ALL is only the value",
        "trades.MTM * param.cap                  | ROW       | DRS-9152 | no parameter 'cap'",
        "trades.STATUS                           | RULE      | DRS-9127 | a row rule is a condition",
        "trades.STATUS == user.name              | ROW       | DRS-9127 | is for row rules",
        "trades.STATUS in param.shock            | RULE      | DRS-9127 | security never depends on a what-if value",
        "sum(sum(trades.MTM))                    | AGGREGATE | DRS-9127 | an aggregate inside an aggregate",
        "percentile(trades.MTM, 2)               | AGGREGATE | DRS-9127 | between 0 and 1",
        "startsWith(trades.STATUS, 'L')          | ROW       | DRS-9127 | write starts_with",
        "count(trades)                           | ROW       | DRS-9127 | an aggregate belongs in a measure",
        "date('09/10/2026') < trades.TRADE_DATE  | ROW       | DRS-9127 | not an ISO date",
        "0 < trades.N <= 10                      | ROW       | DRS-9127 | write 0 < trades.N && trades.N <= 10",
        "sum($.NOTIONAL)                         | AGGREGATE | DRS-9127 | a path of the source",
        "ctl.desk == trades.STATUS               | ROW       | DRS-9127 | a Sutra's control",
        "concat(trades.STATUS, 'x')              | ROW       | DRS-9129 | not yet in a table or a model",
        "let(x = trades.N, x * 2)                | ROW       | DRS-9129 | not yet in a table or a model",
        "trades.STATUS in trades.N               | ROW       | DRS-9127 | takes a list",
        "trades.STATUS in [1, 2]                 | ROW       | DRS-9127 | the list has a integer",
        "round(trades.MTM, trades.N)             | ROW       | DRS-9127 | is a whole number written in the expression",
        "rank(sum(trades.MTM), up = true)        | AGGREGATE | DRS-9127 | has no argument named up",
        "sum(trades.LEGS[*].NOTIONAL)            | AGGREGATE | DRS-9127 | a list of the source",
        "trades.N ? 1 : 2                        | ROW       | DRS-9127 | must be true or false",
        "trades                                  | ROW       | DRS-9127 | is a table, not a column",
    })
    void refusesWithDelsCodes(String text, Scope scope, String code, String says) {
        Checker.Checked c = check(text.strip(), scope);
        assertThat(c.ok()).as(text).isFalse();
        assertThat(c.problems()).as(text).anySatisfy(p -> {
            assertThat(p.code()).isEqualTo(code.strip());
            assertThat(p.message() + " | fix: " + p.fix()).contains(says.strip());
        });
    }

    @Test
    void typesWhatIsRight() {
        assertThat(check("sum(trades.MTM, where = trades.STATUS != 'MATURED')", Scope.AGGREGATE).type()).isEqualTo(ElType.DECIMAL);
        assertThat(check("count(trades)", Scope.AGGREGATE).type()).isEqualTo(ElType.INTEGER);
        assertThat(check("measure('m_mtm') / count(trades)", Scope.AGGREGATE).measures()).containsExactly("MTM");
        assertThat(check("trades.TRADE_DATE - trades.TRADE_DATE", Scope.ROW).type()).isEqualTo(ElType.INTEGER);
        assertThat(check("floor(trades.N / 2)", Scope.ROW).problems().stream().filter(Problem::error)).as("floor(trades.N / 2)").isEmpty();
        assertThat(check("power(trades.N, 2)", Scope.ROW).type()).isEqualTo(ElType.DECIMAL);
        assertThat(check("trades.TRADE_DATE >= '2026-10-01'", Scope.ROW).problems().stream().filter(Problem::error)).as("trades.TRADE_DATE >= '2026-10-01'").isEmpty();
        assertThat(check("trades.STATUS in user.desks", Scope.RULE).problems().stream().filter(Problem::error)).as("trades.STATUS in user.desks").isEmpty();
        assertThat(check("trades.STATUS not in ['A', 'B', null]", Scope.ROW).problems().stream().filter(Problem::error)).as("trades.STATUS not in ['A', 'B', null]").isEmpty();
        assertThat(check("trades.STATUS != null && trades.N > 0", Scope.ROW).type()).isEqualTo(ElType.BOOLEAN);
        assertThat(check("trades.N > 0 ? trades.MTM : 0", Scope.ROW).type()).isEqualTo(ElType.DECIMAL);
        assertThat(check("switch(trades.STATUS, 'A', 1, 'B', 2, 0)", Scope.ROW).type()).isEqualTo(ElType.INTEGER);
        assertThat(check("rank(sum(trades.MTM), by = [trades.STATUS], desc = false)", Scope.AGGREGATE).problems().stream().filter(Problem::error)).as("rank(sum(trades.MTM), by = [trades.STATUS], desc = false)").isEmpty();
        assertThat(check("ytd(sum(trades.MTM), trades.TRADE_DATE)", Scope.AGGREGATE).fields()).contains("trades.TRADE_DATE", "trades.MTM");
        assertThat(check("calc(measure('MTM'), ignore = [desks.NAME], where = trades.MTM > 0)", Scope.AGGREGATE).problems().stream().filter(Problem::error)).as("calc(measure('MTM'), ignore = [desks.NAME], where = trades.MTM > 0)").isEmpty();
        assertThat(check("share_of(measure('MTM'), over = ALL)", Scope.AGGREGATE).type()).isEqualTo(ElType.DECIMAL);
        assertThat(check("parent_of(measure('MTM'), hierarchy = 'Desk and book')", Scope.AGGREGATE).problems().stream().filter(Problem::error)).as("parent_of(measure('MTM'), hierarchy = 'Desk and book')").isEmpty();
        assertThat(check("measure('MTM') - as_of(measure('MTM'), shift_date(asof(), -1))", Scope.AGGREGATE).problems().stream().filter(Problem::error)).as("measure('MTM') - as_of(measure('MTM'), shift_date(asof(), -1))").isEmpty();
        assertThat(check("avg_by(sum(trades.MTM), by = [trades.STATUS])", Scope.AGGREGATE).problems().stream().filter(Problem::error)).as("avg_by(sum(trades.MTM), by = [trades.STATUS])").isEmpty();
        assertThat(check("latest(sum(trades.MTM), trades.TRADE_DATE)", Scope.AGGREGATE).problems().stream().filter(Problem::error)).as("latest(sum(trades.MTM), trades.TRADE_DATE)").isEmpty();
        assertThat(check("measure('VAR', 'DESK:DESK-COMM')", Scope.AGGREGATE).problems().stream().filter(Problem::error)).as("measure('VAR', 'DESK:DESK-COMM')").isEmpty();
        assertThat(check("$.NOTIONAL / 1000000", Scope.ROW).type()).isEqualTo(ElType.DECIMAL);
        assertThat(check("trades.MTM * param.shock", Scope.ROW).problems().stream().filter(Problem::error)).as("trades.MTM * param.shock").isEmpty();
    }

    @Test
    void avgByCannotGroupOnAMaskedField() {
        assertThat(check("avg_by(sum(trades.MTM), by = [trades.DESK])", Scope.AGGREGATE).problems())
                .anyMatch(p -> Problem.MASKED.equals(p.code()));
    }

    @Test
    void problemsPointAtWhereTheyWereWritten() {
        String text = "sum(trades.MTM) + trades.N";
        Problem p = check(text, Scope.AGGREGATE).firstError().orElseThrow();
        assertThat(text.substring(p.start(), p.end())).isEqualTo("trades.N");
        Checker.Checked bad = CheckedMode.check("sum(trades.MTM", Scope.AGGREGATE, MODEL);
        assertThat(bad.firstError().orElseThrow().code()).isEqualTo(Problem.LANGUAGE);
    }

    @Test
    void requireRefusesWithTheCode() {
        assertThatThrownBy(() -> CheckedMode.require("trades.DESK == 'X'", Scope.ROW, MODEL)).isInstanceOfSatisfying(DrishtiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MQ_MASKED));
        assertThatThrownBy(() -> CheckedMode.require("concat('a', 'b')", Scope.ROW, MODEL)).isInstanceOfSatisfying(DrishtiException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LATER_WAVE));
        assertThat(CheckedMode.require("trades.N + 1", Scope.ROW, MODEL)).isInstanceOf(Expr.Binary.class);
    }

    /** Without a model (Fields.NONE) only the language and the catalogue are checked: every example of every signature reads. */
    @Test
    void everyCatalogueExampleChecks() {
        Signatures cat = Signatures.get();
        assertThat(cat.all()).hasSize(65);
        for (Signatures.Signature f : cat.all()) {
            assertThat(f.examples()).as(f.name()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(f.description()).as(f.name()).isNotBlank();
            assertThat(f.runs()).as(f.name()).isIn("R", "E");
            assertThat(Functions.arity()).as(f.name()).containsKey(f.name());
            for (Signatures.Example e : f.examples()) {
                Checker.Checked c = CheckedMode.check(e.expr(), null, Fields.NONE);
                assertThat(c.problems().stream().filter(Problem::error)).as(e.expr()).isEmpty();
            }
        }
    }
}
