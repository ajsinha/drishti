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
package com.ash.drishti.banking.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.compute.linalg.Blas;
import com.ash.drishti.compute.linalg.JavaBlas;
import com.ash.drishti.compute.linalg.LinearAlgebra;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link ParametricVar} (docs/architecture/PARAMETRIC_VAR.md section 15): μ, σ, VaR and ES by hand and against BigDecimal; z and the ES
 * factor against tables; the sign of μ; σ = 0; EWMA weights; the shape; Euler components adding up to the VaR and ES to the cent (five desks,
 * 1,000 positions, μ ≠ 0, EWMA); incremental against a recomputation; the matrix through Java and OpenBLAS against plain loops; the curve.
 */
class ParametricVarTest {

    static final ParametricVar.Settings EQ = ParametricVar.Settings.EQUAL;

    @Test
    void aTenValueVectorByHand() {
        double[] v = {-120, 35, 60, -15, 80, -45, 10, 25, -70, 40};
        ParametricVar.Figures f = ParametricVar.of(v, 0.99, 0.975, EQ);
        // μ = 0, Σ(v−μ)² = 30,000 + ... computed by hand below
        double mu = 0;
        for (double x : v) {
            mu += x;
        }
        mu /= 10;
        double ss = 0;
        for (double x : v) {
            ss += (x - mu) * (x - mu);
        }
        double sigma = Math.sqrt(ss / 9);
        assertThat(f.fit().mu()).isEqualTo(mu);
        assertThat(f.fit().sigma()).isCloseTo(sigma, offset(1e-12));
        assertThat(f.var()).isCloseTo(2.3263478740408408 * sigma - mu, offset(1e-9));
        assertThat(f.es()).isCloseTo(sigma * 2.3378027922013 - mu, offset(1e-9));
        assertThat(f.zSigma()).isEqualTo(f.z() * f.fit().sigma());
    }

    @Test
    void zAndTheEsFactorAgainstTables() {
        assertThat(ParametricVar.inverse(0.99)).isCloseTo(2.3263478740408408, offset(1e-12));
        assertThat(ParametricVar.inverse(0.95)).isCloseTo(1.6448536269514722, offset(1e-12));
        assertThat(ParametricVar.inverse(0.975)).isCloseTo(1.959963984540054, offset(1e-12));
        assertThat(ParametricVar.inverse(0.995)).isCloseTo(2.5758293035489004, offset(1e-12));
        assertThat(ParametricVar.inverse(0.5)).isCloseTo(0, offset(1e-15));
        assertThat(ParametricVar.inverse(0.01)).isCloseTo(-2.3263478740408408, offset(1e-12));
        assertThat(ParametricVar.esFactor(0.975)).isCloseTo(2.3378027922013, offset(1e-11));
        assertThat(ParametricVar.cdf(1.959963984540054)).isCloseTo(0.975, offset(1e-15));
        assertThatThrownBy(() -> ParametricVar.inverse(1)).hasMessageContaining("probability");
    }

    @Test
    void againstBigDecimalOn509Values() {
        Random r = new Random(5);
        double[] v = new double[509];
        for (int j = 0; j < v.length; j++) {
            v[j] = r.nextGaussian() * 5e7 + 2e5 + (r.nextInt(50) == 0 ? -3e8 : 0);
        }
        MathContext mc = MathContext.DECIMAL128;
        BigDecimal sum = BigDecimal.ZERO;
        for (double x : v) {
            sum = sum.add(new BigDecimal(x), mc);
        }
        BigDecimal mu = sum.divide(BigDecimal.valueOf(v.length), mc);
        BigDecimal ss = BigDecimal.ZERO;
        for (double x : v) {
            BigDecimal d = new BigDecimal(x).subtract(mu, mc);
            ss = ss.add(d.multiply(d, mc), mc);
        }
        double sigma = ss.divide(BigDecimal.valueOf(v.length - 1), mc).sqrt(mc).doubleValue();
        ParametricVar.Fit f = ParametricVar.fit(v, EQ);
        assertThat(f.mu()).isCloseTo(mu.doubleValue(), offset(Math.abs(mu.doubleValue()) * 1e-9));
        assertThat(f.sigma()).isCloseTo(sigma, offset(sigma * 1e-9));
        assertThat(f.skew()).isLessThan(0);                                // the extreme losses skew it left
        assertThat(f.excessKurtosis()).isGreaterThan(0);
    }

