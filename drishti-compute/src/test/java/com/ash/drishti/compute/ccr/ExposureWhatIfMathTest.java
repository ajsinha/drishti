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
package com.ash.drishti.compute.ccr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The exact pre-deal what-if in Java (EXPOSURE_GUIDE.md section 18.7, method {@code paths}). Parity: the answers the Python what-if engine
 * ({@code exposure-whatif}) gave on a book of four netting sets (margined with thresholds, minimum transfers, an independent amount, rounding and
 * initial margin; unmargined with an independent amount; no CSA; collateral posted), 100 paths on 17 grid dates (seven look-back points),
 * frozen in {@code ccr/whatif-parity.json} before that engine was retired, are given again to the cent for six what-ifs: the three collateral
 * models, both rank rules, an unwind and a horizon the candidates lengthen. And by hand: the worked example's trade B joining trade A's set is
 * the worked example's set again; a candidate of its own is uncollateralised; refusals are in words.
 */
class ExposureWhatIfMathTest {

    private static final double CENT = 0.005;

    @Test
    void thePythonEnginesAnswersAreGivenAgainToTheCent() throws Exception {
        JsonNode f;
        try (InputStream in = getClass().getResourceAsStream("/ccr/whatif-parity.json")) {
            f = new ObjectMapper().readTree(in);
        }
        LocalDate bd = LocalDate.parse(f.get("businessDate").asText());
        JsonNode gj = f.get("grid");
        List<LocalDate> dates = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        List<Integer> lbOf = new ArrayList<>();
        List<String> tenors = new ArrayList<>();
        for (int k = 0; k < gj.get("days").size(); k++) {
            dates.add(bd.plusDays(gj.get("days").get(k).asInt()));
            roles.add(gj.get("roles").get(k).asText());
            lbOf.add(gj.get("lookbackOf").get(k).asInt());
            tenors.add(gj.get("tenors").get(k).isNull() ? null : gj.get("tenors").get(k).asText());
        }
        ExposureGrid grid = ExposureGrid.of(bd, dates, roles, lbOf, tenors);
        int p = f.get("paths").asInt();
        double[] times = ExposureCalc.times(grid);
        int checked = 0;
        for (JsonNode sc : f.get("scenarios")) {
            String name = sc.get("name").asText();
            ExposureMath.Model model = ExposureMath.Model.of(sc.get("collateral").asText());
            ExposureMath.Rule rule = sc.get("rule").asText().startsWith("interp") ? ExposureMath.Rule.INTERPOLATED : ExposureMath.Rule.KTH;
            double q = sc.get("q").asDouble();
            // the scope as the Java engine sums it: the other pieces, then each netting set under the asked collateral model
            double[] other = doubles(f.get("other"));
            double[] e = new double[other.length];
            double[] n = new double[other.length];
            for (int i = 0; i < other.length; i++) {
                e[i] = Math.max(other[i], 0);
                n[i] = Math.min(other[i], 0);
            }
            double ce = f.get("otherCe").asDouble();
            Map<String, ExposureWhatIfMath.NettingSet> sets = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> en : f.get("sets").properties()) {
                JsonNode s = en.getValue();
                CsaTerms csa = csa(s.get("csa"), en.getKey());
                double[] v = doubles(s.get("v"));
                double[][] enc = ExposureMath.exposure(v, grid, p, csa, model);
                double v0 = s.get("v0").asDouble();
                double ces = ExposureMath.currentExposure(v0, csa, model);
                for (int i = 0; i < e.length; i++) {
                    e[i] += enc[0][i];
                    n[i] += enc[1][i];
                }
                ce += ces;
                sets.put(en.getKey(), new ExposureWhatIfMath.NettingSet(en.getKey(), v, enc[0], enc[1], csa, v0, ces));
            }
            List<ExposureWhatIfMath.Candidate> cands = new ArrayList<>();
            for (JsonNode c : sc.get("cands")) {
                JsonNode x = f.get("candidates").get(c.asText());
                cands.add(new ExposureWhatIfMath.Candidate(x.get("TRADE_ID").asText(), x.get("LABEL").asText(), x.get("NETTING_SET").asText(),
                        doubles(x.get("MTM")), x.get("MTM0").isNull() ? Double.NaN : x.get("MTM0").asDouble(), x.get("LIFE_YEARS").asDouble()));
            }
            ExposureWhatIfMath.Result r = ExposureWhatIfMath.of(new ExposureWhatIfMath.Scope(e, n, ce, sc.get("life").asDouble()), sets, cands, grid, p,
                    times, q, model, rule, "CPTY:CP-PARITY");
            JsonNode x = sc.get("expected").get("row");
            ExposureMath.Stats b = r.before();
            ExposureMath.Stats a = r.after();
            near(name, "EPE", b.epe, a.epe, x);
            near(name, "EEPE", b.eepe, a.eepe, x);
            near(name, "PEAK_PFE", b.peakPfe[0], a.peakPfe[0], x);
            near(name, "PEAK_EE", b.peakEe, a.peakEe, x);
            near(name, "CE", b.ce, a.ce, x);
            assertThat(grid.tenor(grid.primaryAt(b.peakAt[0]))).as(name).isEqualTo(x.get("BEFORE_PEAK_PFE_TENOR").asText());
            assertThat(grid.tenor(grid.primaryAt(a.peakAt[0]))).as(name).isEqualTo(x.get("AFTER_PEAK_PFE_TENOR").asText());
            JsonNode prof = sc.get("expected").get("profile");
            assertThat(prof.size()).isEqualTo(times.length);
            for (int i = 0; i < times.length; i++) {
                JsonNode row = prof.get(i);
                String at = name + " " + row.get("TENOR").asText();
                assertThat(grid.tenor(grid.primaryAt(i))).isEqualTo(row.get("TENOR").asText());
                assertThat(grid.date(grid.primaryAt(i)).toString()).isEqualTo(row.get("DATE").asText());
                assertThat(times[i]).as(at).isCloseTo(row.get("T").asDouble(), within(1e-12));
                assertThat(b.ee[i]).as(at).isCloseTo(row.get("EE_BASE").asDouble(), within(CENT));
                assertThat(a.ee[i]).as(at).isCloseTo(row.get("EE").asDouble(), within(CENT));
                assertThat(b.ene[i]).as(at).isCloseTo(row.get("ENE_BASE").asDouble(), within(CENT));
                assertThat(a.ene[i]).as(at).isCloseTo(row.get("ENE").asDouble(), within(CENT));
                assertThat(b.pfe[0][i]).as(at).isCloseTo(row.get("PFE_BASE").asDouble(), within(CENT));
                assertThat(a.pfe[0][i]).as(at).isCloseTo(row.get("PFE").asDouble(), within(CENT));
                assertThat(a.ee[i] - b.ee[i]).as(at).isCloseTo(row.get("INCREMENTAL_EE").asDouble(), within(CENT));
                assertThat(a.pfe[0][i] - b.pfe[0][i]).as(at).isCloseTo(row.get("INCREMENTAL_PFE").asDouble(), within(CENT));
            }
            JsonNode items = sc.get("expected").get("items");
            assertThat(r.items()).hasSize(items.size());
            for (int i = 0; i < items.size(); i++) {
                JsonNode it = items.get(i);
                ExposureWhatIfMath.Item got = r.items().get(i);
                assertThat(got.id()).isEqualTo(it.get("TRADE_ID").asText());
                assertThat(got.label()).isEqualTo(it.get("LABEL").asText());
                assertThat(got.set()).isEqualTo(it.get("NETTING_SET").asText());
                assertThat(got.peakPfeAlone()).as(name + " " + got.id()).isCloseTo(it.get("PEAK_PFE_ALONE").asDouble(), within(CENT));
                assertThat(got.incrementalPeakPfe()).as(name + " " + got.id()).isCloseTo(it.get("INCREMENTAL_PEAK_PFE").asDouble(), within(CENT));
            }
            checked++;
        }
        assertThat(checked).isEqualTo(6);
    }

    private static void near(String name, String field, double before, double after, JsonNode x) {
        assertThat(before).as(name + " BEFORE_" + field).isCloseTo(x.get("BEFORE_" + field).asDouble(), within(CENT));
        assertThat(after).as(name + " AFTER_" + field).isCloseTo(x.get("AFTER_" + field).asDouble(), within(CENT));
        assertThat(after - before).as(name + " CHANGE_" + field).isCloseTo(x.get("CHANGE_" + field).asDouble(), within(CENT));
    }

    private static CsaTerms csa(JsonNode c, String set) {
        return new CsaTerms(c.get("present").asBoolean() ? "CSA-" + set : null, c.get("th_them").asDouble(), c.get("th_us").asDouble(),
                c.get("mta_them").asDouble(), c.get("mta_us").asDouble(), c.get("ia").asDouble(), c.get("rounding").asDouble(), 10, 1,
                c.get("margined").asBoolean(), c.get("held_today").asDouble(), c.get("im_held").asDouble());
    }

    // the worked example of EXPOSURE_GUIDE.md section 2.1 (ExposureMathTest): trade A's set, trade B a candidate
    private static final ExposureGrid GRID = ExposureMathTest.GRID;
    private static final int P = ExposureMathTest.P;
    private static final double[][] ZERO = new double[3][P];
    private static final double[] V_A = ExposureMathTest.matrix(ExposureMathTest.L, ExposureMathTest.A);
    private static final double[] M_B = ExposureMathTest.matrix(ZERO, ExposureMathTest.B);

    private static ExposureWhatIfMath.Result workedExample(String set) {
        double[][] enc = ExposureMath.exposure(V_A, GRID, P, ExposureMathTest.CSA, ExposureMath.Model.LAGGED);
        Map<String, ExposureWhatIfMath.NettingSet> sets = Map.of("NS-X", new ExposureWhatIfMath.NettingSet("NS-X", V_A, enc[0], enc[1],
                ExposureMathTest.CSA, 0, 0));
        return ExposureWhatIfMath.of(new ExposureWhatIfMath.Scope(enc[0], enc[1], 0, 1.0), sets,
                List.of(new ExposureWhatIfMath.Candidate("B", "B", set, M_B, 0, 1.0)), GRID, P, ExposureMathTest.TIMES, 0.75,
                ExposureMath.Model.LAGGED, ExposureMath.Rule.KTH, "NSET:NS-X");
    }

    @Test
    void tradeBJoiningTradeAsSetIsTheWorkedExampleAgain() {
        ExposureWhatIfMath.Result r = workedExample("NS-X");
        assertThat(r.after().ee).containsExactly(1.0, 1.5, 0.75);
        assertThat(r.after().pfe[0]).containsExactly(1, 2, 1);
        assertThat(r.after().epe).isCloseTo(1.0, within(1e-12));
        assertThat(r.after().eepe).isCloseTo(1.375, within(1e-12));
        assertThat(r.after().peakAt[0]).isEqualTo(1);
        assertThat(r.items().get(0).peakPfeAlone()).isEqualTo(1);                 // 75% of max(B, 0): 1, 0, 1
        assertThat(r.items().get(0).incrementalPeakPfe()).isCloseTo(r.after().peakPfe[0] - r.before().peakPfe[0], within(1e-12));
    }

    @Test
    void aCandidateOfItsOwnIsUncollateralised() {
        ExposureWhatIfMath.Result r = workedExample("");
        for (int i = 0; i < 3; i++) {
            double pos = 0;
            for (double x : ExposureMathTest.B[i]) {
                pos += Math.max(x, 0);
            }
            assertThat(r.after().ee[i] - r.before().ee[i]).isCloseTo(pos / P, within(1e-12));   // max(B, 0) path by path, no netting with A
        }
    }

    @Test
    void refusalsAreInWords() {
        assertThatThrownBy(() -> workedExample("NS-Y")).hasMessageContaining("joins the netting set NS-Y, which the scope NSET:NS-X does not cover whole");
        double[][] enc = ExposureMath.exposure(V_A, GRID, P, ExposureMathTest.CSA, ExposureMath.Model.LAGGED);
        assertThatThrownBy(() -> ExposureWhatIfMath.of(new ExposureWhatIfMath.Scope(enc[0], enc[1], 0, 1.0), Map.of(),
                List.of(new ExposureWhatIfMath.Candidate("S", "short", "", new double[] {1, 2, 3}, 0, 1.0)), GRID, P, ExposureMathTest.TIMES, 0.75,
                ExposureMath.Model.LAGGED, ExposureMath.Rule.KTH, "NSET:NS-X"))
                .hasMessage("the candidate short's paths are not of the run: 3 values, the run has 6 grid dates x 4 paths = 24");
    }

    private static double[] doubles(JsonNode a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).asDouble();
        }
        return out;
    }
}
