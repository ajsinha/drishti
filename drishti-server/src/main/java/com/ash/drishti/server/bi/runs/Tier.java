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
package com.ash.drishti.server.bi.runs;

import java.util.Arrays;
import java.util.Locale;

/** The three execution tiers the planner chooses from metadata only (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 5). */
public enum Tier {
    /** Bounded work: one date, one entity, or a product covers the question. */
    INTERACTIVE,
    /** Answered from rollups or products written at load or by scheduled refresh. */
    KNOWN_HEAVY,
    /** A background job: the run engine, split over nodes, or an external engine. */
    AD_HOC_HEAVY;

    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Tier of(String wire) {
        return Arrays.stream(values()).filter(t -> t.wire().equals(wire)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not a tier: " + wire));
    }
}
