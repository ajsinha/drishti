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

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FVA by hand (docs/architecture/COUNTERPARTY_ENGINES.md section 9, case F1 on case X1's profile, and the cross-check on CVA.md section 15's
 * profile): every figure to the cent, the terms of (b) and (c) to the cent, the EPE reading, the mid point, FCA only, linearity in the
 * profile and additivity over sets, and the curves' interpolation.
 */
class FvaMathTest {

    static final double M = 1_000_000;
    static final double[] T = {0.25, 0.5, 0.75, 1.0};
    static final double[] EE = {0.8 * M, 1.6 * M, 1.1 * M, 1.3 * M};
    static final double[] ENE = {0, 0, -0.4 * M, -0.3 * M};
    static final FvaMath.Profile X1 = new FvaMath.Profile(T, EE, ENE, 0.4 * M, 0);
    static final FvaMath.Curve FLAT50 = FvaMath.Curve.flat("flat", 0.005, "test");
    static final FvaMath.Curve DF4 = new FvaMath.Curve("df", List.of("1Y"), new double[] {1.0}, new double[] {Math.exp(-0.04)}, "test");

    static FvaMath.Result run(FvaMath.Profile p, FvaMath.Curve df, FvaMath.Curve spread, double[] survival) {
        FvaMath.Market m = FvaMath.market(p.t(), df, spread, null, null);
        if (survival != null) {
            m = new FvaMath.Market(m.df(), m.sb(), m.sl(), survival);
        }
        return FvaMath.compute(p, m, FvaMath.Point.END, true);
    }

    @Test
    void caseF1aFlatSpreadNoDiscountingAndTheEpeReading() {
        FvaMath.Result r = run(X1, null, FLAT50, null);
        assertThat(r.fca()).isCloseTo(-6_000.00, within(0.005));
        assertThat(r.fba()).isCloseTo(875.00, within(0.005));
        assertThat(r.fva()).isCloseTo(-5_125.00, within(0.005));
        assertThat(r.sumEeDt()).isCloseTo(1.2 * M, within(1e-6));
        assertThat(r.life()).isEqualTo(1.0);
        assertThat(r.epeLife()).isCloseTo(1.2 * M, within(1e-6));
        assertThat(0.005 * r.life() * r.epeLife()).isCloseTo(-r.fca(), within(0.005));    // FCA = − s · T_life · EPE_life
    }

    @Test
    void caseF1bDiscountedTermsToTheCent() {
        FvaMath.Result r = run(X1, DF4, FLAT50, null);
        assertThat(r.df()[0]).isCloseTo(0.990050, within(5e-7));
        assertThat(r.df()[3]).isCloseTo(0.960789, within(5e-7));
        double[] fca = {-990.05, -1_960.40, -1_334.36, -1_561.28};
        double[] fba = {0, 0, 485.22, 360.30};
        for (int i = 0; i < 4; i++) {
            assertThat(r.fcaTerm()[i]).isCloseTo(fca[i], within(0.005));
            assertThat(r.fbaTerm()[i]).isCloseTo(fba[i], within(0.005));
        }
        assertThat(r.fca()).isCloseTo(-5_846.09, within(0.005));
        assertThat(r.fba()).isCloseTo(845.52, within(0.005));
        assertThat(r.fva()).isCloseTo(-5_000.57, within(0.005));
    }

    @Test
    void caseF1cTermSpread() {
        FvaMath.Curve term = new FvaMath.Curve("term", List.of("6M", "9M"), new double[] {0.5, 0.75}, new double[] {0.004, 0.006}, "test");
        FvaMath.Result r = run(X1, DF4, term, null);
        double[] fca = {-792.04, -1_568.32, -1_601.24, -1_873.54};
        for (int i = 0; i < 4; i++) {
            assertThat(r.fcaTerm()[i]).isCloseTo(fca[i], within(0.005));
        }
        assertThat(r.fbaTerm()[2]).isCloseTo(582.27, within(0.005));
        assertThat(r.fbaTerm()[3]).isCloseTo(432.36, within(0.005));
        assertThat(r.fca()).isCloseTo(-5_835.13, within(0.005));
        assertThat(r.fba()).isCloseTo(1_014.62, within(0.005));
        assertThat(r.fva()).isCloseTo(-4_820.51, within(0.005));
    }

    @Test
    void caseF1dSurvivalWeighting() {
        double[] s = FvaMath.flatSurvival(T, 0.02, 0.01);
        assertThat(s[0]).isCloseTo(0.992528, within(5e-7));
        assertThat(s[3]).isCloseTo(0.970446, within(5e-7));
        FvaMath.Result r = run(X1, DF4, FLAT50, s);
        assertThat(r.fca()).isCloseTo(-5_733.68, within(0.005));
        assertThat(r.fba()).isCloseTo(824.07, within(0.005));
        assertThat(r.fva()).isCloseTo(-4_909.60, within(0.005));
    }

    @Test
    void crossCheckOnTheCvaProfile() {
        FvaMath.Profile p = new FvaMath.Profile(new double[] {0.25, 0.5, 1.0}, new double[] {1.0 * M, 1.5 * M, 0.75 * M},
                new double[] {-0.25 * M, -0.5 * M, -0.75 * M}, 0, 0);
        FvaMath.Result r = run(p, null, FLAT50, null);
        assertThat(r.dt()).containsExactly(0.25, 0.25, 0.5);
        assertThat(r.fca()).isCloseTo(-5_000.00, within(0.005));
        assertThat(r.fba()).isCloseTo(2_812.50, within(0.005));
        assertThat(r.fva()).isCloseTo(-2_187.50, within(0.005));
    }

