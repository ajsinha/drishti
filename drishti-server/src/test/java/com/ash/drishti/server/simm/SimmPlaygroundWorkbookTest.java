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
package com.ash.drishti.server.simm;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.Branding;
import com.ash.drishti.compute.simm.Simm;
import com.ash.drishti.compute.simm.SimmChoices;
import com.ash.drishti.compute.simm.SimmExamples;
import com.ash.drishti.compute.simm.SimmInput;
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
 * The SIMM Playground's workbook against the engine (SIMM_GUIDE.md sections 8 and 10), with the TOY parameter file
 * (not ISDA's): written <b>without cached values</b> and recalculated by headless LibreOffice, so every figure read back is LibreOffice's;
 * each must equal the engine's to the cent. Two demo netting sets (rates, credit, equity, commodity and FX; delta, vega and curvature;
 * concentration factors above 1) with the default switches, each switch flipped, and the post side (every sensitivity negated in Data);
 * the hand-built examples with every CSA add-on.
 */
class SimmPlaygroundWorkbookTest {

    static SimmService svc;
    static final LocalDate COB = LocalDate.of(2026, 9, 30);
    static final List<String> SETS = List.of("NS-ALDERSHOT-LDN", "NS-CORAL-NY");

    @TempDir
    Path work;

    @BeforeAll
    static void setUp() {
        Branding.install(new Branding("TestProduct", "tag", "Test Owner", "Copyright (c) 2026 Test Owner. All rights reserved.", "notice"));
        svc = SimmTestData.service();
    }

    static PlaygroundTemplate template() {
        return PlaygroundTemplate.builtIn("simm", SimmPlaygroundBook.STYLES);
    }

    static List<SimmPlaygroundBook.Alternative> alternatives(java.util.function.Function<SimmChoices, SimmService.Run> runner, SimmChoices c) {
        List<SimmPlaygroundBook.Alternative> out = new ArrayList<>();
        for (SimmChoices a : c.alternatives()) {
            String name = "";
            for (SimmChoices.Switch s : SimmChoices.SWITCHES) {
                if (!a.get(s.name()).equals(c.get(s.name()))) {
                    name = s.name();
                }
            }
            SimmService.Run r = runner.apply(a);
            out.add(new SimmPlaygroundBook.Alternative(name, a.get(name), r.imCollect(), r.imPost()));
        }
        return out;
    }

