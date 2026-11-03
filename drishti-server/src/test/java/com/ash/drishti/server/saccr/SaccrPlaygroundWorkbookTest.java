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
package com.ash.drishti.server.saccr;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.Branding;
import com.ash.drishti.compute.saccr.Saccr;
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
 * The SA-CCR Playground's workbook against the engine (COUNTERPARTY_ENGINES.md sections 8.1 and 10): written <b>without cached values</b>
 * and recalculated by headless LibreOffice, so every figure read back is LibreOffice's; the band must read ALL CHECKS PASS (every result,
 * margined and unmargined, equal to the engine's to the cent) and the scope's EAD must equal the engine's. The hand-built cases (every
 * asset class, an option, a margined set where the cap binds) and two demo netting sets, then the cached workbook's structure.
 */
class SaccrPlaygroundWorkbookTest {

    static SaccrService svc;
    static final LocalDate COB = LocalDate.of(2026, 9, 30);

    @TempDir
    Path work;

    @BeforeAll
    static void setUp() {
        Branding.install(new Branding("TestProduct", "tag", "Test Owner", "Copyright (c) 2026 Test Owner. All rights reserved.", "notice"));
        svc = SaccrTestData.service();
    }

    static byte[] write(SaccrService.Run run, boolean cached) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SaccrPlaygroundBook(run, svc, PlaygroundTemplate.builtIn("saccr", SaccrPlaygroundBook.STYLES), cached, "tester", "2026-11-03T00:00:00Z").write(out);
        return out.toByteArray();
    }

    void recalculatedEqualsEngine(SaccrService.Run run, String name) throws Exception {
        byte[] xlsx = write(run, false);
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Assumptions.assumeTrue(LibreOfficeRecalc.available(), "LibreOffice is not installed here");
        Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, name, xlsx), work);
        Map<String, Object> main = LibreOfficeRecalc.cells(saved, "Main");
        List<String> failing = new ArrayList<>();
        main.forEach((k, v) -> {
            if (SaccrPlaygroundBook.FAIL.equals(v) || v instanceof String t && t.startsWith("Err")) {
                int rr = Integer.parseInt(k.replaceAll("[A-Z]", ""));
                failing.add(k + " " + main.get("A" + rr) + ": " + main.get("B" + rr) + " / " + main.get("C" + rr) + ", engine " + main.get("D" + rr) + " / "
                        + main.get("E" + rr));
            }
        });
        assertThat(main.get("A3")).as(name + ": failing " + failing).isEqualTo(SaccrPlaygroundBook.ALL_PASS);
        Object total = main.entrySet().stream().filter(e -> "EAD of the scope (Σ over the netting sets)".equals(e.getValue())).map(e -> main.get("B"
                + e.getKey().substring(1))).findFirst().orElse(null);
        assertThat(total).isInstanceOf(Double.class);
        assertThat(Math.abs((Double) total - run.ead())).as(name + " EAD").isLessThan(0.005);
        System.out.printf("saccr playground %s: %d bytes, EAD %.2f, %d sets%n", name, xlsx.length, run.ead(), run.sets().size());
    }

    @Test
    void theHandBuiltCasesRecalculateToTheEngine() throws Exception {
        recalculatedEqualsEngine(svc.run(null, "EXAMPLE", null, Saccr.Choices.REGULATORY, false), "examples.xlsx");
        recalculatedEqualsEngine(svc.run(null, "EXAMPLE", null, new Saccr.Choices(false, false), false), "examples-switched.xlsx");
    }

    @Test
    void twoDemoNettingSetsRecalculateToTheEngine() throws Exception {
        recalculatedEqualsEngine(svc.run(null, "NS-ALDERSHOT-NY", COB, Saccr.Choices.REGULATORY, false), "aldershot-ny.xlsx");
        recalculatedEqualsEngine(svc.run(null, "CPTY:CP-AURORA", COB, Saccr.Choices.REGULATORY, false), "aurora.xlsx");
    }

    @Test
    void theCachedWorkbookHasEveryTabTheSwitchesAndTheBranding() throws Exception {
        byte[] xlsx = write(svc.run(null, "EXAMPLE", null, Saccr.Choices.REGULATORY, false), true);
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Map<String, String> parts = new LinkedHashMap<>();
        try (var z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                parts.put(e.getName(), new String(z.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        String wb = parts.get("xl/workbook.xml");
        for (String tab : List.of("Main", "Config", "Data", "Trades", "Hedging sets", "Explain", "Provenance")) {
            assertThat(wb).contains("name=\"" + tab + "\"");
        }
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains(SaccrPlaygroundBook.ALL_PASS, "Copyright (c) 2026 Test Owner");
        assertThat(parts.get("xl/worksheets/sheet2.xml")).contains("<dataValidation type=\"list\"", "on,off", "CRE52.72", "CREDIT.single.sf.BBB");
        assertThat(parts.get("xl/worksheets/sheet4.xml")).contains("NORMSDIST(", "LN(");
        assertThat(parts.get("docProps/core.xml")).contains("TestProduct").doesNotContain("{{");
    }
}
