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
package com.ash.drishti.server.frtb;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.Branding;
import com.ash.drishti.compute.frtb.FactorDictionary;
import com.ash.drishti.compute.frtb.FrtbChoices;
import com.ash.drishti.compute.frtb.FrtbExamples;
import com.ash.drishti.compute.frtb.FrtbInput;
import com.ash.drishti.compute.frtb.FrtbSa;
import com.ash.drishti.compute.frtb.Sbm;
import com.ash.drishti.server.dist.XlsxFormulas;
import com.ash.drishti.server.playground.LibreOfficeRecalc;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The FRTB-SA Playground's workbook against the engine (FRTB_SA_GUIDE.md sections 8.1 and 10): written <b>without cached
 * values</b> and recalculated by headless LibreOffice, so every figure read back is LibreOffice's; each must equal the engine's to the
 * cent. The demo's sensitivities of six books (every risk class, delta, vega and curvature) with the default switches and with each switch
 * flipped; the hand-built cases with DRC, RRAO and a delivered gamma (the curvature-source switch both ways).
 */
class FrtbPlaygroundWorkbookTest {

    static FrtbService svc;
    static final String MIX = "BOOK:BOOK-FX-1 + BOOK:BOOK-EQD-1 + BOOK:BOOK-RATES-1 + BOOK:BOOK-INFL-1 + BOOK:BOOK-FI-1 + BOOK:BOOK-COMM-1";
    static final LocalDate COB = LocalDate.of(2026, 9, 30);

    @TempDir
    Path work;

    @BeforeAll
    static void setUp() {
        Branding.install(new Branding("TestProduct", "tag", "Test Owner", "Copyright (c) 2026 Test Owner. All rights reserved.", "notice"));
        svc = FrtbTestData.service(null);
    }

    static PlaygroundTemplate template() {
        return PlaygroundTemplate.builtIn("frtb-sa", FrtbPlaygroundBook.STYLES);
    }

    static List<FrtbPlaygroundBook.Alternative> alternatives(java.util.function.Function<FrtbChoices, FrtbSa.Result> runner, FrtbChoices c) {
        List<FrtbPlaygroundBook.Alternative> out = new ArrayList<>();
        for (FrtbChoices a : c.alternatives()) {
            String name = "";
            for (FrtbChoices.Switch s : FrtbChoices.SWITCHES) {
                if (!a.get(s.name()).equals(c.get(s.name()))) {
                    name = s.name();
                }
            }
            FrtbSa.Result r = runner.apply(a);
            out.add(new FrtbPlaygroundBook.Alternative(name, a.get(name), r.sbm().sbm(), r.total()));
        }
        return out;
    }

