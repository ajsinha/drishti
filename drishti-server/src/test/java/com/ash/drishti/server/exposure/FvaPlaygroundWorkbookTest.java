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
package com.ash.drishti.server.exposure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.Branding;
import com.ash.drishti.compute.ccr.FvaMath;
import com.ash.drishti.server.dist.XlsxFormulas;
import com.ash.drishti.server.playground.LibreOfficeRecalc;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The FVA Playground's workbook against the engine (COUNTERPARTY_ENGINES.md sections 8.3, 8.4 and case F1): written <b>without cached
 * values</b> and recalculated by headless LibreOffice, so every figure read back is LibreOffice's; each equals FvaMath's to the cent, and the
 * band says ALL CHECKS PASS. Case F1 (a) to (d), the mid point, FCA only with an asymmetric lending spread, and two netting sets side by
 * side; the formulas use only the common set.
 */
class FvaPlaygroundWorkbookTest {

    static final double M = 1_000_000;
    static final double[] T = {0.25, 0.5, 0.75, 1.0};
    static final FvaMath.Profile X1 = new FvaMath.Profile(T, new double[] {0.8 * M, 1.6 * M, 1.1 * M, 1.3 * M}, new double[] {0, 0, -0.4 * M, -0.3 * M},
            0.4 * M, 0);
    static final FvaMath.Profile OTHER = new FvaMath.Profile(T, new double[] {0.1 * M, 0.2 * M, 0.3 * M, 0.4 * M}, new double[] {-0.5 * M, 0, 0, -0.1 * M},
            0.05 * M, -0.2 * M);
    static final FvaMath.Curve FLAT50 = FvaMath.Curve.flat("flat 0.005", 0.005, "test");
    static final FvaMath.Curve DF4 = new FvaMath.Curve("df4", List.of("1Y"), new double[] {1.0}, new double[] {Math.exp(-0.04)}, "test");

    @TempDir
    Path work;

    @BeforeAll
    static void brand() {
        Branding.install(new Branding("TestProduct", "tag", "Test Owner", "Copyright (c) 2026 Test Owner. All rights reserved.", "notice"));
    }

    record Case(String name, List<FvaMath.Profile> sets, FvaMath.Curve borrow, FvaMath.Curve lend, FvaMath.Curve discount, boolean survival,
            FvaMath.Point point, boolean fba, Double fva) {}

    static FvaPlaygroundBook.Input input(Case c) {
        double hc = 0.02;
        double hb = 0.01;
        FvaMath.Market m = FvaMath.market(T, c.discount(), c.borrow(), c.lend(), null);
        if (c.survival()) {
            m = new FvaMath.Market(m.df(), m.sb(), m.sl(), FvaMath.flatSurvival(T, hc, hb));
        }
        List<FvaPlaygroundBook.SetIn> sets = new ArrayList<>();
        List<FvaMath.Result> parts = new ArrayList<>();
        int i = 1;
        for (FvaMath.Profile p : c.sets()) {
            FvaMath.Result r = FvaMath.compute(p, m, c.point(), c.fba());
            parts.add(r);
            sets.add(new FvaPlaygroundBook.SetIn("NS-" + i, "CP-" + i, "none", p.ce(), p.nce(), r));
            i++;
        }
        return new FvaPlaygroundBook.Input("TEST", c.name(), "2026-09-30", "XR-TEST", "RISK_NEUTRAL", List.of(), List.of("a", "b", "c", "d"), T, c.borrow(),
                c.lend(), c.discount(), hc, hb, c.point(), c.survival(), c.fba(), sets, FvaMath.sum(parts), Map.of("Source", "test"));
    }

