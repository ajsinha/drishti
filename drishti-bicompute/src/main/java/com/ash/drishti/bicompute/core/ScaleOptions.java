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
package com.ash.drishti.bicompute.core;

import java.util.Locale;
import java.util.Map;

/**
 * How a request wants scale handled (PIVOT_ENGINE.md sections 5.3 and 9.8), beside the view (a view is what is shown; these are how it is
 * computed, so a saved view never carries them):
 *
 * @param exactness {@code AUTO}: distinct counts and percentiles exact on small data, ≈ (sketches) above
 *     {@code drishti.pivot.sketches.approximate-above-rows}; {@code EXACT}: "exact on request", every sketch replaced by its exact form;
 *     {@code APPROXIMATE}: every exact distinct count and percentile answered by its sketch
 * @param sampleFraction above 0 and below 1: the answer is computed from a sample of this share of the rows (the first, ≈ answer of a
 *     progressive pair); never cached, never for holistic measures
 * @param progressive the caller accepts a ≈ answer first when the view is estimated over {@code progressive.after-ms}
 */
public record ScaleOptions(Exactness exactness, double sampleFraction, boolean progressive) {

    /** Exact, approximate or chosen by size. */
    public enum Exactness { AUTO, EXACT, APPROXIMATE }

    /** The default: exactness by size, no sample, progressive accepted. */
    public static final ScaleOptions AUTO = new ScaleOptions(Exactness.AUTO, 0, true);

    public ScaleOptions {
        exactness = exactness == null ? Exactness.AUTO : exactness;
        sampleFraction = Double.isNaN(sampleFraction) || sampleFraction <= 0 || sampleFraction >= 1 ? 0 : sampleFraction;
    }

    /** True when this computation reads a sample. */
    public boolean sampled() {
        return sampleFraction > 0;
    }

    /** The same options on a sample of {@code f} of the rows. */
    public ScaleOptions sample(double f) {
        return new ScaleOptions(exactness, f, progressive);
    }

    /**
     * From a request's map ({@code "exact": true | false | "auto"}, {@code "progressive": false}); absent keys keep the default.
     */
    public static ScaleOptions parse(Map<String, Object> m) {
        if (m == null) {
            return AUTO;
        }
        Object e = m.get("exact");
        Exactness x = Exactness.AUTO;
        if (e instanceof Boolean b) {
            x = b ? Exactness.EXACT : Exactness.APPROXIMATE;
        } else if (e != null) {
            String s = String.valueOf(e).strip().toLowerCase(Locale.ROOT);
            x = switch (s) {
                case "true", "exact", "yes" -> Exactness.EXACT;
                case "false", "approximate", "approx", "no" -> Exactness.APPROXIMATE;
                default -> Exactness.AUTO;
            };
        }
        Object p = m.get("progressive");
        boolean prog = p == null || !(p instanceof Boolean b ? !b : "false".equalsIgnoreCase(String.valueOf(p)));
        return new ScaleOptions(x, 0, prog);
    }
}
