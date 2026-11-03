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
package com.ash.drishti.tables.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The layer's reader and printer of Rachana text: canonical form, precedence kept, what checked mode adds read, round trips stable. */
class ReaderPrinterTest {

    @Test
    void printsTheCanonicalForm() {
        assertThat(Reader.read("sum(trades.MTM,where=trades.TRADE_STATUS!=\"MATURED\")").print())
                .isEqualTo("sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED')");
        assertThat(Reader.read("$.DESK in user.desks").print()).isEqualTo("$.DESK in user.desks");
        assertThat(Reader.read("x not in ['A','B']").print()).isEqualTo("x not in ['A', 'B']");
        assertThat(Reader.read("^.LEG_ID").print()).isEqualTo("^.LEG_ID");
        assertThat(Reader.read("@.LEGS[0].NOTIONAL * 2").print()).isEqualTo("@.LEGS[0].NOTIONAL * 2");
        assertThat(Reader.read("$.FLOWS[*].AMOUNT").print()).isEqualTo("$.FLOWS[*].AMOUNT");
        assertThat(Reader.read("1_000_000").print()).isEqualTo("1000000");
        assertThat(Reader.read("calc(measure('MTM'), ignore = [desks.NAME])").print()).isEqualTo("calc(measure('MTM'), ignore = [desks.NAME])");
    }

    @Test
    void keepsPrecedenceAndAddsOnlyTheParenthesesItNeeds() {
        assertThat(Reader.read("(a + b) * c").print()).isEqualTo("(a + b) * c");
        assertThat(Reader.read("a + (b * c)").print()).isEqualTo("a + b * c");
        assertThat(Reader.read("a - (b - c)").print()).isEqualTo("a - (b - c)");
        assertThat(Reader.read("a && b || c").print()).isEqualTo("(a && b) || c");
        assertThat(Reader.read("!(a == b)").print()).isEqualTo("!(a == b)");
        assertThat(Reader.read("c ? 1 : d ? 2 : 3").print()).isEqualTo("c ? 1 : d ? 2 : 3");
        assertThat(Reader.read("-5 + x").print()).isEqualTo("-5 + x");
    }

    @Test
    void escapesTextsAsRachanaReadsThem() {
        Node n = Reader.read("'it\\'s'");
        assertThat(((Node.Lit) n).value()).isEqualTo("it's");
        assertThat(n.print()).isEqualTo("'it\\'s'");
        assertThat(Reader.read("\"P&L 1d\"").print()).isEqualTo("'P&L 1d'");
    }

    @Test
    void aRoundTripIsStable() {
        for (String s : List.of("sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED' && trades.TRADE_STATUS != null)",
                "ratio(sum(trades.MTM), sum(trades.NOTIONAL)) * 1000000", "moving_avg(sum(trades.MTM), 3, trades.TRADE_DATE)",
                "measure('P&L 1d') / measure('Exposure (EE peak)')", "(a != 'X' && a != null) || b == 'Y'", "$.MTM / 1000000",
                "payments.CURRENCY == 'JPY' ? 1 / 148.2 : payments.CURRENCY == 'EUR' ? 1.174 : null")) {
            String once = Reader.read(s).print();
            assertThat(once).as(s).isEqualTo(s);
            assertThat(Reader.read(once).print()).isEqualTo(once);
        }
    }

    @Test
    void findsNamesPathsAndCalls() {
        List<String> seen = new ArrayList<>();
        Reader.read("related(core.desks.NAME, on = $.DESK) + @.X").walk(n -> {
            switch (n) {
                case Node.Name x -> seen.add("name " + x.name());
                case Node.Path p -> seen.add("path " + p.text());
                case Node.Call c -> seen.add("call " + c.function());
                default -> { }
            }
        });
        assertThat(seen).containsExactly("call related", "name core.desks.NAME", "path $.DESK", "path @.X");
    }

    @Test
    void refusesWhatDoesNotRead() {
        assertThatThrownBy(() -> Reader.read("sum(")).isInstanceOf(Reader.ReadException.class);
        assertThatThrownBy(() -> Reader.read("'open")).hasMessageContaining("closing quote");
        assertThatThrownBy(() -> Reader.read("a # b")).hasMessageContaining("unexpected");
        assertThatThrownBy(() -> Reader.read(" ")).hasMessageContaining("empty");
        assertThatThrownBy(() -> Reader.read("x".repeat(10_001))).hasMessageContaining("longer");
        assertThatThrownBy(() -> Reader.read("(".repeat(300) + "1" + ")".repeat(300))).hasMessageContaining("nested");
    }
}
