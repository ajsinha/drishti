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
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.common.Branding;
import com.ash.drishti.compute.ccr.CsaTerms;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
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
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Exposure Playground's workbook against the engine (docs/guides/EXPOSURE_GUIDE.md section 29, COUNTERPARTY_ENGINES.md sections 8.2
 * and 9): case X1 worked by hand, then the workbook written <b>without cached values</b> and recalculated by headless LibreOffice, so
 * every figure read back is LibreOffice's; each must equal the engine's to the cent and the band must read ALL CHECKS PASS. Case X1
 * with each collateral model and both rank rules, and a synthetic set (seven trade columns and Rest of set, 240 paths, a margined CSA
 * with rounding, an independent amount and initial margin, one primary date without a look-back point).
 */
class ExposurePlaygroundWorkbookTest {

    @TempDir
    Path work;

    @BeforeAll
    static void brand() {
        Branding.install(new Branding("TestProduct", "tag", "Test Owner", "Copyright (c) 2026 Test Owner. All rights reserved.", "notice"));
    }

    static PlaygroundTemplate template() {
        return PlaygroundTemplate.builtIn("exposure", ExposurePlaygroundBook.STYLES);
    }

    static byte[] write(ExposurePlaygroundModel m, boolean cached) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExposurePlaygroundBook(m, template(), cached, "tester", "2026-11-03T00:00:00Z").write(out);
        return out.toByteArray();
    }

    static ExposurePlayground.Choices choices(double[] qs, ExposureMath.Rule rule, String collateral, String date) {
        return new ExposurePlayground.Choices(qs, rule, collateral, date, null, 0);
    }

    @Test
    void caseX1ByHand() {
        ExposurePlaygroundModel m = ExposurePlayground.example(null);
        assertThat(m.stats.ee).containsExactly(new double[] {0.8, 1.6, 1.1, 1.3}, within(1e-12));
        assertThat(m.stats.ene).containsExactly(new double[] {0, 0, -0.4, -0.3}, within(1e-12));
        assertThat(m.stats.pfe[0]).containsExactly(new double[] {1, 2, 2, 2}, within(1e-12));          // 80%: the 4th smallest of 5
        assertThat(m.stats.pfe[1]).containsExactly(new double[] {1, 2, 2.5, 2}, within(1e-12));        // 95%: the largest
        assertThat(m.stats.eee).containsExactly(new double[] {0.8, 1.6, 1.6, 1.6}, within(1e-12));
        assertThat(m.stats.eeUncoll).containsExactly(new double[] {0.8, 1.8, 1.8, 2.2}, within(1e-12));
        assertThat(m.stats.ce).isCloseTo(0.4, within(1e-12));
        assertThat(m.stats.epe).isCloseTo(1.2, within(1e-12));
        assertThat(m.stats.eepe).isCloseTo(1.4, within(1e-12));
        assertThat(m.eadImm()).isCloseTo(1.96, within(1e-12));
        assertThat(m.tenor(m.stats.peakAt[1])).isEqualTo("9M");
        assertThat(m.tenor(m.stats.peakAt[0])).isEqualTo("6M");                                          // the earliest on a tie
        assertThat(m.tenor(m.peakEeAt)).isEqualTo("6M");
        assertThat(m.contributionAt).isEqualTo(1);
        assertThat(m.share).containsExactly(new double[] {17.0 / 15, 7.0 / 15}, within(1e-12));       // 1.133333 and 0.466667 (case X2's words)
        // the collateral steps of the contract's table: path 4 at 6M 1.5 -> 1.0; path 3 at 1Y the bank posts 0.5
        assertThat(m.target[1 * 5 + 3]).isEqualTo(1.5);
        assertThat(m.c[1 * 5 + 3]).isEqualTo(1.0);
        assertThat(m.c[3 * 5 + 2]).isEqualTo(-0.5);
        assertThat(m.c[1 * 5 + 0]).isEqualTo(0.0);                                                       // under the MTA
    }

    @Test
    void caseX1RecalculatesToTheEngine() throws Exception {
        recalculatedEqualsEngine(ExposurePlayground.example(null), "x1-lagged.xlsx");
    }

    @Test
    void caseX1WithEachModelAndRuleRecalculates() throws Exception {
        double[] qs = {0.8, 0.95};
        recalculatedEqualsEngine(ExposurePlayground.example(choices(qs, ExposureMath.Rule.INTERPOLATED, "lagged", "1Y")), "x1-interpolated.xlsx");
        recalculatedEqualsEngine(ExposurePlayground.example(choices(qs, ExposureMath.Rule.KTH, "current", "9M")), "x1-current.xlsx");
        recalculatedEqualsEngine(ExposurePlayground.example(choices(new double[] {0.9}, ExposureMath.Rule.KTH, "none", "peak")), "x1-none.xlsx");
    }

    @Test
    void aSyntheticSetWithRestOfSetRoundingAndInitialMarginRecalculates() throws Exception {
        recalculatedEqualsEngine(synthetic(ExposureMath.Rule.KTH), "synthetic-kth.xlsx");
        recalculatedEqualsEngine(synthetic(ExposureMath.Rule.INTERPOLATED), "synthetic-interpolated.xlsx");
    }

    @Test
    void theCachedWorkbookHoldsEveryTabTheChecksAndTheBranding() throws Exception {
        ExposurePlaygroundModel m = ExposurePlayground.example(null);
        byte[] xlsx = write(m, true);
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Map<String, String> parts = parts(xlsx);
        String wb = parts.get("xl/workbook.xml");
        for (String tab : List.of("Main", "Config", "Data", "Netting", "Collateral", "Exposure", "Stats", "Contributions", "Explain", "Provenance")) {
            assertThat(wb).contains("name=\"" + tab + "\"");
        }
        assertThat(wb).contains("definedName name=\"CollateralModel\"", "definedName name=\"RankRule\"", "definedName name=\"ContributionDate\"");
        String main = parts.get("xl/worksheets/sheet1.xml");
        assertThat(main).contains(ExposurePlaygroundBook.ALL_PASS, "Copyright (c) 2026 Test Owner", "lagged,current,none", "kth,interpolated");
        assertThat(main).doesNotContain(ExposurePlaygroundBook.FAIL + "</v>");
        assertThat(parts.get("docProps/core.xml")).contains("TestProduct").doesNotContain("{{");
        assertThat(parts.get("xl/worksheets/sheet9.xml")).contains("In plain words: what this workbook answers");
        // a size no spreadsheet opens is refused with the reason
        assertThat(ExposurePlaygroundBook.estimate(5000, 2001, 26, 13, 2, 20_000_000L).refused()).contains("cells");
        assertThat(ExposurePlaygroundBook.estimate(100, 700, 26, 13, 2, 0).refused()).contains("16,384");
        assertThat(ExposurePlaygroundBook.estimate(200, 51, 26, 13, 2, 20_000_000L).refused()).isNull();
    }

    // ---- the synthetic set ---------------------------------------------------------------------------------------------------------------

    /** Eight Data columns (seven trades and Rest of set holding five more), 240 paths, nine grid dates (one primary with no look-back). */
    static ExposurePlaygroundModel synthetic(ExposureMath.Rule rule) {
        LocalDate bd = LocalDate.of(2026, 9, 30);
        List<LocalDate> dates = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        List<Integer> lbOf = new ArrayList<>();
        dates.add(bd.plusDays(7));                                    // 1W: its look-back would be before today, so it has none
        roles.add("PRIMARY");
        lbOf.add(-1);
        int[] days = {30, 91, 182, 365, 730};
        for (int d : days) {
            dates.add(bd.plusDays(d - 14));
            roles.add("LOOKBACK");
            lbOf.add(dates.size());
            dates.add(bd.plusDays(d));
            roles.add("PRIMARY");
            lbOf.add(-1);
        }
        ExposureGrid grid = ExposureGrid.of(bd, dates, roles, lbOf, null);
        int g = grid.size();
        int p = 240;
        Random rnd = new Random(20261103L);
        List<ExposurePlaygroundModel.Column> cols = new ArrayList<>();
        double[] rest = new double[g * p];
        for (int x = 0; x < 12; x++) {
            double[] v = new double[g * p];
            double vol = 0.4 + 0.3 * x;
            double drift = (x % 3 - 1) * 0.2;
            for (int j = 0; j < p; j++) {
                double w = 0;
                for (int k = 0; k < g; k++) {
                    w += rnd.nextGaussian() * vol * Math.sqrt(Math.max(1, grid.time(k) * 12));
                    v[k * p + j] = Math.round((drift * k + w) * 1_000_000.0) / 1000.0;          // USD thousands to the cent of a million
                }
            }
            double mtm0 = Math.round(rnd.nextGaussian() * 100_000) / 100.0;
            if (x < 7) {
                cols.add(new ExposurePlaygroundModel.Column("T-" + (100 + x), "T-" + (100 + x) + " · IRS", false, 1, mtm0, 0.5 + x * 0.3, v, p));
            } else {
                for (int i = 0; i < v.length; i++) {
                    rest[i] += v[i];
                }
            }
        }
        cols.add(new ExposurePlaygroundModel.Column(ExposurePlayground.REST, "Rest of set (5 trades)", true, 5, 123.45, 3.0, rest, p));
        CsaTerms csa = new CsaTerms("CSA-SYN", 1500, 2500, 250, 250, 300, 100, 10, 1, true, 400, 750);
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("Source", "synthetic, seeded");
        ExposurePlaygroundModel.About about = new ExposurePlaygroundModel.About("NS-SYN", "CP-SYN", bd.toString(), "SR-1", "REAL_WORLD", 300, 12, 10, 1, 10, prov,
                Map.of("EPE (all paths)", 1.0));
        return new ExposurePlaygroundModel(about, grid, times(grid), cols, p, csa, ExposureMath.Model.LAGGED, rule, new double[] {0.95, 0.99}, 1.4, -1);
    }

    static double[] times(ExposureGrid grid) {
        double[] t = new double[grid.size()];
        for (int k = 0; k < t.length; k++) {
            t[k] = grid.time(k);
        }
        return t;
    }

    // ---- the recalculation ---------------------------------------------------------------------------------------------------------------

    /** Recalculates the workbook and checks Main against the engine, figure by figure, to the cent; the band must read ALL CHECKS PASS. */
    void recalculatedEqualsEngine(ExposurePlaygroundModel m, String name) throws Exception {
        byte[] xlsx = write(m, false);
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Assumptions.assumeTrue(LibreOfficeRecalc.available(), "LibreOffice is not installed here");
        long t0 = System.nanoTime();
        Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, name, xlsx), work);
        Map<String, Object> main = LibreOfficeRecalc.cells(saved, "Main");
        System.out.printf("exposure playground %s: %d bytes, LibreOffice %d ms, EPE %.4f%n", name, xlsx.length, (System.nanoTime() - t0) / 1_000_000,
                m.stats.epe);
        List<String> failing = new ArrayList<>();
        main.forEach((k, v) -> {
            if (ExposurePlaygroundBook.FAIL.equals(v) || v instanceof String t && t.startsWith("Err")) {
                int rr = Integer.parseInt(k.replaceAll("[A-Z]", ""));
                failing.add(k + " " + main.get("A" + rr) + ": workbook " + main.get("C" + rr) + ", engine " + main.get("D" + rr));
            }
        });
        assertThat(main.get("A3")).as(name + " band; failing " + failing).isEqualTo(ExposurePlaygroundBook.ALL_PASS);
        Map<String, Integer> rows = new LinkedHashMap<>();
        main.forEach((k, v) -> {
            if (k.startsWith("A") && v instanceof String s) {
                rows.putIfAbsent(s, Integer.parseInt(k.substring(1)));
            }
        });
        near(main, rows, "CE", m.stats.ce, name);
        near(main, rows, "EPE", m.stats.epe, name);
        near(main, rows, "EEPE", m.stats.eepe, name);
        near(main, rows, "Peak EE", m.stats.peakEe, name);
        near(main, rows, "EAD_IMM (informational)", m.eadImm(), name);
        assertThat(main.get("C" + rows.get("Peak EE date"))).isEqualTo(m.tenor(m.peakEeAt));
        for (int a = 0; a < m.qs.length; a++) {
            String label = "Peak PFE " + pct(m.qs[a]);
            near(main, rows, label, m.stats.peakPfe[a], name);
            assertThat(main.get("C" + rows.get(label + " date"))).as(name + " " + label + " date").isEqualTo(m.tenor(m.stats.peakAt[a]));
        }
        for (int i = 0; i < m.primaryCount(); i++) {
            int row = rows.get(m.tenor(i)) == null ? -1 : rows.get(m.tenor(i));
            assertThat(row).as(name + " profile row " + m.tenor(i)).isPositive();
            assertThat((Double) main.get("D" + row)).as(name + " EE " + m.tenor(i)).isCloseTo(m.stats.ee[i], within(0.005));
            assertThat((Double) main.get("E" + row)).as(name + " ENE " + m.tenor(i)).isCloseTo(m.stats.ene[i], within(0.005));
            for (int a = 0; a < m.qs.length; a++) {
                assertThat((Double) main.get(com.ash.drishti.server.dist.XlsxWriter.col(5 + a) + row)).as(name + " PFE " + m.tenor(i))
                        .isCloseTo(m.stats.pfe[a][i], within(0.005));
            }
        }
        Map<String, Object> contrib = LibreOfficeRecalc.cells(saved, "Contributions");
        for (int x = 0; x < m.columns.size(); x++) {
            String c = com.ash.drishti.server.dist.XlsxWriter.col(5 + x);
            assertThat((Double) contrib.get(c + "5")).as(name + " share " + m.columns.get(x).id()).isCloseTo(m.share[x], within(0.005));
        }
    }

    private static String pct(double q) {
        double p = Math.round(q * 10_000) / 100.0;
        return (p == Math.rint(p) ? String.valueOf((long) p) : String.valueOf(p)) + "%";
    }

    private static void near(Map<String, Object> main, Map<String, Integer> rows, String label, double engine, String name) {
        Integer row = rows.get(label);
        assertThat(row).as(name + " row " + label).isNotNull();
        assertThat((Double) main.get("C" + row)).as(name + " " + label).isCloseTo(engine, within(0.005));
        assertThat(main.get("E" + row)).as(name + " check " + label).isEqualTo(ExposurePlaygroundBook.PASS);
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