    @Test
    void theSignOfMuAndAFlatVector() {
        double[] v = {1, -1, 2, -2, 3, -3};
        double[] up = new double[v.length];
        for (int j = 0; j < v.length; j++) {
            up[j] = v[j] + 10;
        }
        assertThat(ParametricVar.of(up, 0.99, 0.975, EQ).var()).isCloseTo(ParametricVar.of(v, 0.99, 0.975, EQ).var() - 10, offset(1e-12));
        ParametricVar.Figures flat = ParametricVar.of(new double[] {5, 5, 5}, 0.99, 0.975, EQ);
        assertThat(flat.fit().sigma()).isZero();
        assertThat(flat.var()).isEqualTo(-5);
        assertThat(flat.es()).isEqualTo(-5);
        assertThat(ParametricVar.component(new double[] {2, 2, 2}, new double[] {5, 5, 5}, flat).componentVar()).isEqualTo(-2);
    }

    @Test
    void ewmaWeightsSumToOneTheNewestTheLargest() {
        ParametricVar.Settings ew = new ParametricVar.Settings(ParametricVar.Weighting.EWMA, 0.94);
        double[] w = ParametricVar.weights(509, ew);
        double s = 0;
        for (double x : w) {
            s += x;
        }
        assertThat(s).isCloseTo(1, offset(1e-12));
        assertThat(w[508]).isCloseTo(0.06, offset(1e-12));
        assertThat(w[508]).isGreaterThan(w[507]);
        assertThatThrownBy(() -> new ParametricVar.Settings(ParametricVar.Weighting.EWMA, 1.2)).hasMessageContaining("(0.5, 1)");
        assertThat(ParametricVar.Weighting.of("EWMA")).isEqualTo(ParametricVar.Weighting.EWMA);
        assertThatThrownBy(() -> ParametricVar.Weighting.of("garch")).hasMessageContaining("equal or ewma");
    }

    @Test
    void theShapeOfAKnownSample() {
        double[] v = {1, 2, 3, 4, 100};
        ParametricVar.Fit f = ParametricVar.fit(v, EQ);
        double mu = 22;
        double m2 = 0;
        double m3 = 0;
        double m4 = 0;
        for (double x : v) {
            double d = x - mu;
            m2 += d * d / 5;
            m3 += d * d * d / 5;
            m4 += d * d * d * d / 5;
        }
        assertThat(f.skew()).isCloseTo(m3 / Math.pow(m2, 1.5), offset(1e-12));
        assertThat(f.excessKurtosis()).isCloseTo(m4 / (m2 * m2) - 3, offset(1e-12));
    }

    /** Parts partitioning a scope: Σ components = the scope's VaR and ES to the cent. */
    static void addsUp(int parts, ParametricVar.Settings s, double drift) {
        Random r = new Random(parts);
        int n = 509;
        List<double[]> ps = new ArrayList<>();
        double[] v = new double[n];
        double[] common = new double[n];
        for (int j = 0; j < n; j++) {
            common[j] = r.nextGaussian();
        }
        for (int i = 0; i < parts; i++) {
            double[] p = new double[n];
            double scale = Math.pow(10, 4 + r.nextInt(4));
            for (int j = 0; j < n; j++) {
                p[j] = Math.round((0.6 * common[j] + r.nextGaussian()) * scale + drift);
                v[j] += p[j];
            }
            ps.add(p);
        }
        ParametricVar.Figures f = ParametricVar.of(v, 0.99, 0.975, s);
        double cv = 0;
        double ce = 0;
        for (double[] p : ps) {
            ParametricVar.Component c = ParametricVar.component(p, v, f);
            cv += c.componentVar();
            ce += c.componentEs();
        }
        assertThat(Math.abs(cv - f.var())).as(parts + " parts, VaR").isLessThan(0.005);
        assertThat(Math.abs(ce - f.es())).as(parts + " parts, ES").isLessThan(0.005);
    }

    @Test
    void eulerComponentsAddUpToTheCent() {
        addsUp(5, EQ, 0);
        addsUp(5, EQ, 3_000);                                              // μ ≠ 0: the −μᵢ term
        addsUp(1_000, EQ, -150);
        addsUp(60, new ParametricVar.Settings(ParametricVar.Weighting.EWMA, 0.94), 500);
    }