    @Test
    void midPointAveragesBothEndsFromTheCurrentExposure() {
        FvaMath.Market m = FvaMath.market(T, null, FLAT50, null, null);
        FvaMath.Result r = FvaMath.compute(X1, m, FvaMath.Point.MID, true);
        // EE points: (0.4+0.8)/2, (0.8+1.6)/2, (1.6+1.1)/2, (1.1+1.3)/2 = 0.6, 1.2, 1.35, 1.2 → Σ 4.35 × 0.25 × 0.005 = 5,437.50
        assertThat(r.fca()).isCloseTo(-5_437.50, within(0.005));
        // |ENE| points: 0, 0, 0.2, 0.35 → 0.55 × 0.25 × 0.005 = 687.50
        assertThat(r.fba()).isCloseTo(687.50, within(0.005));
    }

    @Test
    void fcaOnlyAndALendingSpreadOfItsOwn() {
        FvaMath.Result only = FvaMath.compute(X1, FvaMath.market(T, null, FLAT50, null, null), FvaMath.Point.END, false);
        assertThat(only.fba()).isZero();
        assertThat(only.fva()).isCloseTo(-6_000.00, within(0.005));
        FvaMath.Curve lend = FvaMath.Curve.flat("lend", 0.003, "test");
        FvaMath.Result asym = FvaMath.compute(X1, FvaMath.market(T, null, FLAT50, lend, null), FvaMath.Point.END, true);
        assertThat(asym.fba()).isCloseTo(525.00, within(0.005));                          // 0.7m × 0.25 × 30 bp
        assertThat(asym.fca()).isCloseTo(-6_000.00, within(0.005));
    }

    @Test
    void linearInTheProfileAndAdditiveOverSets() {
        FvaMath.Result one = run(X1, DF4, FLAT50, null);
        double[] ee2 = EE.clone();
        double[] ene2 = ENE.clone();
        for (int i = 0; i < 4; i++) {
            ee2[i] *= 2;
            ene2[i] *= 2;
        }
        FvaMath.Result two = run(new FvaMath.Profile(T, ee2, ene2, 0.8 * M, 0), DF4, FLAT50, null);
        assertThat(two.fva()).isCloseTo(2 * one.fva(), within(1e-6));
        FvaMath.Profile other = new FvaMath.Profile(T, new double[] {0.1 * M, 0.2 * M, 0.3 * M, 0.4 * M}, new double[] {-0.5 * M, 0, 0, -0.1 * M}, 0, 0);
        FvaMath.Result b = run(other, DF4, FLAT50, null);
        FvaMath.Result sum = FvaMath.sum(List.of(one, b));
        double[] ee = new double[4];
        double[] ene = new double[4];
        for (int i = 0; i < 4; i++) {
            ee[i] = EE[i] + other.ee()[i];
            ene[i] = ENE[i] + other.ene()[i];
        }
        FvaMath.Result together = run(new FvaMath.Profile(T, ee, ene, 0, 0), DF4, FLAT50, null);
        assertThat(sum.fca()).isCloseTo(together.fca(), within(1e-6));
        assertThat(sum.fva()).isCloseTo(one.fva() + b.fva(), within(1e-9));
        assertThat(sum.fba()).isCloseTo(together.fba(), within(1e-6));                // ENE is never positive: |Σ ENE| = Σ |ENE|
    }

    @Test
    void curvesInterpolateAndTenorsRead() {
        double[] t = {0.5, 1.0, 2.0};
        double[] v = {0.004, 0.005, 0.007};
        assertThat(FvaMath.linear(t, v, 0.1)).isEqualTo(0.004);
        assertThat(FvaMath.linear(t, v, 1.5)).isCloseTo(0.006, within(1e-15));
        assertThat(FvaMath.linear(t, v, 9)).isEqualTo(0.007);
        double[] df = {Math.exp(-0.02), Math.exp(-0.05)};
        double[] dt = {1.0, 2.0};
        assertThat(FvaMath.logLinear(dt, df, 0.5)).isCloseTo(Math.exp(-0.01), within(1e-15));
        assertThat(FvaMath.logLinear(dt, df, 1.5)).isCloseTo(Math.exp(-0.035), within(1e-15));
        assertThat(FvaMath.logLinear(dt, df, 3.0)).isCloseTo(Math.exp(-0.08), within(1e-14));   // the last segment's 3% goes on
        assertThat(FvaMath.tenorYears("6M")).isEqualTo(0.5);
        assertThat(FvaMath.tenorYears("2Y")).isEqualTo(2.0);
        assertThat(FvaMath.tenorYears("0.75")).isEqualTo(0.75);
        assertThatThrownBy(() -> FvaMath.tenorYears("6Q")).isInstanceOf(IllegalArgumentException.class);
        assertThat(FvaMath.Point.of("MID")).isEqualTo(FvaMath.Point.MID);
        assertThatThrownBy(() -> FvaMath.Point.of("start")).isInstanceOf(IllegalArgumentException.class);
    }
}
