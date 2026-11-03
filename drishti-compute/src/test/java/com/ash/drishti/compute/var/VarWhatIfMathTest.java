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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pre-deal what-if in Java (VAR_GUIDE.md section 8.5). Parity: the answers the Python what-if engine gave on the demo's DESK-RATES
 * (2026-09-30, 1,487 trades, 509 scenarios; three hypothetical trades: an unwind, a half and a double), frozen in
 * {@code var/whatif-parity.json} before that engine was retired, are given again to the cent. And the worked example by hand: putting
 * back the hedge a scope took out gives the book again.
 */
class VarWhatIfMathTest {

    private static final double CENT = 0.005;

    @Test
    void theDemoDeskGivesThePythonEnginesAnswersToTheCent() throws Exception {
        JsonNode f;
        try (InputStream in = getClass().getResourceAsStream("/var/whatif-parity.json")) {
            f = new ObjectMapper().readTree(in);
        }
        VarCalc calc = new VarCalc(f.get("confidence").asDouble(), f.get("esConfidence").asDouble(), VarMath.RankRule.KTH_WORST,
                f.get("componentWindow").asInt(), true);
        List<VarWhatIfMath.Item> items = new ArrayList<>();
        for (JsonNode it : f.get("items")) {
            double factor = it.get("factor").asDouble();
            double[] v = doubles(it.get("pnl"));
            for (int i = 0; i < v.length; i++) {
                v[i] *= factor;
            }
            items.add(new VarWhatIfMath.Item(it.get("label").asText(), factor, v));
        }
        VarWhatIfMath.Result r = VarWhatIfMath.of(doubles(f.get("before")), items, calc, f.get("stressWindow").asInt());
        JsonNode e = f.get("expected");
        assertThat(r.beforeVar()).isCloseTo(e.get("BEFORE_VAR").asDouble(), within(CENT));
        assertThat(r.afterVar()).isCloseTo(e.get("AFTER_VAR").asDouble(), within(CENT));
        assertThat(r.changeVar()).isCloseTo(e.get("CHANGE_VAR").asDouble(), within(CENT));
        assertThat(r.beforeEs()).isCloseTo(e.get("BEFORE_ES").asDouble(), within(CENT));
        assertThat(r.afterEs()).isCloseTo(e.get("AFTER_ES").asDouble(), within(CENT));
        assertThat(r.changeEs()).isCloseTo(e.get("CHANGE_ES").asDouble(), within(CENT));
        assertThat(r.stressedBefore()).isCloseTo(e.get("STRESSED_VAR_BEFORE").asDouble(), within(CENT));
        assertThat(r.stressedAfter()).isCloseTo(e.get("STRESSED_VAR_AFTER").asDouble(), within(CENT));
        assertThat(r.stressedFrom()).isEqualTo(e.get("STRESSED_FROM").asInt());
        JsonNode xs = f.get("expectedItems");
        assertThat(r.items()).hasSize(xs.size());
        for (int i = 0; i < xs.size(); i++) {
            JsonNode x = xs.get(i);
            VarWhatIfMath.ItemResult it = r.items().get(i);
            assertThat(it.label()).isEqualTo(x.get("LABEL").asText());
            assertThat(it.factor()).isEqualTo(x.get("FACTOR").asDouble());
            assertThat(it.standaloneVar()).as(it.label()).isCloseTo(x.get("STANDALONE_VAR").asDouble(), within(CENT));
            assertThat(it.standaloneEs()).as(it.label()).isCloseTo(x.get("STANDALONE_ES").asDouble(), within(CENT));
            assertThat(it.incrementalVar()).as(it.label()).isCloseTo(x.get("INCREMENTAL_VAR").asDouble(), within(CENT));
            assertThat(it.incrementalAlone()).as(it.label()).isCloseTo(x.get("INCREMENTAL_ALONE").asDouble(), within(CENT));
            assertThat(it.componentVar()).as(it.label()).isCloseTo(x.get("COMPONENT_VAR").asDouble(), within(CENT));
            assertThat(it.componentEs()).as(it.label()).isCloseTo(x.get("COMPONENT_ES").asDouble(), within(CENT));
        }
    }

