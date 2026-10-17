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
package com.ash.drishti.server.bi.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.server.bi.dataset.ExprParser;
import com.ash.drishti.server.bi.dataset.Node;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * DEL v2 (docs/architecture/BI_DESIGN.md section 3.3): the accepted Python subset, the refusals with their fixes, the converter's table of section 3.3.7
 * (old syntax to DEL v2, same compiler tree), the scopes and types of the checker, and the catalogue's examples.
 */
class DelLanguageTest {

    @org.junit.jupiter.api.BeforeEach
    void readTheOldSyntax() {
        Expressions.configure(Expressions.Legacy.WARN, 10_000);                 // these tests read the old syntax on purpose
    }

    @AfterEach
    void reset() {
        Expressions.configure(Expressions.Legacy.REFUSE, 10_000);               // the default since the end of wave A
    }

    static Node v2(String text) {
        return DelLowering.lower(DelParser.parse(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "sum(trades.mtm)",
        "sum(trades.mtm, where=trades.status == 'Live')",
        "measure('P&L 1d') / measure('Exposure (EE peak)')",
        "trades.mtm if trades.mtm > 0 else 0",
        "0 < trades.notional <= 1_000_000",
        "trades.currency in ['USD', 'EUR']",
        "trades.currency not in ['USD',]",
        "trades.book is None",
        "trades.book is not None and not trades.status == 'Dead'",
        "trades.desk in user.desks",
        "-trades.mtm ** 2",
        "trades.notional // 1000 % 7",
        "1e6 * 1.5",
        "rank(sum(trades.mtm), desc=False, by=[trades.desk])",
        "lag(sum(trades.mtm), 1, trades.tradeDate, by=[trades.desk],)",
        "count(trades)  # how many trades",
        "date('2026-10-09') <= trades.tradeDate",
        "\"it's\" == trades.comment",
    })
    void acceptsThePythonSubset(String text) {
        assertThat(DelParser.parse(text)).isNotNull();
        assertThat(Expressions.read(text).legacy()).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "x := 1                        | assignment",
        "lambda x: x                   | lambda",
        "[x for x in trades]           | comprehensions",
        "sum(x for x in trades.mtm)    | comprehensions",
        "trades.mtm[0]                 | subscripts",
        "trades.mtm.sum()              | method calls",
        "sum(*trades.mtm)              | star arguments",
        "f'{trades.mtm}'               | prefixed strings",
        "'''x'''                       | triple-quoted",
        "{'a': 1}                      | dict and set",
        "(1, 2)                        | tuples",
        "import os                     | import",
        "await trades                  | await",
        "trades.mtm = 1                | '=' is assignment",
        "trades.a && trades.b          | old syntax",
        "!trades.a                     | old syntax",
        "trades.a ? 1 : 2              | old syntax",
        "true                          | old syntax",
        "frobnicate(trades.a) ?? 3     | '??' is not DEL",
        "trades.a ? 3                  | unexpected '?'",
        "sum(trades.a                  | but the expression ends there",
    })
    void refusesWhatIsNotDel(String text, String says) {
        assertThatThrownBy(() -> DelParser.parse(text.strip())).isInstanceOf(DelException.class).hasMessageContaining(says.strip())
                .satisfies(e -> assertThat(((DelException) e).code()).isEqualTo("DRS-9127"));
    }

    @Test
    void boundsLengthAndDepth() {
        assertThatThrownBy(() -> DelParser.parse("1 + ".repeat(600) + "1")).hasMessageContaining("longer than 2000");
        assertThat(DelParser.parse(("1 + 1\n").repeat(1) + "+ 2")).isNotNull();
        assertThatThrownBy(() -> DelParser.parse("(".repeat(70) + "1" + ")".repeat(70))).hasMessageContaining("deeper than 64");
    }

