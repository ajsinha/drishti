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
package com.ash.drishti.banking.compute.simm;

/**
 * The aggregation formulas of ISDA SIMM (SENSITIVITIES_AND_FRTB_SA.md sections 4 and 8, docs/guides/SIMM_GUIDE.md section 5): the
 * concentration factor, the bucket's K with f_kl, the capped bucket net, the sum across buckets with g_bc and the residual bucket outside
 * the root, the curvature scaling SF(t), θ and λ, and the product class's margin with ψ. {@link Simm} computes every figure through these;
 * the design's hand-built cases 6 and 11 prove them with toy parameters.
 */
public final class SimmMath {

    private SimmMath() {}

    /** The concentration factor {@code CR = max(1, sqrt(|Σ s| / T))} of a net sensitivity against its threshold. */
    public static double concentration(double netSensitivity, double threshold) {
        return Math.max(1, Math.sqrt(Math.abs(netSensitivity) / threshold));
    }

    /**
     * The bucket's K: {@code sqrt(Σ WS² + Σ_{k≠l} ρ_kl · f_kl · WS_k · WS_l)}, {@code f_kl = min(CR_k, CR_l) / max(CR_k, CR_l)} (1 for
     * interest rates, {@code interestRate} true).
     */
    public static double bucketK(double[] ws, double[] cr, double[][] rho, boolean interestRate) {
        double sum = 0;
        for (int k = 0; k < ws.length; k++) {
            sum += ws[k] * ws[k];
            for (int l = 0; l < ws.length; l++) {
                if (l != k) {
                    double f = interestRate ? 1 : Math.min(cr[k], cr[l]) / Math.max(cr[k], cr[l]);
                    sum += rho[k][l] * f * ws[k] * ws[l];
                }
            }
        }
        return Math.sqrt(Math.max(0, sum));
    }

    /** A product class's margin from its risk classes' {@code IM_r}: {@code sqrt(Σ IM_r² + Σ_{r≠s} ψ_rs · IM_r · IM_s)}. */
    public static double productClass(double[] im, double[][] psi) {
        double sum = 0;
        for (int r = 0; r < im.length; r++) {
            for (int s = 0; s < im.length; s++) {
                sum += (r == s ? 1 : psi[r][s]) * im[r] * im[s];
            }
        }
        return Math.sqrt(Math.max(0, sum));
    }

    /** The capped bucket net {@code S_b = max(min(Σ WS, K_b), −K_b)} (SIMM always caps; paragraphs 7(d), 8(d)). */
    public static double capped(double net, double k) {
        return Math.max(Math.min(net, k), -k);
    }

    /**
     * Across buckets: {@code sqrt(max(0, Σ K_b² + Σ_{b≠c} γ_bc · g_bc · S_b · S_c))} with {@code S} already capped; {@code g} null for 1.
     */
    public static double acrossBuckets(double[] k, double[] s, double[][] gamma, double[][] g) {
        double sum = 0;
        for (int b = 0; b < k.length; b++) {
            sum += k[b] * k[b];
            for (int c = 0; c < k.length; c++) {
                if (c != b) {
                    sum += gamma[b][c] * (g == null ? 1 : g[b][c]) * s[b] * s[c];
                }
            }
        }
        return Math.sqrt(Math.max(0, sum));
    }

    /** {@code g_bc = min(CR_b, CR_c) / max(CR_b, CR_c)} (also f_kl). */
    public static double ratio(double a, double b) {
        return Math.min(a, b) / Math.max(a, b);
    }

    /** The curvature scaling {@code SF(t) = 0.5 · min(1, 14 / t_days)} of an expiry in years (365 days a year). */
    public static double scaling(double years) {
        return 0.5 * Math.min(1, 14 / (years * 365));
    }

    /** {@code θ = min(Σ CVR / Σ |CVR|, 0)}; 0 when every CVR is 0. */
    public static double theta(double sum, double sumAbs) {
        return sumAbs == 0 ? 0 : Math.min(sum / sumAbs, 0);
    }

    /** {@code λ = (Φ⁻¹(99.5 %)² − 1) · (1 + θ) − θ}. */
    public static double lambda(double theta) {
        return (SimmParams.PHI995 * SimmParams.PHI995 - 1) * (1 + theta) - theta;
    }

    /** The curvature margin of one side: {@code max(Σ CVR + λ · K, 0)}. */
    public static double curvature(double sum, double lambda, double k) {
        return Math.max(sum + lambda * k, 0);
    }
}
