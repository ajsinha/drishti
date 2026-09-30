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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.format.Formats;
import java.util.TreeSet;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class ElTest {

    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, java.util.List.of("../packs/finance/config/formats.yaml"));
    static final DataNode DOC = new JsonCodec().read("""
            {"tradeId":"IRS-1","notional":50000000,"direction":"PAY_FIXED","rate":0.0385,
             "legs":[{"leg":1,"payer":true,"label":"Pay fixed","rows":[{"a":1},{"a":2.5}]},
                     {"leg":2,"payer":false,"label":"Receive SOFR"}],
             "cp":{"id":"CP-1","name":"Northbridge"}}""");

    static Object eval(String src) {
        return Values.simplify(EL.compile(src).eval(EvalContext.of(DOC, F)));
    }

    @Test
    void pathsOperatorsAndFunctions() {
        assertThat(eval("$.tradeId")).isEqualTo("IRS-1");
        assertThat(eval("$.legs[1].label")).isEqualTo("Receive SOFR");
        assertThat(eval("$.direction == 'PAY_FIXED' ? 'Pay fixed' : 'Receive fixed'")).isEqualTo("Pay fixed");
        assertThat(eval("size($.legs) == 2 && !$.legs[1].payer")).isEqualTo(true);
        assertThat(eval("'Leg ' + $.legs[0].leg + ' · ' + $.legs[0].label")).isEqualTo("Leg 1 · Pay fixed");
        assertThat(eval("sum($.legs[0].rows, 'a')")).isEqualTo(3.5);
        assertThat(eval("$.legs[?@.payer][0].leg")).isEqualTo(1L);
        assertThat(eval("fmt($.rate, 'pct4') + ' fixed'")).isEqualTo("3.8500% fixed");
        assertThat(eval("coalesce($.nope, $.cp.name)")).isEqualTo("Northbridge");
        assertThat(eval("max(3, -1, 7)")).isEqualTo(7L);
        assertThat(eval("(1 + 2) * 3 - 10 / 4")).isEqualTo(6.5);
        assertThat(eval("$.missing.deeper[3]")).isNull();
        assertThat(eval("1 / 0")).isNull();
        assertThat(EL.compile("link($.cp.id, 'counterparty', $.cp.name)").eval(EvalContext.of(DOC, F)))
                .isEqualTo(new Link("CP-1", "counterparty", "Northbridge"));
    }

    @Test
    void rowContextAndBareIdentifiers() {
        EvalContext row = EvalContext.of(DOC, F).withRow(DOC.at("legs[1]"), 1);
        assertThat(Values.simplify(EL.compile("label + ' #' + #index").eval(row))).isEqualTo("Receive SOFR #1");
        assertThat(Values.simplify(EL.compile("@.leg").eval(row))).isEqualTo(2L);
        assertThat(eval("tradeId")).isEqualTo("IRS-1");
    }

    @Test
    void templates() {
        assertThat(EL.template("Leg 2 · ${$.legs[1].label}").render(EvalContext.of(DOC, F))).isEqualTo("Leg 2 · Receive SOFR");
        assertThat(EL.template("plain").render(EvalContext.of(DOC, F))).isEqualTo("plain");
    }

    @Test
    void reportsDocumentPathsForLiveUpdates() {
        TreeSet<String> paths = new TreeSet<>();
        EL.compile("$.legs[0].rate * 2 + size($.legs) + @.x").paths(paths::add);
        assertThat(paths).containsExactly("$.legs", "$.legs[0].rate");
    }

    @Test
    void syntaxErrorsCarryPositions() {
        assertThatThrownBy(() -> EL.compile("$.a +")).isInstanceOfSatisfying(ElException.class, e -> assertThat(e.position()).isEqualTo(5));
        assertThatThrownBy(() -> EL.compile("nosuch(1)")).hasMessageContaining("unknown function");
        assertThatThrownBy(() -> EL.compile("size(1, 2)")).hasMessageContaining("takes 1");
        assertThatThrownBy(() -> EL.compile("'open")).hasMessageContaining("unterminated");
    }

    @Property(tries = 300)
    void integerArithmeticMatchesJava(@ForAll @IntRange(min = -10000, max = 10000) int a,
            @ForAll @IntRange(min = -10000, max = 10000) int b, @ForAll @IntRange(min = 1, max = 99) int c) {
        Object r = EL.compile(a + " + " + b + " * " + c + " - (" + a + " % " + c + ")").eval(EvalContext.of(DOC, F));
        assertThat(Values.number(r)).isEqualTo((double) (a + b * c - (a % c)));
    }

    @Property(tries = 200)
    void comparisonsAgreeWithJava(@ForAll int a, @ForAll int b) {
        EvalContext ctx = EvalContext.of(DOC, F);
        assertThat(EL.compile(a + " < " + b).eval(ctx)).isEqualTo(a < b);
        assertThat(EL.compile(a + " == " + b).eval(ctx)).isEqualTo(a == b);
    }
}