    /** Section 3.3.7: every old form, converted by the printer, reads back as the same compiler tree (so the same SQL). */
    @ParameterizedTest
    @CsvSource(delimiterString = " => ", value = {
        "sum(trades.mtm, trades.status == 'Live') => sum(trades.mtm, where=trades.status == 'Live')",
        "[P&L 1d] / [Exposure (EE peak)] => measure('P&L 1d') / measure('Exposure (EE peak)')",
        "trades.mtm > 0 ? trades.mtm : 0 => trades.mtm if trades.mtm > 0 else 0",
        "if(trades.mtm > 0, trades.mtm, 0) => trades.mtm if trades.mtm > 0 else 0",
        "trades.a > 1 && (trades.b < 2 || !trades.c) => trades.a > 1 and (trades.b < 2 or not trades.c)",
        "trades.desk = 'RATES' => trades.desk == 'RATES'",
        "trades.currency in ('USD', 'EUR') => trades.currency in ['USD', 'EUR']",
        "trades.book == null => trades.book is None",
        "trades.book != null && trades.live == true => trades.book is not None and trades.live == True",
        "trades.desk in user.desks => trades.desk in user.desks",
        "startsWith(trades.book, 'R') && length(trades.id) > 3 => startswith(trades.book, 'R') and len(trades.id) > 3",
        "rank(sum(trades.mtm), 'asc') => rank(sum(trades.mtm), desc=False)",
        "lag(sum(trades.mtm), 1, trades.tradeDate, trades.desk) => lag(sum(trades.mtm), 1, trades.tradeDate, by=[trades.desk])",
        "percentile(trades.mtm, 0.5, trades.mtm < 0) => percentile(trades.mtm, 0.5, where=trades.mtm < 0)",
        "-(trades.a - trades.b) * 2 => -(trades.a - trades.b) * 2",
        "ratio(sum(x.a), sum(x.b)) * 1000000 => ratio(sum(x.a), sum(x.b)) * 1000000",
        "not trades.a == 1 => not trades.a == 1",
    })
    void convertsTheOldSyntaxKeepingTheTree(String old, String expected) {
        Expressions.Converted c = Expressions.convert(old.strip());
        assertThat(c.to()).isEqualTo(expected.strip());
        assertThat(Expressions.normal(v2(c.to()))).isEqualTo(Expressions.normal(ExprParser.parse(old.strip())));
        assertThat(Expressions.convert(c.to()).changed()).isFalse();
    }

    @Test
    void readsTheOldSyntaxWithAMarkThenRefusesIt() {
        assertThat(Expressions.read("[MTM] / [Trades]").legacy()).isTrue();
        assertThat(Expressions.isLegacy("measure('MTM') / measure('Trades')")).isFalse();
        Expressions.configure(Expressions.Legacy.REFUSE, 100);
        assertThatThrownBy(() -> Expressions.parse("[MTM] / [Trades]")).isInstanceOf(DelException.class)
                .satisfies(e -> {
                    assertThat(((DelException) e).code()).isEqualTo("DRS-9100");
                    assertThat(((DelException) e).fix()).isEqualTo("write: measure('MTM') / measure('Trades')");
                });
        assertThat(Expressions.parse("measure('MTM') / measure('Trades')")).isEqualTo(new Node.Bin("/", new Node.MeasureRef("MTM"), new Node.MeasureRef("Trades")));
    }

    @Test
    void lowersToTheCompilersTree() {
        assertThat(v2("sum(t.x, where=t.y == 1)")).isEqualTo(new Node.Call("sum", List.of(new Node.Ref(List.of("t", "x")),
                new Node.Bin("==", new Node.Ref(List.of("t", "y")), new Node.Num(1, true)))));
        assertThat(v2("0 < t.x <= 10")).isEqualTo(new Node.Bin("&&", new Node.Bin("<", new Node.Num(0, true), new Node.Ref(List.of("t", "x"))),
                new Node.Bin("<=", new Node.Ref(List.of("t", "x")), new Node.Num(10, true))));
        assertThat(v2("t.x ** 2")).isEqualTo(new Node.Call("power", List.of(new Node.Ref(List.of("t", "x")), new Node.Num(2, true))));
        assertThat(v2("round(t.x)")).isEqualTo(new Node.Call("round", List.of(new Node.Ref(List.of("t", "x")))));
        assertThat(v2("len(t.s)")).isEqualTo(new Node.Call("length", List.of(new Node.Ref(List.of("t", "s")))));
        assertThat(v2("rank(sum(t.x), by=[t.d])")).isEqualTo(new Node.Call("rank", List.of(new Node.Call("sum", List.of(new Node.Ref(List.of("t", "x")))), new Node.Str("desc"),
                new Node.Ref(List.of("t", "d")))));
    }

    /** A model of two aliases for the checker: trades (mtm decimal, desk string masked, tradeDate date) and desks. */
    static final Fields MODEL = new Fields() {
        final Map<String, Fields.Field> cols = Map.of("trades.mtm", new Fields.Field("trades.mtm", DelType.DECIMAL, false), "trades.desk",
                new Fields.Field("trades.desk", DelType.STRING, true), "trades.tradeDate", new Fields.Field("trades.tradeDate", DelType.DATE, false), "trades.status",
                new Fields.Field("trades.status", DelType.STRING, false), "trades.n", new Fields.Field("trades.n", DelType.INTEGER, false));

        @Override
        public Optional<Fields.Field> field(List<String> parts) {
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
            return List.of("trades.mtm", "trades.desk", "trades.tradeDate", "trades.status", "trades.n", "MTM");
        }
    };

