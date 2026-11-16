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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The residual risk add-on (FRTB_SA_GUIDE.md section 6; MAR23): the gross notional of each instrument with an exotic underlying times
 * 1.0%, of each instrument bearing other residual risks times 0.1%; a back-to-back trade excluded entirely, a listed or centrally cleared
 * one only from the other-residual part (MAR23.7). Additive: each instrument's add-on is its contribution.
 */
public final class Rrao {

    /** One instrument: its class ({@code EXOTIC}, {@code OTHER} or {@code NONE}), weight and add-on, and why it is excluded (or null). */
    public record Line(FrtbInput.RraoItem in, String rraoClass, double weight, double addOn, String excluded) {}

    /** The RRAO of a scope; {@code delivered} false when no residual-risk input exists (missing, never 0). */
    public record Result(boolean delivered, List<Line> lines, double exotic, double other, double total) {}

    private Rrao() {}

    public static Result missing() {
        return new Result(false, List.of(), Double.NaN, Double.NaN, Double.NaN);
    }

    public static Result compute(List<FrtbInput.RraoItem> items, FrtbParams p) {
        if (items == null) {
            return missing();
        }
        List<Line> lines = new ArrayList<>();
        double exotic = 0;
        double other = 0;
        for (FrtbInput.RraoItem it : items) {
            String cls = it.rraoClass() != null && !it.rraoClass().isBlank() ? it.rraoClass().strip().toUpperCase(Locale.ROOT)
                    : p.rraoTypes.getOrDefault(it.productType() == null ? "" : it.productType().strip().toUpperCase(Locale.ROOT), "NONE");
            double w = cls.equals("EXOTIC") ? p.rraoExotic : cls.equals("OTHER") ? p.rraoOther : 0;
            String excluded = null;
            if (it.backToBack()) {
                excluded = "back-to-back (MAR23.7)";
                w = 0;
            } else if (it.listedOrCleared() && cls.equals("OTHER")) {
                excluded = "listed or centrally cleared (MAR23.7)";
                w = 0;
            }
            double add = w * Math.abs(it.grossNotional());
            if (cls.equals("EXOTIC")) {
                exotic += add;
            } else {
                other += add;
            }
            if (!cls.equals("NONE")) {
                lines.add(new Line(it, cls, w, add, excluded));
            }
        }
        return new Result(true, List.copyOf(lines), exotic, other, exotic + other);
    }
}
