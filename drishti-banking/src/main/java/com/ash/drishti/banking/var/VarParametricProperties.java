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
package com.ash.drishti.banking.var;

import com.ash.drishti.banking.compute.var.ParametricVar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametric VaR, a cross-check beside historical (docs/architecture/PARAMETRIC_VAR.md section 5; VAR_GUIDE.md section 18.15).
 *
 * @param enabled the measure (off: no parametric block, no PVAR alias, no playground Parametric tab, no pvar(); asked: refused in words)
 * @param weighting {@code equal} or {@code ewma}; anything else refuses the start
 * @param lambda EWMA's decay, in (0.5, 1)
 * @param horizonRule {@code lens} (the lens's own vector) or {@code square-root-of-time} (μ·h, σ·√h from the 1-day vector)
 * @param curvePoints the normal curve's points on the P&amp;L path (21 to 1,001)
 * @param matrixMaxParts most parts of a correlation matrix
 * @param showCurve the Normal curve toggle starts on
 */
@ConfigurationProperties("drishti.var.parametric")
public record VarParametricProperties(Boolean enabled, String weighting, Double lambda, String horizonRule, Integer curvePoints,
        Integer matrixMaxParts, Boolean showCurve) {

    public VarParametricProperties {
        enabled = enabled == null || enabled;
        weighting = ParametricVar.Weighting.of(weighting).text();
        lambda = lambda == null ? 0.94 : lambda;
        new ParametricVar.Settings(ParametricVar.Weighting.of(weighting), lambda);       // a bad lambda refuses the start, in words
        horizonRule = horizonRule == null || horizonRule.isBlank() ? "lens" : horizonRule.strip().toLowerCase(Locale.ROOT);
        if (!horizonRule.equals("lens") && !horizonRule.equals("square-root-of-time")) {
            throw new IllegalArgumentException("drishti.var.parametric.horizon-rule is lens or square-root-of-time, not " + horizonRule);
        }
        curvePoints = curvePoints == null ? 121 : Math.max(21, Math.min(1001, curvePoints));
        matrixMaxParts = matrixMaxParts == null || matrixMaxParts < 2 ? 60 : matrixMaxParts;
        showCurve = showCurve == null || showCurve;
    }

    /** The defaults (tests). */
    static VarParametricProperties defaults() {
        return new VarParametricProperties(null, null, null, null, null, null, null);
    }

    ParametricVar.Settings settings() {
        return new ParametricVar.Settings(ParametricVar.Weighting.of(weighting), lambda);
    }

    /** {@code GET /api/v1/risk/var/status}'s {@code parametric}. */
    Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("weighting", weighting);
        m.put("lambda", weighting.equals("ewma") ? lambda : null);
        m.put("horizonRule", horizonRule);
        m.put("curvePoints", curvePoints);
        m.put("matrixMaxParts", matrixMaxParts);
        m.put("showCurve", showCurve);
        m.put("official", false);
        return m;
    }
}
