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

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The exposure measures by hand: EXP.md section 15's worked example exactly (EE 1.0, 1.5, 0.75; EPE 1.0; EEPE 1.375; PFE 75% 1, 2, 1
 * and its peak 2 at t2, 4 without collateral; EAD 1.925; the uncollateralised EE contributions 2.0 and 0.25), the collateral rules
 * (thresholds, minimum transfer, independent amount, rounding, today's collateral where there is no look-back point, the unmargined and
 * current models), the quantile rules, the horizon cut inside an interval, and a set's trade shares adding up to its exposure.
 */
class ExposureMathTest {

    static final LocalDate BD = LocalDate.of(2026, 1, 2);
    /** Three look-back points and three primary points, interleaved: L1 P1 L2 P2 L3 P3. */
    static final ExposureGrid GRID = ExposureGrid.of(BD, List.of(BD.plusDays(80), BD.plusDays(91), BD.plusDays(170), BD.plusDays(182),
            BD.plusDays(350), BD.plusDays(365)), List.of("LOOKBACK", "PRIMARY", "LOOKBACK", "PRIMARY", "LOOKBACK", "PRIMARY"), List.of(1, -1, 3, -1, 5, -1),
            null);
    static final double[] TIMES = {0.25, 0.5, 1.0};
    static final int P = 4;
    // the worked example: trades A and B on 4 paths at t1, t2, t3; the look-back values L of the set
    static final double[][] A = {{3, -2, 3, 0}, {5, -1, 4, -2}, {2, -3, 6, -1}};
    static final double[][] B = {{-1, 1, -2, 1}, {-1, 2, 0, 0}, {1, 1, -1, -2}};
    static final double[][] L = {{1, -1, 0, 1}, {2, 0, 3, -1}, {3, -1, 4, -3}};