    static DelChecker.Checked check(String text, Scope scope) {
        return DelChecker.check(DelParser.parse(text), scope, MODEL);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "trades.mtm * 2                         | AGGREGATE | DRS-9127 | wrap it: sum(trades.mtm)",
        "sum(trades.mtm)                        | ROW       | DRS-9127 | an aggregate belongs in a measure",
        "sum(trades.mtm, trades.status == 'L')  | AGGREGATE | DRS-9127 | name it: where=...",
        "sum(trades.mtm, where=trades.mtm)      | AGGREGATE | DRS-9127 | is a boolean",
        "sum(trades.mtx)                        | AGGREGATE | DRS-9152 | did you mean trades.mtm?",
        "measure('MTN')                         | AGGREGATE | DRS-9152 | no measure 'MTN'",
        "trades.status == None                  | ROW       | DRS-9127 | write x is None",
        "trades.mtm and trades.n                | ROW       | DRS-9127 | takes conditions",
        "trades.status + 1                      | ROW       | DRS-9127 | text cannot be used",
        "trades.tradeDate > '9 Oct'             | ROW       | DRS-9127 | cannot compare a date",
        "trades.desk == 'RATES'                 | ROW       | DRS-9161 | masked",
        "calc(measure('MTM'), ignore=ALL)       | AGGREGATE | DRS-9129 | arrives in wave B",
        "trades.mtm * param.shock               | ROW       | DRS-9129 | wave B",
        "trades.status                          | RULE      | DRS-9127 | a row rule is a condition",
        "trades.status == user.name             | ROW       | DRS-9127 | is for row rules",
        "sum(sum(trades.mtm))                   | AGGREGATE | DRS-9127 | an aggregate inside an aggregate",
        "percentile(trades.mtm, 2)              | AGGREGATE | DRS-9127 | between 0 and 1",
        "startsWith(trades.status, 'L')         | ROW       | DRS-9127 | write startswith",
        "count(trades)                          | ROW       | DRS-9127 | an aggregate belongs in a measure",
        "date('09/10/2026') < trades.tradeDate  | ROW       | DRS-9127 | not an ISO date",
    })
    void checksScopesTypesAndNames(String text, Scope scope, String code, String says) {
        DelChecker.Checked c = check(text.strip(), scope);
        assertThat(c.ok()).as(text).isFalse();
        assertThat(c.problems()).as(text).anySatisfy(p -> {
            assertThat(p.code()).isEqualTo(code.strip());
            assertThat(p.message() + " | fix: " + p.fix()).contains(says.strip());
        });
    }

    @Test
    void typesWhatIsRight() {
        assertThat(check("sum(trades.mtm, where=trades.status == 'Live')", Scope.AGGREGATE).type()).isEqualTo(DelType.DECIMAL);
        assertThat(check("count(trades)", Scope.AGGREGATE).type()).isEqualTo(DelType.INTEGER);
        assertThat(check("measure('m_mtm') / count(trades)", Scope.AGGREGATE).measures()).containsExactly("MTM");
        assertThat(check("trades.tradeDate - trades.tradeDate", Scope.ROW).type()).isEqualTo(DelType.INTEGER);
        assertThat(check("trades.n // 2", Scope.ROW).type()).isEqualTo(DelType.INTEGER);
        assertThat(check("trades.tradeDate >= '2026-10-01'", Scope.ROW).ok()).isTrue();
        assertThat(check("trades.status in user.desks", Scope.RULE).ok()).isTrue();
        assertThat(check("rank(sum(trades.mtm), by=[trades.status])", Scope.AGGREGATE).ok()).isTrue();
        assertThat(check("ytd(sum(trades.mtm), trades.tradeDate)", Scope.AGGREGATE).fields()).contains("trades.tradeDate", "trades.mtm");
    }

    /** Every example of every built function in the catalogue is DEL v2 and lowers to the compiler's tree; a later wave's examples are refused DRS-9129. */
    @Test
    void everyCatalogueExampleReads() {
        FunctionCatalog cat = FunctionCatalog.get();
        assertThat(cat.all()).hasSizeGreaterThan(50);
        for (FunctionCatalog.Fn f : cat.all()) {
            assertThat(f.examples()).as(f.name()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(f.description()).as(f.name()).isNotBlank();
            for (FunctionCatalog.Example e : f.examples()) {
                DelAst tree = DelParser.parse(e.expr());
                DelChecker.Checked c = DelChecker.check(tree, null, Fields.NONE);
                if (f.built()) {
                    assertThat(c.problems().stream().filter(DelException.Problem::error)).as(e.expr()).isEmpty();
                    assertThat(DelLowering.lower(tree)).as(e.expr()).isNotNull();
                } else {
                    assertThat(c.problems()).as(e.expr()).anyMatch(p -> "DRS-9129".equals(p.code()));
                }
            }
        }
    }
}
