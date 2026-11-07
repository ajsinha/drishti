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

import com.ash.drishti.common.Branding;
import com.ash.drishti.banking.compute.var.ScenarioFx;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.compute.var.VarLens;
import com.ash.drishti.banking.compute.var.VarMath;
import com.ash.drishti.server.dist.XlsxFormulas;
import com.ash.drishti.server.playground.ExportSizing;
import com.ash.drishti.server.playground.LibreOfficeRecalc;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The VaR Playground's workbook against the engine (VAR_PLAYGROUND.md section 12), with no server: positions priced as scenario PVs in
 * USD, EUR and JPY and as USD P&amp;L vectors, the engine's vectors made the engine's way ({@link ScenarioFx#pnlFromPv},
 * {@link ScenarioFx#convert}) and its figures by {@link VarCalc} and {@link VarLens}; the workbook written <b>without cached values</b> and
 * recalculated by headless LibreOffice, so every figure read back is LibreOffice's own; each must equal the engine's to the cent. 1, 50 and
 * 500 positions (500 with Rest of scope), USD and EUR reporting, both rank rules. The formula scan, the template's refusals, the branding,
 * the sizes and the layouts too.
 */
class VarPlaygroundWorkbookTest {

    static final int N = 509;
    static final String[] CCY = {"USD", "EUR", "JPY", "USD"};
    static List<LocalDate> dates;
    static Map<String, VarPlaygroundModel.Fx> fx;

    @TempDir
    Path work;

    @BeforeAll
    static void setUp() {
        Branding.install(new Branding("TestProduct", "tag", "Test Owner", "Copyright (c) 2026 Test Owner. All rights reserved.", "notice"));
        dates = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 10, 18);
        while (dates.size() < N) {
            if (d.getDayOfWeek().getValue() < 6) {
                dates.add(d);
            }
            d = d.plusDays(1);
        }
        Random r = new Random(7);
        fx = new LinkedHashMap<>();
        double[] ones = new double[N];
        java.util.Arrays.fill(ones, 1.0);
        fx.put("USD", new VarPlaygroundModel.Fx("USD", 1, ones, "base"));
        fx.put("EUR", new VarPlaygroundModel.Fx("EUR", 1.0835, rates(r, 1.0835), "supplied"));
        fx.put("JPY", new VarPlaygroundModel.Fx("JPY", 0.006712, rates(r, 0.006712), "derived"));
    }

    static double[] rates(Random r, double base) {
        double[] out = new double[N];
        for (int j = 0; j < N; j++) {
            out[j] = base * (1 + 0.006 * r.nextGaussian());
        }
        return out;
    }

    /** {@code count} positions over one common factor and noise: every fourth a P&amp;L vector, the others scenario PVs. */
    static List<VarPlaygroundModel.Pos> positions(int count, long seed) {
        Random r = new Random(seed);
        double[] f = new double[N];
        for (int j = 0; j < N; j++) {
            f[j] = r.nextGaussian() * (j % 97 == 13 ? 4 : 1);
        }
        List<VarPlaygroundModel.Pos> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String c = CCY[i % 4];
            VarPlaygroundModel.Fx x = fx.get(c);
            double scale = (0.5 + r.nextDouble()) * (c.equals("JPY") ? 150 : 1) * 1e6;
            double beta = r.nextGaussian();
            double[] data = new double[N];
            boolean pnl = i % 4 == 3;
            double base = scale * (r.nextBoolean() ? 1 : -1);
            for (int j = 0; j < N; j++) {
                double move = scale * 0.01 * (beta * f[j] + 0.5 * r.nextGaussian());
                data[j] = pnl ? Math.round(move * 100) / 100.0 : base + move;
            }
            out.add(new VarPlaygroundModel.Pos("POS-" + (10_000 + i), "position", "POS-" + (10_000 + i), c, pnl ? 250_000.0 * (i % 3) : base, x.base(),
                    pnl ? "PnL" : "PV", pnl ? "pnl-vector" : "scenario-pv", data));
        }
        return out;
    }

    /** A position's vector the engine's way: USD P&amp;L from its PVs, then into the reporting currency through USD. */
    static double[] engineVector(VarPlaygroundModel.Pos p, String reporting) {
        double[] usd = new double[N];
        VarPlaygroundModel.Fx c = fx.get(p.currency());
        if (p.pv()) {
            ScenarioFx.pnlFromPv(p.basePv(), p.data(), N, new ScenarioFx.Rates(c.currency(), c.base(), c.rates(), c.source()), usd);
        } else {
            System.arraycopy(p.data(), 0, usd, 0, N);
        }
        if (reporting.equals("USD")) {
            return usd;
        }
        VarPlaygroundModel.Fx x = fx.get(reporting);
        double[] out = new double[N];
        ScenarioFx.convert(p.today(), usd, N, new ScenarioFx.Rates(x.currency(), x.base(), x.rates(), x.source()), out);
        return out;
    }

    record Case(VarPlaygroundModel model, VarPlaygroundBook.Engine engine, VarPlaygroundBook.About about) {}

    /** {@code shown} of {@code total} positions, the rest summed into Rest of scope (the engine's vectors less the shown ones). */
    static Case build(int shown, int total, String reporting, VarMath.RankRule rule, String horizon, String method) {
        List<VarPlaygroundModel.Pos> all = positions(total, 11L * total + shown);
        List<double[]> ev = new ArrayList<>();
        double[] scope = new double[N];
        for (VarPlaygroundModel.Pos p : all) {
            double[] v = engineVector(p, reporting);
            ev.add(v);
            VarMath.addInto(scope, v);
        }
        List<VarPlaygroundModel.Pos> pos = all.subList(0, shown);
        VarPlaygroundModel.Settings st = new VarPlaygroundModel.Settings(0.99, 0.975, List.of(0.95, 0.975, 0.99, 0.995), List.of(0.95, 0.975, 0.99), rule, 5,
                true, horizon, method, 50);
        List<VarPlaygroundModel.Fx> fxs = new ArrayList<>(List.of(fx.get("USD"), fx.get("EUR"), fx.get("JPY")));
        double[] rest = null;
        List<double[]> parts = new ArrayList<>(ev.subList(0, shown));
        if (shown < total) {
            VarPlaygroundModel only = new VarPlaygroundModel(dates, pos, null, fxs, reporting, st);
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
        VarPlaygroundModel m = new VarPlaygroundModel(dates, pos, rest, fxs, reporting, st);
        VarCalc calc = new VarCalc(0.99, 0.975, rule, 5, true);
        VarPlaygroundBook.Engine e = VarPlayground.engine(scope, parts, calc, VarLens.oneDay(0.99, 0.975), Math.min(50, shown), st.confidences(),
                st.esConfidences());
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("Business date", "2026-09-30");
        prov.put("Refused on the date (with DRS codes)", List.of("DRS-5206 T-9 a scenario failed to price"));
        VarPlaygroundBook.About about = new VarPlaygroundBook.About("DESK-RATES-NY", "NODE:MGMT~DESK-RATES-NY", "BANK › RATES › DESK-RATES-NY",
                "2026-09-30", "tester", "2026-11-01T00:00:00Z", total, total - shown, prov);
        return new Case(m, e, about);
    }

    static PlaygroundTemplate template() {
        return PlaygroundTemplate.builtIn("var", VarPlaygroundBook.STYLES);
    }

    static byte[] write(Case c, boolean cached) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new VarPlaygroundBook(c.model(), c.engine(), c.about(), template(), cached).write(out);
        return out.toByteArray();
    }

    /** Recalculates the workbook (no cached values) and checks every result against the engine's, to the cent. */
    void recalculatedEqualsEngine(Case c, String name) throws Exception {
        Assumptions.assumeTrue(LibreOfficeRecalc.available(), "LibreOffice is not installed here");
        byte[] xlsx = write(c, false);
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        long t0 = System.nanoTime();
        Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, name, xlsx), work);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> main = LibreOfficeRecalc.cells(saved, "Main");
        System.out.printf("playground %s: %d bytes, %d cells?, LibreOffice %d ms%n", name, xlsx.length, main.size(), ms);
        assertThat(main.get("B3")).as(name + " check band").isEqualTo(VarPlaygroundBook.ALL_PASS);
        VarPlaygroundBook.Engine e = c.engine();
        // the results block: the sheet's figure (F) against the engine's (G), every row
        for (int r = 5; r <= 15; r++) {
            double sheet = (Double) main.get("F" + r);
            double engine = (Double) main.get("G" + r);
            assertThat(Math.abs(sheet - engine)).as(name + " " + main.get("E" + r)).isLessThan(0.005);
            assertThat(main.get("H" + r)).isEqualTo(VarPlaygroundBook.PASS);
        }
        assertThat((Double) main.get("F7")).isCloseTo(e.var1(), org.assertj.core.data.Offset.offset(0.005));
        assertThat((Double) main.get("F8")).isCloseTo(e.es1(), org.assertj.core.data.Offset.offset(0.005));
        assertThat((Double) main.get("F12")).isCloseTo(e.var10sum(), org.assertj.core.data.Offset.offset(0.005));
        assertThat((Double) main.get("F13")).isCloseTo(e.es10sum(), org.assertj.core.data.Offset.offset(0.005));
        // every confidence: the rows under "Every confidence" and "Every ES confidence"
        int checked = 0;
        int table = -1;
        for (int r = 16; r < 200; r++) {
            Object a = main.get("A" + r);
            if (a instanceof Double conf && main.get("C" + r) instanceof Double) {
                for (String[] pair : new String[][] {{"C", "F"}, {"D", "G"}, {"E", "H"}}) {
                    assertThat(Math.abs((Double) main.get(pair[0] + r) - (Double) main.get(pair[1] + r))).as(name + " confidence " + conf + " " + pair[0])
                            .isLessThan(0.005);
                }
                checked++;
            }
            if ("Position".equals(a)) {
                table = r + 1;
                break;
            }
        }
        assertThat(checked).isEqualTo(c.model().set.confidences().size() + c.model().set.esConfidences().size());
        assertThat(table).isPositive();
        int items = c.model().positions.size() + (c.model().rest == null ? 0 : 1);
        for (int i = 0; i < items; i++) {
            int r = table + i;
            assertThat(Math.abs((Double) main.get("G" + r) - e.componentVar()[i])).as(name + " component VaR " + main.get("A" + r)).isLessThan(0.005);
            assertThat(Math.abs((Double) main.get("I" + r) - e.componentEs()[i])).as(name + " component ES " + main.get("A" + r)).isLessThan(0.005);
            assertThat(Math.abs((Double) main.get("K" + r) - e.component10()[i])).as(name + " component 10-day " + main.get("A" + r)).isLessThan(0.005);
            if (i < Math.min(50, c.model().positions.size())) {
                assertThat(Math.abs((Double) main.get("J" + r) - e.incremental()[i])).as(name + " incremental " + main.get("A" + r)).isLessThan(0.005);
            }
        }
        // the per-position standalone VaR against the engine's own calculator on the engine's vector
        Map<String, Object> pnl = LibreOfficeRecalc.cells(saved, "PnL");
        assertThat((Double) pnl.get("B4")).isCloseTo(c.model().pnl(0, 0), org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void onePositionUsdRecalculatesToTheEngine() throws Exception {
        recalculatedEqualsEngine(build(1, 1, "USD", VarMath.RankRule.KTH_WORST, "1D", "SQRT10"), "one-usd.xlsx");
    }

    @Test
    void fiftyPositionsInEurAndTheInterpolatedRuleRecalculateToTheEngine() throws Exception {
        recalculatedEqualsEngine(build(50, 50, "EUR", VarMath.RankRule.INTERPOLATED, "10D", "SUM"), "fifty-eur.xlsx");
    }

    @Test
    void fiveHundredPositionsWithRestOfScopeRecalculateToTheEngine() throws Exception {
        recalculatedEqualsEngine(build(500, 560, "USD", VarMath.RankRule.KTH_WORST, "10D", "SQRT10"), "five-hundred.xlsx");
    }

    @Test
    void cachedValuesAreTheModelsAndEveryFormulaIsInTheCommonSet() throws Exception {
        Case c = build(8, 12, "EUR", VarMath.RankRule.KTH_WORST, "1D", "SQRT10");
        byte[] xlsx = write(c, true);
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Map<String, String> parts = unzip(xlsx);
        assertThat(parts.keySet()).contains("xl/worksheets/sheet1.xml", "xl/worksheets/sheet7.xml", "docProps/core.xml", "xl/styles.xml");
        assertThat(parts.get("xl/workbook.xml")).contains("name=\"Main\"", "name=\"Config\"", "name=\"Data\"", "name=\"FX\"", "name=\"PnL\"",
                "name=\"Explain\"", "name=\"Provenance\"", "definedName name=\"Confidence\"", "definedName name=\"ReportingCurrency\"", "fullCalcOnLoad");
        // branding from configuration, never a literal: product and copyright in the properties and every footer
        assertThat(parts.get("docProps/core.xml")).contains("TestProduct", "Copyright (c) 2026 Test Owner").doesNotContain("{{");
        assertThat(parts.get("docProps/app.xml")).contains("TestProduct", "Test Owner");
        for (int s = 1; s <= 7; s++) {
            assertThat(parts.get("xl/worksheets/sheet" + s + ".xml")).contains("Copyright (c) 2026 Test Owner", "TestProduct", "Template var-playground 1.0");
        }
        assertThat(parts.get("xl/styles.xml")).doesNotContain("<!--");
        // the cached results are the model's (a viewer that never recalculates sees the right numbers)
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains(VarPlaygroundBook.ALL_PASS);
        String sheet7 = parts.get("xl/worksheets/sheet7.xml");
        assertThat(sheet7).contains("DRS-5206");
        String explain = parts.get("xl/worksheets/sheet6.xml");
        assertThat(explain).contains("Main!B").doesNotContain("{VaR1}").doesNotContain(":v}");
    }

    @Test
    void theFormulaScannerRefusesFunctionsOutsideTheCommonSet() {
        assertThat(XlsxFormulas.functions("SUMPRODUCT((A1:A9<B1)*1)+IF(C1=\"FILTER(\",1,0)")).containsExactly("SUMPRODUCT", "IF");
        assertThatThrownBy(() -> XlsxFormulas.check("FILTER(A1:A9,A1:A9>0)")).hasMessageContaining("FILTER");
        assertThatThrownBy(() -> XlsxFormulas.check("XLOOKUP(1,A:A,B:B)")).hasMessageContaining("XLOOKUP");
        assertThatThrownBy(() -> XlsxFormulas.check("INDIRECT(\"A1\")")).hasMessageContaining("INDIRECT");
        assertThatThrownBy(() -> XlsxFormulas.check("=SUM(A1)")).hasMessageContaining("leading");
        assertThat(XlsxFormulas.check("'Rest of scope'!A1+SUM(B1:B2)")).isNotNull();
    }

    @Test
    void aBanksTemplateMissingAStyleIsRefusedWithTheList(@TempDir Path dir) throws Exception {
        for (String part : List.of("template.properties", "xl/styles.xml", "docProps/core.xml", "docProps/app.xml", "explain.txt")) {
            Path f = dir.resolve(part);
            Files.createDirectories(f.getParent());
            try (var in = getClass().getClassLoader().getResourceAsStream("playground/var/template/" + part)) {
                Files.write(f, in.readAllBytes());
            }
        }
        assertThat(PlaygroundTemplate.folder(dir, VarPlaygroundBook.STYLES).version()).isEqualTo("1.0");
        Path props = dir.resolve("template.properties");
        Files.writeString(props, Files.readString(props).replace("style.check=16\n", "").replace("style.mark=19\n", ""));
        assertThatThrownBy(() -> PlaygroundTemplate.folder(dir, VarPlaygroundBook.STYLES)).hasMessageContaining("style.check").hasMessageContaining("style.mark");
        Files.delete(dir.resolve("explain.txt"));
        assertThatThrownBy(() -> PlaygroundTemplate.folder(dir, VarPlaygroundBook.STYLES)).hasMessageContaining("explain.txt");
    }

    @Test
    void sizesLayoutsAndTheApplicationsThatOpenThem() {
        ExportSizing.Estimate e500 = VarPlaygroundBook.estimate(500, true, 509, 4, 50, 7, 12_000_000);
        assertThat(e500.orientation()).isEqualTo("columns");
        assertThat(e500.apps()).contains("Excel", "LibreOffice Calc 7.4 or later", "LibreOffice Calc before 7.4", "Google Sheets");
        assertThat(e500.refused()).isNull();
        ExportSizing.Estimate e10k = VarPlaygroundBook.estimate(10_000, true, 509, 4, 50, 7, 20_000_000);
        assertThat(e10k.orientation()).isEqualTo("columns");
        assertThat(e10k.apps()).contains("Excel", "LibreOffice Calc 7.4 or later").doesNotContain("LibreOffice Calc before 7.4", "Google Sheets");
        ExportSizing.Estimate e20k = VarPlaygroundBook.estimate(18_412, false, 509, 4, 50, 7, 0);
        assertThat(e20k.orientation()).isEqualTo("rows");                  // past 16,384 columns: positions become rows
        assertThat(e20k.apps()).contains("Excel").doesNotContain("Google Sheets");
        ExportSizing.Estimate capped = VarPlaygroundBook.estimate(30_000, false, 509, 4, 50, 7, 20_000_000);
        assertThat(capped.refused()).contains("export-max-cells");
        ExportSizing.Estimate huge = VarPlaygroundBook.estimate(2_000_000, false, 509, 4, 50, 7, 0);
        assertThat(huge.refused()).contains("no spreadsheet application");
        assertThat(VarPlaygroundBook.columnsFit(16_330, 50)).isFalse();
        assertThat(VarPlaygroundBook.columnsFit(16_300, 0)).isTrue();
        assertThatThrownBy(() -> new VarPlayground.Settings(true, 501, 50, 50, "", List.of(500), 0, 500)).hasMessageContaining("at most 500");
    }

    @Test
    void positionsAsRowsGiveTheSameFigures() throws Exception {
        // a small book laid out as rows (as a workbook past 16,384 columns is): the same formulas through the other orientation
        Case c = build(6, 6, "USD", VarMath.RankRule.KTH_WORST, "1D", "SQRT10");
        VarPlaygroundBook rows = new VarPlaygroundBook(c.model(), c.engine(), c.about(), template(), false, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rows.write(out);
        Assumptions.assumeTrue(LibreOfficeRecalc.available(), "LibreOffice is not installed here");
        Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, "rows.xlsx", out.toByteArray()), work);
        Map<String, Object> main = LibreOfficeRecalc.cells(saved, "Main");
        assertThat(main.get("B3")).isEqualTo(VarPlaygroundBook.ALL_PASS);
        assertThat((Double) main.get("F7")).isCloseTo(c.engine().var1(), org.assertj.core.data.Offset.offset(0.005));
    }

    static Map<String, String> unzip(byte[] xlsx) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
