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
package com.ash.drishti.server.calc;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Calc: Python run in the browser (PYTHON_CALC.md). The server never runs the code; it answers the same reads as for the
 * screen, keeps each user's saved snippets, and serves whole columns of a day.
 *
 * @param enabled Calc is offered at all (to roles with {@code calc}); off: nobody sees it and its endpoints refuse
 * @param maxColumnRows {@code drishti.columns()} returns at most this many entities (the browser holds them all)
 * @param columnsBudget how long a columns read may wait for its source (the first read of a day loads the columns)
 * @param maxSnippetChars a saved snippet's code is at most this long
 */
@ConfigurationProperties("drishti.calc")
public record CalcProperties(Boolean enabled, Integer maxColumnRows, Duration columnsBudget, Integer maxSnippetChars) {

    public CalcProperties {
        enabled = enabled == null || enabled;
        maxColumnRows = maxColumnRows == null || maxColumnRows <= 0 ? 250_000 : maxColumnRows;
        columnsBudget = columnsBudget == null || columnsBudget.isNegative() || columnsBudget.isZero() ? Duration.ofSeconds(20) : columnsBudget;
        maxSnippetChars = maxSnippetChars == null || maxSnippetChars <= 0 ? 50_000 : Math.min(maxSnippetChars, 60_000);
    }
}
