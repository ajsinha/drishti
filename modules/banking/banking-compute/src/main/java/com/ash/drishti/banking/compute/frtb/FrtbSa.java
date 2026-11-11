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
package com.ash.drishti.banking.compute.frtb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The FRTB standardised approach of one scope (FRTB_SA_GUIDE.md): the sensitivities-based method, the default risk charge and the
 * residual risk add-on, with the method choices of {@link FrtbChoices}. {@code Total = SBM + DRC + RRAO}; a DRC or RRAO whose input was
 * not delivered is missing (the total then says it lacks them), never zero.
 */
public final class FrtbSa {

    /** The engine's version, recorded in every recipe. */
    public static final String ENGINE = "frtb-sa 1.0";

    /** One run. */
    public record Result(FrtbInput input, FrtbChoices choices, FrtbParams params, Sbm.Result sbm, Drc.Result drc, Rrao.Result rrao) {

        /** SBM + DRC + RRAO, the parts that were delivered. */
        public double total() {
            return sbm.sbm() + (drc.delivered() ? drc.total() : 0) + (rrao.delivered() ? rrao.total() : 0);
        }

        /** True when the DRC and RRAO inputs were both there. */
        public boolean complete() {
            return drc.delivered() && rrao.delivered();
        }

        /** Each position's contribution to the total (SBM by Euler in the reported scenario, DRC by obligor share, RRAO as is). */
        public double[] contributions() {
            int n = input.positions().size();
            double[] out = sbm.contributions(n);
            Map<String, Double> drcByLine = drc.contributionsByLine();
            for (Drc.Line l : drc.lines()) {
                if (l.in().position() >= 0 && l.in().position() < n) {
                    out[l.in().position()] += drcByLine.getOrDefault(l.in().id(), 0.0);
                }
            }
            for (Rrao.Line l : rrao.lines()) {
                if (l.in().position() >= 0 && l.in().position() < n) {
                    out[l.in().position()] += l.addOn();
                }
            }
            return out;
        }

        /** The contributions grouped by a position attribute ({@code DESK}, {@code TT_BOOK}, ...; {@code POSITION} by id). */
        public Map<String, Double> contributionsBy(String attribute) {
            double[] c = contributions();
            Map<String, Double> out = new LinkedHashMap<>();
            for (int i = 0; i < c.length; i++) {
                FrtbInput.Position p = input.positions().get(i);
                String k = attribute.equalsIgnoreCase("POSITION") ? p.id() : p.attributes().getOrDefault(attribute, "(none)");
                out.merge(k == null ? "(none)" : k, c[i], Double::sum);
            }
            return out;
        }
    }

    private FrtbSa() {}

    /** Runs FRTB-SA on {@code in} with the parameters (overrides already applied) and the choices. */
    public static Result run(FrtbInput in, FrtbParams params, FrtbChoices choices) {
        SbmModel model = new SbmModel(in, params, choices);
        Sbm.Result sbm = Sbm.compute(model);
        return new Result(in, choices, params, sbm, Drc.compute(in.drc(), params), Rrao.compute(in.rrao(), params));
    }
}
