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
package com.ash.drishti.engine.pivot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The pivot of a panel (PIVOT_GUIDE.md, a panel's Pivot): offered only where a Sutra ({@code pivot:} on a table or ladder) or a pack
 * ({@code pivot:} beside a kind's {@code columns:}) opts in; its rows are read by the view pipeline and pivoted by the pivot engine.
 *
 * @param enabled false offers no Pivot on any panel (the old name of the pivot engine's switch; default true)
 * @param maxRecords the most rows of a panel a pivot of it reads (default 50,000; a panel of more is refused, never cut short)
 */
@ConfigurationProperties("drishti.pivot")
public record PivotProperties(Boolean enabled, Integer maxRecords) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public PivotProperties {
        enabled = enabled == null || enabled;
        maxRecords = positive(maxRecords, 50_000);
    }

    /** The defaults. */
    public static PivotProperties defaults() {
        return new PivotProperties(null, null);
    }

    private static int positive(Integer v, int fallback) {
        return v == null || v <= 0 ? fallback : v;
    }
}
