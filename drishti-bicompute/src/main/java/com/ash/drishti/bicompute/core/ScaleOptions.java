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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How a request wants scale handled (HOW_IT_FITS.md sections A26.3, 26.6), beside the view (a view is what is shown; these are how it
 * is computed, so a saved view never carries them). The pivot screen shows each as a switch with its state (BI_GUIDE.md, the screen's
 * switches): Exact / Auto / ≈, Progressive, Use rollups.
 *
 * @param exactness {@code AUTO}: distinct counts and percentiles exact on small data, ≈ (sketches) above
 *     {@code drishti.pivot.sketches.approximate-above-rows}; {@code EXACT}: "exact on request", every sketch replaced by its exact form;
 *     {@code APPROXIMATE}: every exact distinct count and percentile answered by its sketch, and a level's top N by a count chosen by a
 *     Space-Saving summary
 * @param sampleFraction above 0 and below 1: the answer is computed from a sample of this share of the rows (the first, ≈ answer of a
 *     progressive pair); never cached, never for holistic measures
 * @param progressive the caller accepts a ≈ answer first when the view is estimated over {@code progressive.after-ms}
 * @param rollups the caller lets a covering rollup answer (the default); false: every set is read from the source (aggregate awareness
 *     skipped, and the answer says so)
 */
public record ScaleOptions(Exactness exactness, double sampleFraction, boolean progressive, boolean rollups) {

    /** Exact, approximate or chosen by size. */
    public enum Exactness { AUTO, EXACT, APPROXIMATE }

    /** The default: exactness by size, no sample, progressive accepted, rollups used. */
    public static final ScaleOptions AUTO = new ScaleOptions(Exactness.AUTO, 0, true, true);

    public ScaleOptions {
        exactness = exactness == null ? Exactness.AUTO : exactness;
        sampleFraction = Double.isNaN(sampleFraction) || sampleFraction <= 0 || sampleFraction >= 1 ? 0 : sampleFraction;
    }

    /** With rollups used. */
    public ScaleOptions(Exactness exactness, double sampleFraction, boolean progressive) {
        this(exactness, sampleFraction, progressive, true);
    }

    /** True when this computation reads a sample. */
    public boolean sampled() {
        return sampleFraction > 0;
    }

    /** True when the person asked ≈ (not auto, not exact). */
    public boolean approximate() {
        return exactness == Exactness.APPROXIMATE;
    }

    /** The same options on a sample of {@code f} of the rows. */
    public ScaleOptions sample(double f) {
        return new ScaleOptions(exactness, f, progressive, rollups);
    }

    /**
     * The options of a live pivot's recompute (HOW_IT_FITS.md section A26.7, item 5): never a sample, never progressive, and exact unless the
     * view asked ≈ (a live figure is never an estimate the person did not ask for).
     */
    public ScaleOptions live() {
        return new ScaleOptions(approximate() ? Exactness.APPROXIMATE : Exactness.EXACT, 0, false, rollups);
    }

    /** The word the screen and the API show: {@code exact}, {@code auto} or {@code approximate}. */
    public String word() {
        return exactness.name().toLowerCase(Locale.ROOT);
    }

    /** As a request's map (what {@link #parse} reads back). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exact", exactness == Exactness.EXACT ? (Object) Boolean.TRUE : approximate() ? (Object) Boolean.FALSE : "auto");
        m.put("progressive", progressive);
        m.put("rollups", rollups);
        return m;
    }

    /**
     * From a request's map ({@code "exact": true | false | "auto"}, {@code "progressive": false}, {@code "rollups": false}); absent keys keep
     * the default.
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
                case "false", "approximate", "approx", "no", "≈" -> Exactness.APPROXIMATE;
                default -> Exactness.AUTO;
            };
        }
        return new ScaleOptions(x, 0, flag(m.get("progressive")), flag(m.get("rollups")));
    }

    /** Absent, or anything but false: true. */
    private static boolean flag(Object o) {
        return o == null || !(o instanceof Boolean b ? !b : "false".equalsIgnoreCase(String.valueOf(o).strip()));
    }
}
