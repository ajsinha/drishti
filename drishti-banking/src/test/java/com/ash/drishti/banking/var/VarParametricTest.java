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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.banking.compute.var.ParametricVar;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.compute.var.VarLens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The parametric blocks without a server: the headline's figures and its tooltip's lines carry the answer's numbers (no number left for
 * the page to form); the tree cell; the path's curve and markers; a component row's lines and the totals adding up; the square-root-of-time
 * rule; the settings refusing bad values in words.
 */
class VarParametricTest {

    static double[] vector(long seed, int n) {
        Random r = new Random(seed);
        double[] v = new double[n];
        for (int j = 0; j < n; j++) {
            v[j] = Math.round(r.nextGaussian() * 5e7 - 2e5 + (r.nextInt(60) == 0 ? -2e8 : 0));
        }
        return v;
    }

    @Test
    void theHeadlineAndItsLines() {
        VarParametric p = new VarParametric(VarParametricProperties.defaults());
        VarLens lens = VarLens.oneDay(0.99, 0.975);
        double[] v = vector(1, 509);
        ParametricVar.Figures f = p.figures(v, v, lens);
        Map<String, Object> h = p.headline(f, lens, "Bank", "HS-509", "2026-09-29", 158_495_340, 166_821_647, 6);
        assertThat(h.get("official")).isEqualTo(false);
        assertThat(h.get("var")).isEqualTo(Math.round(f.var()));
        assertThat(h.get("gap")).isEqualTo(Math.round(158_495_340 - f.var()));
        @SuppressWarnings("unchecked")
        List<String> d = (List<String>) h.get("describe");
        assertThat(d.get(0)).isEqualTo("Parametric VaR 99% · 1 day · Bank");
        assertThat(d.get(1)).contains("not the official VaR").contains("158,495,340");
        assertThat(String.join("\n", d)).contains("z = 2.3263").contains("6th worst of 509").contains(VarParametric.money(f.var()))
                .contains("HS-509, ending 2026-09-29").contains("Weighting: equal").contains("never in the browser");
        Map<String, Object> cell = p.treeCell(f, lens, "Rates", 84_051_284, 90_000_000);
        assertThat(cell).containsKeys("var", "es", "gap", "gapPct", "describe");
    }

    @Test
    void thePathCarriesTheCurveAndBothMarkers() {
        VarParametric p = new VarParametric(VarParametricProperties.defaults());
        VarLens lens = VarLens.oneDay(0.99, 0.975);
        double[] v = vector(2, 509);
        ParametricVar.Figures f = p.figures(v, v, lens);
        double[] edges = VarCalc.histogram(v, 40).edges();
        Map<String, Object> path = p.path(Map.of("var", 1L), f, edges, 150_000_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> markers = (Map<String, Object>) path.get("markers");
        assertThat(markers.get("historical")).isEqualTo(-150_000_000.0);
        assertThat((double) markers.get("parametric")).isEqualTo(f.fit().mu() - f.zSigma());
        assertThat(((double[]) ((Map<?, ?>) path.get("curve")).get("x"))).hasSize(121);
        assertThat((double[]) path.get("expected")).hasSize(40);
    }

    @Test
    void componentRowsAddUpAndSayHow() {
        VarParametric p = new VarParametric(VarParametricProperties.defaults());
        VarLens lens = VarLens.oneDay(0.99, 0.975);
        List<double[]> parts = new ArrayList<>();
        double[] v = new double[509];
        for (int i = 0; i < 5; i++) {
            double[] x = vector(10 + i, 509);
            parts.add(x);
            for (int j = 0; j < 509; j++) {
                v[j] += x[j];
            }
        }
        ParametricVar.Figures f = p.figures(v, v, lens);
        double sum = 0;
        for (int i = 0; i < 5; i++) {
            ParametricVar.Component c = ParametricVar.component(parts.get(i), v, f);
            Map<String, Object> row = p.row(c, f, Double.NaN, "Desk " + i, "Bank", true, 5);
            assertThat(row.get("pMarginalPer1m")).isNull();
            assertThat(row.get("describe").toString()).contains("z·cov(Pᵢ, P)/σ_P − μᵢ").contains("add up to the scope's");
            sum += c.componentVar();
        }
        assertThat(p.totals(f, sum, 0, true).get("pSumComponent")).isEqualTo(Math.round(f.var()));
        Map<String, Object> m = p.matrix(List.of("a", "b", "c", "d", "e"), List.of("A", "B", "C", "D", "E"), parts, f.fit().sigma());
        assertThat((double) m.get("sigmaCheck")).isCloseTo(f.fit().sigma(), org.assertj.core.data.Offset.offset(f.fit().sigma() * 1e-9));
    }

    @Test
    void squareRootOfTimeScalesTheOneDayFit() {
        VarParametric p = new VarParametric(new VarParametricProperties(true, "equal", 0.94, "square-root-of-time", null, null, null));
        double[] one = vector(3, 509);
        VarLens ten = new VarLens(10, VarLens.Method.SQRT, 0.99, 0.975);
        ParametricVar.Figures f = p.figures(ten.apply(one), one, ten);
        ParametricVar.Fit o = ParametricVar.fit(one, ParametricVar.Settings.EQUAL);
        assertThat(f.var()).isCloseTo(f.z() * o.sigma() * Math.sqrt(10) - 10 * o.mu(), org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void badSettingsRefuseInWords() {
        assertThatThrownBy(() -> new VarParametricProperties(true, "garch", null, null, null, null, null)).hasMessageContaining("equal or ewma");
        assertThatThrownBy(() -> new VarParametricProperties(true, "ewma", 1.5, null, null, null, null)).hasMessageContaining("(0.5, 1)");
        assertThatThrownBy(() -> new VarParametricProperties(true, null, null, "hourly", null, null, null)).hasMessageContaining("lens or square-root");
        assertThat(VarParametricProperties.defaults().describe()).containsEntry("weighting", "equal").containsEntry("official", false);
    }
}