    // the worked example of VAR_GUIDE.md section 18 (N = 10, alpha 80%, a component window of 3)
    private static final double[] T1 = {5, -8, 3, -12, 6, -2, 4, -5, 7, 1};
    private static final double[] T2 = {-2, 3, -1, 4, -3, 1, -2, 2, -3, 0};
    private static final double[] T3 = {-4, 2, -6, 1, -9, 3, -1, 0, 2, -7};

    @Test
    void puttingTheHedgeBackGivesTheBookAgain() {
        VarCalc calc = new VarCalc(0.8, 0.8, VarMath.RankRule.KTH_WORST, 3, true);
        double[] withoutT2 = new double[10];
        double[] halfT3 = new double[10];
        for (int i = 0; i < 10; i++) {
            withoutT2[i] = T1[i] + T3[i];
            halfT3[i] = 0.5 * T3[i];
        }
        VarWhatIfMath.Result r = VarWhatIfMath.of(withoutT2, List.of(new VarWhatIfMath.Item("T2 again", 1, T2),
                new VarWhatIfMath.Item("half of T3", 0.5, halfT3)), calc, 5);
        assertThat(r.beforeVar()).isEqualTo(6);
        assertThat(r.beforeEs()).isEqualTo(8.5);
        assertThat(r.items().get(0).incrementalVar()).isZero();                  // back to the book: VaR 6 again
        assertThat(r.items().get(0).incrementalAlone()).isZero();
        assertThat(r.items().get(0).incrementalVar() + r.items().get(1).incrementalVar()).isCloseTo(r.changeVar(), within(1e-9));
        assertThat(r.items().get(1).standaloneVar()).isEqualTo(calc.var(halfT3));
        VarCalc.Measures m = calc.measures(r.after());
        assertThat(r.afterVar()).isEqualTo(m.var());
        // the scope before and the trades partition the scope after: their components add up to its VaR
        assertThat(VarMath.component(withoutT2, m.tail(), m.scale()) + r.items().get(0).componentVar() + r.items().get(1).componentVar())
                .isCloseTo(r.afterVar(), within(1e-9));
        assertThat(r.stressedBefore()).isGreaterThanOrEqualTo(r.beforeVar() - 1e-9);
        assertThat(r.stressWindow()).isEqualTo(5);
    }

    @Test
    void theStressedWindowIsTheWorstRunOfDays() {
        VarCalc calc = new VarCalc(0.8, 0.8, VarMath.RankRule.KTH_WORST, 3, true);
        double[] v = {1, 1, 1, -50, -40, 1, 1, 1, 1, 1};
        VarWhatIfMath.Result r = VarWhatIfMath.of(v, List.of(), calc, 3);
        assertThat(r.stressedBefore()).isEqualTo(50);
        assertThat(r.stressedFrom()).isEqualTo(1);                              // the first run of 3 days holding the -50
        assertThat(VarWhatIfMath.of(v, List.of(), calc, 10).stressedBefore()).isEqualTo(calc.var(v));
        assertThat(VarWhatIfMath.of(v, List.of(), calc, 0).stressedFrom()).isEqualTo(-1);
    }

    @Test
    void aVectorOfAnotherLengthIsRefusedInWords() {
        VarCalc calc = VarCalc.defaults();
        assertThatThrownBy(() -> VarWhatIfMath.of(T1, List.of(new VarWhatIfMath.Item("short", 1, new double[] {1, 2, 3})), calc, 0))
                .hasMessage("what-if item short has 3 scenario P&Ls; the scope has 10");
    }

    private static double[] doubles(JsonNode a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).asDouble();
        }
        return out;
    }
}