    static byte[] write(FrtbService.Run run, List<FrtbPlaygroundBook.Alternative> alts, boolean cached) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new FrtbPlaygroundBook(run, alts, template(), cached, "tester", "2026-11-02T00:00:00Z", "FR-TEST").write(out);
        return out.toByteArray();
    }

    /** Recalculates the workbook and checks Main against the engine, row by row, to the cent. */
    void recalculatedEqualsEngine(FrtbService.Run run, List<FrtbPlaygroundBook.Alternative> alts, String name) throws Exception {
        byte[] xlsx = write(run, alts, false);
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Assumptions.assumeTrue(LibreOfficeRecalc.available(), "LibreOffice is not installed here");
        long t0 = System.nanoTime();
        Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, name, xlsx), work);
        Map<String, Object> main = LibreOfficeRecalc.cells(saved, "Main");
        System.out.printf("frtb playground %s: %d bytes, LibreOffice %d ms, total %.2f%n", name, xlsx.length, (System.nanoTime() - t0) / 1_000_000,
                run.result().total());
        assertThat(main.get("A3")).as(name + " band").isEqualTo(FrtbPlaygroundBook.ALL_PASS);
        FrtbSa.Result r = run.result();
        int row = 6;
        for (Sbm.ClassCharge cc : r.sbm().charges) {
            for (int s = 0; s < 3; s++) {
                double sheet = (Double) main.get(String.valueOf((char) ('C' + s)) + row);
                assertThat(Math.abs(sheet - cc.charge[s])).as(name + " " + cc.cls + " " + cc.measure + " " + Sbm.SCENARIOS[s]).isLessThan(0.005);
            }
            assertThat(main.get("I" + row)).isEqualTo(FrtbPlaygroundBook.PASS);
            row++;
        }
        for (int s = 0; s < 3; s++) {
            assertThat(Math.abs((Double) main.get(String.valueOf((char) ('C' + s)) + row) - r.sbm().total[s])).as(name + " SBM " + s).isLessThan(0.005);
        }
        int total = row + 5;
        assertThat(main.get("A" + total)).isEqualTo("Total FRTB-SA");
        assertThat(Math.abs((Double) main.get("C" + total) - r.total())).as(name + " total").isLessThan(0.005);
        assertThat(main.get("I" + total)).isEqualTo(FrtbPlaygroundBook.PASS);
    }

    @Test
    void sixDemoBooksWithTheDefaultSwitchesRecalculateToTheEngine() throws Exception {
        FrtbChoices c = FrtbChoices.defaults();
        FrtbService.Run run = svc.run(null, MIX, COB, null, "USD", c);
        assertThat(run.result().sbm().charges.stream().map(x -> x.cls + x.measure).toList()).contains("GIRRDELTA", "GIRRVEGA", "GIRRCURVATURE", "CSR_NSDELTA",
                "EQDELTA", "EQVEGA", "EQCURVATURE", "COMMDELTA", "COMMVEGA", "COMMCURVATURE", "FXDELTA", "FXVEGA", "FXCURVATURE");
        assertThat(run.result().rrao().delivered()).isFalse();          // no VaR engine here: no notionals to derive it from
        recalculatedEqualsEngine(run, alternatives(a -> svc.run(null, MIX, COB, null, "USD", a).result(), c), "six-books-default.xlsx");
    }

    @Test
    void eachSwitchFlippedRecalculatesToTheEngineRunWithThatSwitch() throws Exception {
        for (FrtbChoices alt : FrtbChoices.defaults().alternatives()) {
            if (alt.get("curvature-source").equals("gamma")) {
                continue;                                             // the demo delivers no gamma: proved on the hand-built cases below
            }
            FrtbService.Run run = svc.run(null, MIX, COB, null, "USD", alt);
            recalculatedEqualsEngine(run, List.of(), "six-books-" + alt.changed().entrySet().iterator().next().getValue() + ".xlsx");
        }
    }

    static FrtbService.Run example(FrtbInput in, FrtbChoices c) {
        FrtbSa.Result r = FrtbSa.run(in, svc.params(c, svc.sbmBytes(), svc.drcBytes()), c);
        return new FrtbService.Run(r, "EXAMPLE", "EXAMPLE", null, null, "USD", new LinkedHashMap<>(Map.of("Inputs", "hand-built cases")),
                FrtbService.digest(in), List.of());
    }

    /** The hand-built cases with DRC, RRAO and case 10's gamma beside case 3's revaluations. */
    static FrtbInput cases() {
        FrtbInput t = FrtbExamples.together();
        FactorDictionary d = FrtbExamples.dictionary();
        List<FrtbInput.Element> els = new ArrayList<>(t.elements());
        els.add(new FrtbInput.Element(0, d.resolve("EQ/SPOT/SPX"), "GAMMA", -200, Double.NaN, Double.NaN));
        els.addAll(FrtbExamples.case9().elements().stream().map(e -> new FrtbInput.Element(e.position() + 1, e.factor(), e.measure(), e.value(), Double.NaN,
                Double.NaN)).toList());
        return new FrtbInput(t.positions(), els, "USD", t.drc(), t.rrao());
    }

    @Test
    void theHandBuiltCasesWithDrcRraoAndGammaRecalculateBothWays() throws Exception {
        for (FrtbChoices c : List.of(FrtbChoices.defaults(), FrtbChoices.defaults().with("curvature-source", "gamma"),
                FrtbChoices.defaults().with("girr-correlation", "exact"))) {
            FrtbService.Run run = example(cases(), c);
            assertThat(run.result().drc().total()).isCloseTo(126_177.32, org.assertj.core.data.Offset.offset(0.005));
            recalculatedEqualsEngine(run, alternatives(a -> example(cases(), a).result(), c), "cases-" + c.curvatureSource() + "-" + c.girrCorrelation() + ".xlsx");
        }
    }

    @Test
    void cachedWorkbookHasTheTabsTheSwitchListsAndTheBranding() throws Exception {
        FrtbService.Run run = example(cases(), FrtbChoices.defaults());
        byte[] xlsx = write(run, List.of(), true);
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Map<String, String> parts = new LinkedHashMap<>();
        try (var z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                parts.put(e.getName(), new String(z.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        String wb = parts.get("xl/workbook.xml");
        for (String tab : List.of("Main", "Config", "Data", "WS", "Buckets", "Classes", "Curvature", "DRC", "RRAO", "Explain", "Provenance")) {
            assertThat(wb).contains("name=\"" + tab + "\"");
        }
        assertThat(parts.get("xl/worksheets/sheet2.xml")).contains("<dataValidation type=\"list\"", "revaluation,gamma", "table,exact");
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains(FrtbPlaygroundBook.ALL_PASS, "Copyright (c) 2026 Test Owner");
        assertThat(parts.get("docProps/core.xml")).contains("TestProduct").doesNotContain("{{");
    }
}
