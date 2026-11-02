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
package com.ash.drishti.compute.simm;

/**
 * The aggregation formulas ISDA SIMM shares with FRTB-SA, with SIMM's concentration factors (SENSITIVITIES_AND_FRTB_SA.md sections 4 and
 * 8): the formulas only, proved by the design's hand-built cases 6 and 11 with toy parameters. The SIMM engine (its parameter files,
 * risk classes, vega and curvature, product classes and add-ons) is built on these with the SIMM playground (2.9.11 row 4).
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
}
