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

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The interactive pivot (USER_GUIDE.md, The Pivot tab): offered only where a Sutra ({@code pivot:} on a table or ladder)
 * or a pack ({@code pivot:} beside a kind's {@code columns:}) opts in. These are its limits.
 *
 * @param enabled false hides every Pivot tab, whatever Sutras and packs say (default true)
 * @param maxRecords rows of a panel the browser receives to pivot (default 50,000; the rest are left out, and it says so)
 * @param maxRowKeys row groups a pivot shows at the innermost level (default 2,000; further ones are counted in the totals)
 * @param maxColumnKeys column groups a pivot shows (default 200)
 * @param documentScan entities a search pivot reads as documents when a field is not kept as a column and the user asks
 *     for it (default 20,000; more makes the result partial)
 * @param drillPage the most underlying rows one drill-down page returns (default 200)
 * @param budget how long a search pivot may wait for a business day's columns or documents (default 20 s)
 */
@ConfigurationProperties("drishti.pivot")
public record PivotProperties(Boolean enabled, Integer maxRecords, Integer maxRowKeys, Integer maxColumnKeys, Integer documentScan,
        Integer drillPage, Duration budget) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public PivotProperties {
        enabled = enabled == null || enabled;
        maxRecords = positive(maxRecords, 50_000);
        maxRowKeys = positive(maxRowKeys, 2_000);
        maxColumnKeys = positive(maxColumnKeys, 200);
        documentScan = positive(documentScan, 20_000);
        drillPage = positive(drillPage, 200);
        budget = budget == null || budget.isNegative() || budget.isZero() ? Duration.ofSeconds(20) : budget;
    }

    /** The defaults. */
    public static PivotProperties defaults() {
        return new PivotProperties(null, null, null, null, null, null, null);
    }

    private static int positive(Integer v, int fallback) {
        return v == null || v <= 0 ? fallback : v;
    }
}