    /** A trade's (or the set's) G x P matrix: the look-back rows, then the primary rows, in grid order. */
    static double[] matrix(double[][] look, double[][] primary) {
        double[] m = new double[6 * P];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(look[i], 0, m, (2 * i) * P, P);
            System.arraycopy(primary[i], 0, m, (2 * i + 1) * P, P);
        }
        return m;
    }

    static double[][] sum(double[][] a, double[][] b) {
        double[][] out = new double[3][P];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < P; j++) {
                out[i][j] = a[i][j] + b[i][j];
            }
        }
        return out;
    }

    static final CsaTerms CSA = new CsaTerms("CSA-X", 1, 1, 0, 0, 0, 0, 10, 1, true, 0, 0);

    @Test
    void theWorkedExampleOfTheContract() {
        double[] v = matrix(L, sum(A, B));
        double[][] enc = ExposureMath.exposure(v, GRID, P, CSA, ExposureMath.Model.LAGGED);
        assertThat(enc[2]).containsExactly(0, 0, 0, 0, 1, 0, 2, 0, 2, 0, 3, -2);              // C = max(L - 1, 0) - max(-L - 1, 0)
        assertThat(enc[0]).containsExactly(2, 0, 1, 1, 3, 1, 2, 0, 1, 0, 2, 0);               // E = max(V - C, 0)
        assertThat(enc[1]).containsExactly(0, -1, 0, 0, 0, 0, 0, -2, 0, -2, 0, -1);           // N = min(V - C, 0)
        ExposureMath.Stats s = ExposureMath.statistics(enc[0], enc[1], ExposureMath.positive(v, GRID, P), TIMES, P, 1.0, new double[] {0.75, 0.95},
                0, ExposureMath.Rule.KTH);
        assertThat(s.ee).containsExactly(1.0, 1.5, 0.75);
        assertThat(s.ene).containsExactly(-0.25, -0.5, -0.75);
        assertThat(s.eee).containsExactly(1.0, 1.5, 1.5);
        assertThat(s.pfe[0]).containsExactly(1, 2, 1);                                      // the 3rd smallest of 4
        assertThat(s.pfe[1]).containsExactly(2, 3, 2);                                      // the 4th: the largest
        assertThat(s.eeUncoll).containsExactly(1.0, 2.25, 2.0);
        assertThat(s.epe).isCloseTo(1.0, within(1e-12));
        assertThat(s.eepe).isCloseTo(1.375, within(1e-12));                                  // EEPE > EPE: the running max keeps 1.5
        assertThat(s.peakPfe[0]).isEqualTo(2);
        assertThat(s.peakAt[0]).isEqualTo(1);                                                // at t2
        assertThat(1.4 * s.eepe).isCloseTo(1.925, within(1e-12));                            // EAD_IMM, informational
        ExposureMath.Stats none = ExposureMath.statistics(ExposureMath.positive(v, GRID, P), new double[12], null, TIMES, P, 1.0, new double[] {0.75},
                0, ExposureMath.Rule.KTH);
        assertThat(none.peakPfe[0]).isEqualTo(4);                                           // without collateral: 0, 1, 4, 4 at t2
        // the uncollateralised EE contributions at t2: A 2.0, B 0.25, adding up to 2.25
        double[] pos = ExposureMath.positive(v, GRID, P);
        double[] zero = new double[12];
        double[] a = ExposureMath.tradeShare(matrix(new double[3][P], A), v, pos, zero, 0, GRID, P);
        double[] b = ExposureMath.tradeShare(matrix(new double[3][P], B), v, pos, zero, 0, GRID, P);
        assertThat(ExposureMath.mean(a, 1, P)).isEqualTo(2.0);
        assertThat(ExposureMath.mean(b, 1, P)).isEqualTo(0.25);
    }

    @Test
    void tradeSharesAddUpToTheSetsExposureWithCollateral() {
        double[] v = matrix(L, sum(A, B));
        double[][] enc = ExposureMath.exposure(v, GRID, P, CSA.holding(0, 0.5), ExposureMath.Model.LAGGED);
        double[] a = ExposureMath.tradeShare(matrix(L, A), v, enc[0], enc[2], 0.5, GRID, P);
        double[] b = ExposureMath.tradeShare(matrix(new double[3][P], B), v, enc[0], enc[2], 0.5, GRID, P);
        for (int i = 0; i < 12; i++) {
            assertThat(a[i] + b[i]).isCloseTo(enc[0][i], within(1e-12));
        }
    }

    @Test
    void collateralRules() {
        CsaTerms c = new CsaTerms("CSA-1", 10, 5, 2, 3, 1, 0, 10, 1, true, 0, 0);
        assertThat(c.target(25)).isEqualTo(15 + 1);                                         // above their threshold, plus IA
        assertThat(c.target(-8)).isEqualTo(-3 + 1);                                          // we post beyond our threshold
        assertThat(c.called(25)).isEqualTo(16 - 2);                                          // held side shortened by their MTA
        assertThat(c.called(10.5)).isEqualTo(0);                                             // 1.5 held minus MTA 2: nothing
        assertThat(c.called(-8)).isEqualTo(-2);                                              // the posted side is not shortened
        CsaTerms r = new CsaTerms("CSA-2", 0, 0, 0, 0, 0, 10_000, 10, 1, true, 100_000, 0);
        assertThat(r.called(123_456)).isEqualTo(130_000);                                    // a delivery rounded up
        assertThat(r.called(83_000)).isEqualTo(90_000);                                      // a return of 17,000 rounded down to 10,000
        // no look-back point (1W on a 10-day margin period): today's collateral on every path
        ExposureGrid g = ExposureGrid.of(BD, List.of(BD.plusDays(7), BD.plusDays(30), BD.plusDays(44)), List.of("PRIMARY", "LOOKBACK", "PRIMARY"),
                List.of(-1, 2, -1), null);
        double[] v = {50, 60, 20, 30, 40, 45};                                             // P = 2: rows 1W, L, 6W
        CsaTerms held = new CsaTerms("CSA-3", 0, 0, 0, 0, 0, 0, 10, 1, true, 35, 0);
        double[][] enc = ExposureMath.exposure(v, g, 2, held, ExposureMath.Model.LAGGED);
        assertThat(enc[2]).containsExactly(35, 35, 20, 30);
        assertThat(enc[0]).containsExactly(15, 25, 20, 15);
        assertThat(ExposureMath.exposure(v, g, 2, held, ExposureMath.Model.CURRENT)[2]).containsExactly(35, 35, 35, 35);
        assertThat(ExposureMath.exposure(v, g, 2, held, ExposureMath.Model.NONE)[0]).containsExactly(50, 60, 40, 45);
        CsaTerms unmargined = new CsaTerms("CSA-4", 0, 0, 0, 0, 5, 0, 10, 1, false, 999, 0);
        assertThat(ExposureMath.exposure(v, g, 2, unmargined, ExposureMath.Model.LAGGED)[2]).containsExactly(5, 5, 5, 5);
        assertThat(ExposureMath.exposure(v, g, 2, CsaTerms.NONE, ExposureMath.Model.LAGGED)[2]).containsExactly(0, 0, 0, 0);
        CsaTerms im = held.holding(35, 10);
        assertThat(ExposureMath.exposure(v, g, 2, im, ExposureMath.Model.LAGGED)[0]).containsExactly(5, 15, 10, 5);
        assertThat(ExposureMath.currentExposure(100, im, ExposureMath.Model.LAGGED)).isEqualTo(55);
        assertThat(ExposureMath.currentExposure(100, im, ExposureMath.Model.NONE)).isEqualTo(100);
    }

    @Test
    void quantilesAndTheHorizon() {
        double[] s = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        assertThat(ExposureMath.rank(10, 0.95)).isEqualTo(10);
        assertThat(ExposureMath.rank(200, 0.95)).isEqualTo(190);
        assertThat(ExposureMath.rank(200, 0.99)).isEqualTo(198);
        assertThat(ExposureMath.quantileSorted(s, 0.75, ExposureMath.Rule.KTH)).isEqualTo(7);
        assertThat(ExposureMath.quantileSorted(s, 0.75, ExposureMath.Rule.INTERPOLATED)).isCloseTo(6.5, within(1e-12));
        // T = 0.4: EE 2 over [0, 0.25], EE 4 over [0.25, 0.4]: (0.5 + 0.6) / 0.4
        double[] e = {2, 2, 4, 4, 8, 8};
        ExposureMath.Stats st = ExposureMath.statistics(e, new double[6], null, new double[] {0.25, 0.5, 1.0}, 2, 0.4, new double[] {0.95}, 0,
                ExposureMath.Rule.KTH);
        assertThat(st.epe).isCloseTo((2 * 0.25 + 4 * 0.15) / 0.4, within(1e-12));
        assertThat(st.peakEe).isEqualTo(8);
        assertThat(ExposureMath.statistics(e, new double[6], null, new double[] {0.25, 0.5, 1.0}, 2, 0, new double[] {0.95}, 0,
                ExposureMath.Rule.KTH).epe).isZero();
        // EEE starts from the current exposure
        assertThat(ExposureMath.statistics(e, new double[6], null, new double[] {0.25, 0.5, 1.0}, 2, 1.0, new double[] {0.95}, 5,
                ExposureMath.Rule.KTH).eee).containsExactly(5, 5, 8);
    }

    @Test
    void aGridThatIsNotOneRunIsRefused() {
        assertThatThrownBy(() -> ExposureGrid.of(BD, List.of(BD.plusDays(2), BD.plusDays(1)), List.of("PRIMARY", "PRIMARY"), List.of(-1, -1), null))
                .hasMessageContaining("strictly increasing");
        assertThatThrownBy(() -> ExposureGrid.of(BD, List.of(BD), List.of("PRIMARY"), List.of(-1), null)).hasMessageContaining("not after");
        assertThatThrownBy(() -> ExposureGrid.of(BD, List.of(BD.plusDays(1), BD.plusDays(2)), List.of("LOOKBACK", "LOOKBACK"), List.of(1, -1), null))
                .hasMessageContaining("not a later primary");
        assertThatThrownBy(() -> ExposureGrid.of(BD, List.of(BD.plusDays(1)), List.of("PRIMARY", "PRIMARY"), List.of(-1), null))
                .hasMessageContaining("one per date");
        assertThat(ExposureGrid.of(BD, List.of(BD.plusDays(7), BD.plusDays(30)), List.of("PRIMARY", "PRIMARY"), List.of(-1, -1), null).tenor(1))
                .isEqualTo("1M");
    }
}
