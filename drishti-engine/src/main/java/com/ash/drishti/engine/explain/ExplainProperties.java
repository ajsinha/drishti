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
package com.ash.drishti.engine.explain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.explain.*}: the About this page answers (docs/architecture/CONTEXT_HELP.md).
 *
 * @param cacheSize answers kept, per user and page
 * @param cacheTtl how long an answer is served before it is derived again
 */
@ConfigurationProperties("drishti.explain")
public record ExplainProperties(Long cacheSize, Duration cacheTtl) {

    public ExplainProperties {
        cacheSize = cacheSize == null || cacheSize <= 0 ? 2_000L : cacheSize;
        cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? Duration.ofSeconds(60) : cacheTtl;
    }
}
