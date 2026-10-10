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
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ElTest {

    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, java.util.List.of("../config/packs/finance/config/formats.yaml"));
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
    void pathsReachAnyDepthThroughObjectsAndArrays() {
        DataNode deep = new JsonCodec().read("""
                {"trade": {"parties": {"us": {"entity": {"name": "Drishti Bank plc", "lei": "5493001"}}},
                           "legs": [{"schedule": {"periods": [{"fixing": {"rate": 0.0371, "source": {"index": "SOFR"}}},
                                                              {"fixing": {"rate": 0.0368, "source": {"index": "SOFR"}}}]}},
                                    {"schedule": {"periods": [{"fixing": {"rate": 0.0250, "source": {"index": "ESTR"}}}]}}]}}""");
        EvalContext c = EvalContext.of(deep, F);
        assertThat(Values.simplify(EL.compile("$.trade.parties.us.entity.name").eval(c))).isEqualTo("Drishti Bank plc");
        assertThat(Values.simplify(EL.compile("$.trade.legs[0].schedule.periods[1].fixing.source.index").eval(c))).isEqualTo("SOFR");
        assertThat(Values.simplify(EL.compile("$.trade.legs[-1].schedule.periods[0].fixing.rate").eval(c))).isEqualTo(0.025);
        assertThat(Values.simplify(EL.compile("size($.trade.legs[0].schedule.periods)").eval(c))).isEqualTo(2L);
        // a row inside a nested array reads further nesting relative to itself
        EvalContext row = c.withRow(deep.at("trade.legs[0].schedule.periods[0]"), 0);
        assertThat(Values.simplify(EL.compile("@.fixing.source.index + ' ' + fmt(@.fixing.rate, 'pct4')").eval(row))).isEqualTo("SOFR 3.7100%");
        // a missing branch anywhere in the path is simply empty, not an error
        assertThat(Values.simplify(EL.compile("$.trade.parties.them.entity.name").eval(c))).isNull();
        assertThat(Values.simplify(EL.compile("$.trade.legs[5].schedule.periods[0].fixing.rate").eval(c))).isNull();
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

    /** Fixed base seed, overridable with {@code -Ddrishti.test.seed=N}; a failure names the seed and case index. */
    private static final long BASE_SEED = Long.getLong("drishti.test.seed", 20260903L);
    private static final int[] EDGES = {0, 1, -1, 2, -2, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1};

    static IntStream arithmeticCases() {
        return IntStream.range(0, 300);
    }

    static IntStream comparisonCases() {
        return IntStream.range(0, 200);
    }

    private static SplittableRandom rng(int index, long salt) {
        return new SplittableRandom(BASE_SEED * 31 + salt + index);
    }

    /** An int: edge values a fifth of the time, otherwise uniform in the full range. */
    private static int anyInt(SplittableRandom r) {
        return r.nextInt(5) == 0 ? EDGES[r.nextInt(EDGES.length)] : r.nextInt();
    }

    @ParameterizedTest(name = "case {0}")
    @MethodSource("arithmeticCases")
    void integerArithmeticMatchesJava(int index) {
        SplittableRandom r = rng(index, 1);
        int a = r.nextInt(-10000, 10001);
        int b = r.nextInt(-10000, 10001);
        int c = r.nextInt(1, 100);
        String expr = a + " + " + b + " * " + c + " - (" + a + " % " + c + ")";
        Object res = EL.compile(expr).eval(EvalContext.of(DOC, F));
        assertThat(Values.number(res)).as("case %d baseSeed %d a=%d b=%d c=%d expr=%s", index, BASE_SEED, a, b, c, expr)
                .isEqualTo((double) (a + b * c - (a % c)));
    }

    @ParameterizedTest(name = "case {0}")
    @MethodSource("comparisonCases")
    void comparisonsAgreeWithJava(int index) {
        SplittableRandom r = rng(index, 2);
        int a = anyInt(r);
        int b = r.nextInt(4) == 0 ? a : anyInt(r);
        EvalContext ctx = EvalContext.of(DOC, F);
        String at = "case " + index + " baseSeed " + BASE_SEED + " a=" + a + " b=" + b;
        assertThat(EL.compile(a + " < " + b).eval(ctx)).as(at).isEqualTo(a < b);
        assertThat(EL.compile(a + " == " + b).eval(ctx)).as(at).isEqualTo(a == b);
    }

    @Test
    void textsThatAreNotNumbersOrderAsTextSoIsoDatesCompare() {
        assertThat(eval("'2026-01-31' < '2026-02-01'")).isEqualTo(true);
        assertThat(eval("'2027-06-30' >= '2027-01-01'")).isEqualTo(true);
        assertThat(eval("'2027-06-30' < '2027-01-01'")).isEqualTo(false);
        assertThat(eval("'10' < '9'")).isEqualTo(false);                   // texts that read as numbers stay numbers
        assertThat(eval("5 < 'abc'")).isEqualTo(false);                    // a number against text never orders
        assertThat(eval("$.nothing < '2027-01-01'")).isEqualTo(false);     // nor does empty
    }
}