    void recalculatedEqualsEngine(Case c) throws Exception {
        FvaPlaygroundBook.Input in = input(c);
        if (c.fva() != null) {
            assertThat(in.total().fva()).as(c.name() + " by hand").isCloseTo(c.fva(), org.assertj.core.data.Offset.offset(0.005));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new FvaPlaygroundBook(in, PlaygroundTemplate.builtIn("fva", FvaPlaygroundBook.STYLES), "tester", "2026-11-03T00:00:00Z", false).write(out);
        byte[] xlsx = out.toByteArray();
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Assumptions.assumeTrue(LibreOfficeRecalc.available(), "LibreOffice is not installed here");
        Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, c.name() + ".xlsx", xlsx), work);
        Map<String, Object> main = LibreOfficeRecalc.cells(saved, "Main");
        assertThat(main.get("A3")).as(c.name() + ": " + main).isEqualTo(FvaPlaygroundBook.ALL_PASS);
        int tot = 6 + in.sets().size();
        assertThat((Double) main.get("D" + tot)).as(c.name() + " FCA").isCloseTo(in.total().fca(), org.assertj.core.data.Offset.offset(0.005));
        assertThat((Double) main.get("F" + tot)).as(c.name() + " FBA").isCloseTo(in.total().fba(), org.assertj.core.data.Offset.offset(0.005));
        assertThat((Double) main.get("H" + tot)).as(c.name() + " FVA").isCloseTo(in.total().fva(), org.assertj.core.data.Offset.offset(0.005));
        for (int r = 6; r <= tot + 1; r++) {
            assertThat(main.get("J" + r)).as(c.name() + " check J" + r).isEqualTo(FvaPlaygroundBook.PASS);
        }
    }

    @Test
    void caseF1RecalculatesToTheEngineToTheCent() throws Exception {
        FvaMath.Curve term = new FvaMath.Curve("term", List.of("6M", "9M"), new double[] {0.5, 0.75}, new double[] {0.004, 0.006}, "test");
        recalculatedEqualsEngine(new Case("f1a", List.of(X1), FLAT50, null, null, false, FvaMath.Point.END, true, -5_125.00));
        recalculatedEqualsEngine(new Case("f1b", List.of(X1), FLAT50, null, DF4, false, FvaMath.Point.END, true, -5_000.57));
        recalculatedEqualsEngine(new Case("f1c", List.of(X1), term, null, DF4, false, FvaMath.Point.END, true, -4_820.51));
        recalculatedEqualsEngine(new Case("f1d", List.of(X1), FLAT50, null, DF4, true, FvaMath.Point.END, true, -4_909.60));
    }

    @Test
    void theChoicesAndTwoSetsSideBySideRecalculate() throws Exception {
        FvaMath.Curve lend = FvaMath.Curve.flat("flat 0.003", 0.003, "test");
        FvaMath.Curve oisLike = new FvaMath.Curve("ois", List.of("3M", "1Y", "2Y"), new double[] {0.25, 1.0, 2.0},
                new double[] {0.9910, 0.9644, 0.9310}, "test");
        recalculatedEqualsEngine(new Case("mid", List.of(X1), FLAT50, null, null, false, FvaMath.Point.MID, true, -5_437.50 + 687.50));
        recalculatedEqualsEngine(new Case("fca-only", List.of(X1), FLAT50, lend, DF4, false, FvaMath.Point.END, false, -5_846.09));
        recalculatedEqualsEngine(new Case("asymmetric", List.of(X1), FLAT50, lend, oisLike, false, FvaMath.Point.END, true, null));
        recalculatedEqualsEngine(new Case("two-sets", List.of(X1, OTHER), FLAT50, null, oisLike, true, FvaMath.Point.MID, true, null));
    }

    @Test
    void theExampleWorkbookIsCaseF1b() throws Exception {
        FvaPlaygroundBook.Input in = FvaController.example();
        assertThat(in.total().fca()).isCloseTo(-5_846.09, org.assertj.core.data.Offset.offset(0.005));
        assertThat(in.total().fva()).isCloseTo(-5_000.57, org.assertj.core.data.Offset.offset(0.005));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new FvaPlaygroundBook(in, PlaygroundTemplate.builtIn("fva", FvaPlaygroundBook.STYLES), "tester", "2026-11-03T00:00:00Z").write(out);
        assertThat(XlsxFormulas.scan(out.toByteArray())).isEmpty();
        assertThat(PlaygroundTemplate.builtIn("fva", FvaPlaygroundBook.STYLES).explain()).anyMatch(s -> s[0].startsWith("8. FVA"));
    }
}