    @Test
    void incrementalIsTheRecomputation() {
        double[] a = {10, -20, 30, -40, 50, 5};
        double[] b = {-3, 7, -11, 13, -17, 2};
        double[] v = new double[a.length];
        for (int j = 0; j < v.length; j++) {
            v[j] = a[j] + b[j];
        }
        ParametricVar.Figures f = ParametricVar.of(v, 0.99, 0.975, EQ);
        ParametricVar.Component c = ParametricVar.component(a, v, f);
        assertThat(c.incrementalVar()).isCloseTo(f.var() - ParametricVar.of(b, 0.99, 0.975, EQ).var(), offset(1e-9));
        assertThat(c.standaloneVar()).isCloseTo(ParametricVar.of(a, 0.99, 0.975, EQ).var(), offset(1e-9));
    }

    @Test
    void theMatrixOnJavaAndOpenBlasAgainstPlainLoops() {
        Random r = new Random(8);
        List<double[]> parts = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            double[] p = new double[509];
            for (int j = 0; j < p.length; j++) {
                p[j] = r.nextGaussian() * (i + 1) * 1e5;
            }
            parts.add(p);
        }
        List<LinearAlgebra> las = new ArrayList<>(List.of(JavaBlas.INSTANCE));
        LinearAlgebra nb = Blas.nativeOrNull();
        if (nb != null) {
            las.add(nb);
        }
        double[] v = new double[509];
        parts.forEach(p -> {
            for (int j = 0; j < v.length; j++) {
                v[j] += p[j];
            }
        });
        double sigma2 = Math.pow(ParametricVar.fit(v, EQ).sigma(), 2);
        for (LinearAlgebra la : las) {
            double[][][] m = ParametricVar.matrix(parts, EQ, la);
            double total = 0;
            for (int i = 0; i < 7; i++) {
                ParametricVar.Fit fi = ParametricVar.fit(parts.get(i), EQ);
                assertThat(m[1][i][i]).isEqualTo(1);
                for (int l = 0; l < 7; l++) {
                    double plain = ParametricVar.cov(parts.get(i), fi.mu(), parts.get(l), ParametricVar.fit(parts.get(l), EQ).mu(), null, EQ);
                    assertThat(m[0][i][l]).as(la.name()).isCloseTo(plain, offset(Math.abs(plain) * 1e-12 + 1e-3));
                    assertThat(m[0][i][l]).isEqualTo(m[0][l][i]);
                    total += m[0][i][l];
                }
            }
            assertThat(total).isCloseTo(sigma2, offset(sigma2 * 1e-12));
        }
    }

    @Test
    void theCurveSitsOnTheBarsScale() {
        Random r = new Random(2);
        double[] v = new double[509];
        for (int j = 0; j < v.length; j++) {
            v[j] = r.nextGaussian() * 1e6;
        }
        ParametricVar.Figures f = ParametricVar.of(v, 0.99, 0.975, EQ);
        VarCalc.Histogram h = VarCalc.histogram(v, 40);
        ParametricVar.Curve c = ParametricVar.curve(f, h.edges(), 121);
        double area = 0;
        for (double e : c.expected()) {
            area += e;
        }
        double mu = f.fit().mu();
        double s = f.fit().sigma();
        double[] edges = h.edges();
        assertThat(area).isCloseTo(509 * (ParametricVar.cdf((edges[40] - mu) / s) - ParametricVar.cdf((edges[0] - mu) / s)), offset(1e-9));
        assertThat(c.x()).hasSize(121);
        assertThat(c.x()[0]).isEqualTo(edges[0]);
        assertThat(c.x()[120]).isEqualTo(edges[40]);
        assertThat(c.binWidth()).isEqualTo(edges[1] - edges[0]);
        double peak = 509 * c.binWidth() / (s * Math.sqrt(2 * Math.PI));
        for (int i = 0; i < 121; i++) {
            assertThat(c.y()[i]).isLessThanOrEqualTo(peak + 1e-9);
        }
    }

    @Test
    void theTenDayLensScalesByRootTen() {
        Random r = new Random(4);
        double[] v = new double[509];
        double[] ten = new double[509];
        for (int j = 0; j < v.length; j++) {
            v[j] = r.nextGaussian() * 1e6 + 1e4;
            ten[j] = v[j] * Math.sqrt(10);
        }
        ParametricVar.Figures one = ParametricVar.of(v, 0.99, 0.975, EQ);
        ParametricVar.Figures tenDay = ParametricVar.of(ten, 0.99, 0.975, EQ);
        assertThat(tenDay.var()).isCloseTo(one.var() * Math.sqrt(10), offset(Math.abs(one.var()) * 1e-12));
    }
}
