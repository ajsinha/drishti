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

/**
 * A netting set's collateral terms in USD (EXP.md section 6.3, NSET.md section 3.5) and what it holds today.
 *
 * @param csaId the CSA, null when the set has none (unmargined: no collateral at all)
 * @param thThem the exposure we may have to the counterparty before they post
 * @param thUs the exposure they may have to us before we post
 * @param mtaThem their minimum transfer amount (shortens what we hold)
 * @param mtaUs ours
 * @param ia the independent amount in our favour (held +, posted -)
 * @param rounding the rounding unit of a transfer (0: none): deliveries rounded up, returns down
 * @param mporDays the margin period of risk the CSA assumes (business days); compared with the run's look-back
 * @param frequencyDays the remargining period (business days; above 1 the effective MPOR is longer, flagged)
 * @param margined the CSA calls variation margin; false: the set holds its IA only
 * @param heldToday the variation margin held today, net of what we posted (+ held, - posted)
 * @param imHeld the initial margin held today (kept on every path under {@code im-model: current})
 */
public record CsaTerms(String csaId, double thThem, double thUs, double mtaThem, double mtaUs, double ia, double rounding, int mporDays,
        int frequencyDays, boolean margined, double heldToday, double imHeld) {

    /** No CSA: nothing held, nothing called. */
    public static final CsaTerms NONE = new CsaTerms(null, 0, 0, 0, 0, 0, 0, 0, 1, false, 0, 0);

    public boolean present() {
        return csaId != null;
    }

    /** The collateral the CSA calls for at value v: {@code max(v - TH_them, 0) - max(-v - TH_us, 0) + IA}. */
    public double target(double v) {
        return Math.max(v - thThem, 0) - Math.max(-v - thUs, 0) + ia;
    }

    /**
     * What is held after a call at value v: the target, rounded as a change from today's collateral (deliveries up, returns down), the
     * held side shortened by the counterparty's minimum transfer amount.
     */
    public double called(double v) {
        double x = target(v);
        if (rounding > 0) {
            double delta = x - heldToday;
            delta = delta > 0 ? Math.ceil(delta / rounding) * rounding : -Math.floor(-delta / rounding) * rounding;
            x = heldToday + delta;
        }
        if (x > 0) {
            x = Math.max(x - mtaThem, 0);
        }
        return x;
    }

    /** The same terms holding {@code held} today (a partial or standalone scope keeps the CSA, not the set's holdings). */
    public CsaTerms holding(double held, double im) {
        return new CsaTerms(csaId, thThem, thUs, mtaThem, mtaUs, ia, rounding, mporDays, frequencyDays, margined, held, im);
    }
}
