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
package com.ash.drishti.sutra;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.sutra.*}.
 *
 * @param dirs directories scanned recursively for {@code *.yaml} Sutra files
 * @param hotReload watch the directories and reload changed files
 * @param reloadDebounce quiet period before a burst of file events triggers one reload
 * @param formatsFile optional site file overriding or adding named formats
 * @param expressionCacheSize compiled Sutra-EL expressions kept in memory
 */
@ConfigurationProperties("drishti.sutra")
public record SutraProperties(
        List<String> dirs, Boolean hotReload, Duration reloadDebounce, String formatsFile, Long expressionCacheSize) {

    public SutraProperties {
        dirs = dirs == null || dirs.isEmpty() ? List.of("./sutras") : List.copyOf(dirs);
        hotReload = hotReload == null ? Boolean.TRUE : hotReload;
        reloadDebounce = reloadDebounce == null ? Duration.ofMillis(250) : reloadDebounce;
        expressionCacheSize = expressionCacheSize == null ? 10_000L : expressionCacheSize;
    }
}
