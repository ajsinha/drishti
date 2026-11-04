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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.compute.var.ParametricVar;
import com.ash.drishti.compute.var.VarCalc;
import com.ash.drishti.compute.var.VarLens;
import com.ash.drishti.compute.var.VarMath;
import com.ash.drishti.server.dist.XlsxFormulas;
import com.ash.drishti.server.playground.LibreOfficeRecalc;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLAY PVAR's workbook (PARAMETRIC_VAR.md section 10), written without cached values and recalculated by headless LibreOffice: the
 * Parametric tab's PMu, PSigma, PZ, PVaR, PES and every position's component equal the engine's to the cent, Σ components = PVaR, the band
 * says PARAMETRIC CHECKS PASS; at 1 and 50 positions and at 500 with Rest of scope, equal weights and EWMA, USD and EUR; every formula in the
 * common set (NORM.S.INV and PI included). Skipped where LibreOffice is not installed.
 */
class PvarPlaygroundWorkbookTest {

    static final int N = VarPlaygroundWorkbookTest.N;

    @TempDir
    Path work;

    @BeforeAll
    static void setUp() {
        VarPlaygroundWorkbookTest.setUp();
    }

    record Case(VarPlaygroundModel model, VarPlaygroundBook.Engine engine, VarPlaygroundBook.About about, VarPlaygroundParametric.Engine parametric) {}

    static Case build(int shown, int total, String reporting, ParametricVar.Settings st) {
        List<VarPlaygroundModel.Pos> all = VarPlaygroundWorkbookTest.positions(total, 13L * total + shown);
        List<double[]> ev = new ArrayList<>();
        double[] scope = new double[N];
        for (VarPlaygroundModel.Pos p : all) {
            double[] v = VarPlaygroundWorkbookTest.engineVector(p, reporting);
            ev.add(v);
            VarMath.addInto(scope, v);
        }
        List<VarPlaygroundModel.Pos> pos = all.subList(0, shown);
        VarPlaygroundModel.Settings ms = new VarPlaygroundModel.Settings(0.99, 0.975, List.of(0.95, 0.99), List.of(0.975), VarMath.RankRule.KTH_WORST, 5,
                true, "1D", "SQRT10", 50);
        List<VarPlaygroundModel.Fx> fxs = new ArrayList<>(List.of(VarPlaygroundWorkbookTest.fx.get("USD"), VarPlaygroundWorkbookTest.fx.get("EUR"),
                VarPlaygroundWorkbookTest.fx.get("JPY")));
        List<double[]> parts = new ArrayList<>(ev.subList(0, shown));
        double[] rest = null;
        if (shown < total) {
            VarPlaygroundModel only = new VarPlaygroundModel(VarPlaygroundWorkbookTest.dates, pos, null, fxs, reporting, ms);
            rest = new double[N];
            double[] er = scope.clone();
            for (int j = 0; j < N; j++) {
                double s = 0;
                for (int i = 0; i < shown; i++) {
                    s += only.pnl(i, j);
                    er[j] -= ev.get(i)[j];
                }
                rest[j] = scope[j] - s;
            }
            parts.add(er);
        }
        VarPlaygroundModel m = new VarPlaygroundModel(VarPlaygroundWorkbookTest.dates, pos, rest, fxs, reporting, ms);
        VarCalc calc = new VarCalc(0.99, 0.975, VarMath.RankRule.KTH_WORST, 5, true);
        VarPlaygroundBook.Engine e = VarPlayground.engine(scope, parts, calc, VarLens.oneDay(0.99, 0.975), Math.min(50, shown), ms.confidences(),
                ms.esConfidences());
        VarPlaygroundBook.About about = new VarPlaygroundBook.About("DESK-RATES-NY", "NODE:MGMT~DESK-RATES-NY", "BANK › RATES › DESK-RATES-NY",
                "2026-09-30", "tester", "2026-11-04T00:00:00Z", total, total - shown, new LinkedHashMap<>());
        return new Case(m, e, about, VarPlaygroundParametric.engine(scope, parts, 0.99, 0.975, st));
    }

    static byte[] write(Case c, boolean cached) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new VarPlaygroundBook(c.model(), c.engine(), c.about(), VarPlaygroundWorkbookTest.template(), cached).parametric(c.parametric()).write(out);
        return out.toByteArray();
    }

    void recalculatedEqualsEngine(Case c, String name) throws Exception {
        Assumptions.assumeTrue(LibreOfficeRecalc.available(), "LibreOffice is not installed here");
        byte[] xlsx = write(c, false);
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, name, xlsx), work);
        Map<String, Object> s = LibreOfficeRecalc.cells(saved, "Parametric");
        ParametricVar.Figures f = c.parametric().scope();
        assertThat(s.get("B3")).as(name + " band").isEqualTo(VarPlaygroundParametric.ALL_PASS);
        assertThat((Double) s.get("B10")).as(name + " PMu").isCloseTo(f.fit().mu(), org.assertj.core.data.Offset.offset(Math.max(1e-6, Math.abs(f.fit().mu()) * 1e-9)));
        assertThat((Double) s.get("B11")).as(name + " PSigma").isCloseTo(f.fit().sigma(), org.assertj.core.data.Offset.offset(f.fit().sigma() * 1e-9));
        assertThat((Double) s.get("B12")).as(name + " PZ").isCloseTo(f.z(), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Math.abs((Double) s.get("B14") - f.var())).as(name + " PVaR").isLessThan(0.005);
        assertThat(Math.abs((Double) s.get("B15") - f.es())).as(name + " PES").isLessThan(0.005);
        int items = c.model().positions.size() + (c.model().rest == null ? 0 : 1);
        int first = 30 + N + 3;
        double sum = 0;
        for (int i = 0; i < items; i++) {
            double cv = (Double) s.get("F" + (first + i));
            assertThat(Math.abs(cv - c.parametric().parts().get(i).componentVar())).as(name + " component " + s.get("A" + (first + i))).isLessThan(0.005);
            assertThat(s.get("J" + (first + i))).isEqualTo(VarPlaygroundBook.PASS);
            sum += cv;
        }
        assertThat(Math.abs(sum - (Double) s.get("B14"))).as(name + " Σ components = PVaR").isLessThan(0.005);
        System.out.printf("pvar playground %s: PVaR %.2f, %d positions%n", name, (Double) s.get("B14"), items);
    }

    @Test
    void onePositionUsdEqual() throws Exception {
        recalculatedEqualsEngine(build(1, 1, "USD", ParametricVar.Settings.EQUAL), "pvar-one.xlsx");
    }

    @Test
    void fiftyPositionsEurEwma() throws Exception {
        recalculatedEqualsEngine(build(50, 50, "EUR", new ParametricVar.Settings(ParametricVar.Weighting.EWMA, 0.94)), "pvar-fifty-ewma.xlsx");
    }

    @Test
    void fiveHundredWithRestOfScope() throws Exception {
        recalculatedEqualsEngine(build(500, 560, "USD", ParametricVar.Settings.EQUAL), "pvar-five-hundred.xlsx");
    }

    @Test
    void cachedValuesAndTheCommonSet() throws Exception {
        Case c = build(5, 5, "USD", ParametricVar.Settings.EQUAL);
        assertThat(XlsxFormulas.scan(write(c, true))).isEmpty();               // NORM.S.INV and PI are in the common set now
        assertThat(XlsxFormulas.check("NORM.S.INV(0.99)*SQRT(2*PI())")).isNotNull();
    }
}