    static byte[] write(SimmService.Run run, List<SimmPlaygroundBook.Alternative> alts, boolean cached, String side) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SimmPlaygroundBook(run, alts, template(), cached, side, "tester", "2026-11-02T00:00:00Z", "SM-TEST").write(out);
        return out.toByteArray();
    }

    /** Recalculates the workbook and checks Main against the engine of the side Config starts on, row by row, to the cent. */
    void recalculatedEqualsEngine(SimmService.Run run, List<SimmPlaygroundBook.Alternative> alts, String side, String name) throws Exception {
        byte[] xlsx = write(run, alts, false, side);
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Assumptions.assumeTrue(LibreOfficeRecalc.available(), "LibreOffice is not installed here");
        long t0 = System.nanoTime();
        Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, name, xlsx), work);
        Map<String, Object> main = LibreOfficeRecalc.cells(saved, "Main");
        Simm.Result r = side.equals("post") ? run.post() : run.collect();
        System.out.printf("simm playground %s: %d bytes, LibreOffice %d ms, IM %.2f (%s)%n", name, xlsx.length, (System.nanoTime() - t0) / 1_000_000, r.total(),
                side);
        List<String> failing = new ArrayList<>();
        main.forEach((k, v) -> {
            if (SimmPlaygroundBook.FAIL.equals(v) || v instanceof String t && t.startsWith("Err")) {
                int rr = Integer.parseInt(k.replaceAll("[A-Z]", ""));
                failing.add(k + " " + main.get("A" + rr) + " " + main.get("B" + rr) + ": workbook " + main.get("C" + rr) + ", engine " + main.get("D" + rr) + " / "
                        + main.get("E" + rr));
            }
        });
        assertThat(main.get("A3")).as(name + " band " + main.get("A3") + "; failing " + failing + "; same " + main.entrySet().stream().filter(e -> e.getKey().startsWith("C")).map(e -> e.getKey() + "=" + e.getValue()).toList()).isEqualTo(SimmPlaygroundBook.ALL_PASS);
        int row = 6;
        for (Simm.Charge c : run.collect().charges()) {
            Simm.Charge mine = r.charge(c.productClass, c.riskClass, c.measure);
            double sheet = (Double) main.get("C" + row);
            assertThat(Math.abs(sheet - mine.margin)).as(name + " " + c.productClass + " " + c.riskClass + " " + c.measure).isLessThan(0.005);
            assertThat(main.get("F" + row)).isEqualTo(SimmPlaygroundBook.PASS);
            row++;
        }
        for (Simm.ProductIm pm : r.products()) {
            assertThat(Math.abs((Double) main.get("C" + row) - pm.simm())).as(name + " SIMM " + pm.productClass()).isLessThan(0.005);
            row++;
        }
        int im = row + 2;
        assertThat(main.get("A" + im)).isEqualTo("Initial margin");
        assertThat(Math.abs((Double) main.get("C" + im) - r.total())).as(name + " IM").isLessThan(0.005);
        assertThat(main.get("F" + im)).isEqualTo(SimmPlaygroundBook.PASS);
    }

    @Test
    void twoDemoNettingSetsWithTheDefaultSwitchesBothSidesRecalculateToTheEngine() throws Exception {
        for (String ns : SETS) {
            SimmChoices c = SimmChoices.defaults();
            SimmService.Run run = svc.run(null, ns, COB, null, null, c, null);
            assertThat(run.collect().groups()).anyMatch(g -> g.cr > 1);            // the toy thresholds bind somewhere
            List<SimmPlaygroundBook.Alternative> alts = alternatives(a -> svc.run(null, ns, COB, null, null, a, null), c);
            recalculatedEqualsEngine(run, alts, "collect", ns + "-collect.xlsx");
            recalculatedEqualsEngine(run, alts, "post", ns + "-post.xlsx");
        }
    }

    @Test
    void eachSwitchFlippedRecalculatesToTheEngineRunWithThatSwitch() throws Exception {
        for (SimmChoices alt : SimmChoices.defaults().alternatives()) {
            SimmService.Run run = svc.run(null, "NS-CORAL-NY", COB, null, null, alt, null);
            recalculatedEqualsEngine(run, List.of(), "collect", "coral-" + alt.changed().keySet().iterator().next() + "-off.xlsx");
        }
    }

    @Test
    void theHandBuiltExamplesWithEveryAddOnRecalculate() throws Exception {
        SimmInput in = SimmExamples.together();
        SimmInput withAddOns = new SimmInput(in.trades(), in.elements(), "USD", new SimmInput.AddOns(1_000_000, Map.of("Credit", 1.5),
                Map.of("Variance swap", 0.01), List.of(new SimmInput.Line("C11-1", "Variance swap", 25_000_000))));
        SimmService.Run base = svc.run(null, SimmService.EXAMPLE, null, null, null, SimmChoices.defaults(), null);
        Simm.Result c = Simm.run(withAddOns, base.collect().params(), SimmChoices.defaults(), false);
        Simm.Result p = Simm.run(withAddOns, base.collect().params(), SimmChoices.defaults(), true);
        SimmService.Run run = new SimmService.Run(SimmService.EXAMPLE, SimmService.EXAMPLE, null, null, null, "USD", base.version(), c, p, "NONE", "NONE",
                List.of("NONE"), List.of("NONE"), new LinkedHashMap<>(Map.of("Inputs", "hand-built examples")), SimmService.digest(withAddOns), 0, 0);
        assertThat(c.addOn().total()).isCloseTo(1_000_000 + 250_000 + 0.5 * c.product("Credit").simm(), org.assertj.core.data.Offset.offset(0.005));
        recalculatedEqualsEngine(run, List.of(), "collect", "examples-collect.xlsx");
        recalculatedEqualsEngine(run, List.of(), "post", "examples-post.xlsx");
    }

    @Test
    void theCachedWorkbookAndTheEmptyTemplate() throws Exception {
        SimmService.Run run = svc.run(null, SimmService.EXAMPLE, null, null, null, SimmChoices.defaults(), null);
        Map<String, String> parts = parts(write(run, List.of(), true, "collect"));
        String wb = parts.get("xl/workbook.xml");
        for (String tab : List.of("Main", "Config", "Data", "WS", "Buckets", "Classes", "Curvature", "Add-ons", "Explain", "Provenance")) {
            assertThat(wb).contains("name=\"" + tab + "\"");
        }
        assertThat(parts.get("xl/worksheets/sheet2.xml")).contains("<dataValidation type=\"list\"", "on,off", "collect,post");
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains(SimmPlaygroundBook.ALL_PASS, "Copyright (c) 2026 Test Owner");
        assertThat(parts.get("docProps/core.xml")).contains("TestProduct").doesNotContain("{{");
        // the shipped template: the same formulas, every parameter cell empty, no licensed number
        Map<String, String> tp = parts(write(svc.template(), List.of(), true, "collect"));
        assertThat(XlsxFormulas.scan(write(svc.template(), List.of(), true, "collect"))).isEmpty();
        assertThat(tp.get("xl/worksheets/sheet1.xml")).contains(SimmPlaygroundBook.TEMPLATE);
        String config = tp.get("xl/worksheets/sheet2.xml");
        assertThat(config).contains("InterestRate.across-currencies").doesNotContain("<v>0.3</v>").doesNotContain("<v>0.98</v>");
    }

    static Map<String, String> parts(byte[] xlsx) throws Exception {
        Map<String, String> parts = new LinkedHashMap<>();
        try (var z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                parts.put(e.getName(), new String(z.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return parts;
    }
}
