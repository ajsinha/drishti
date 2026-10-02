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
package com.ash.drishti.rachana;

import com.ash.drishti.rachana.el.ElLimits;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.rachana.*}.
 *
 * @param dirs directories scanned recursively for {@code *.yaml} Sutra files
 * @param hotReload watch the directories and reload changed files
 * @param reloadDebounce quiet period before a burst of file events triggers one reload
 * @param formatsFile optional site file overriding or adding named formats
 * @param expressionCacheSize compiled Rachana-EL expressions kept in memory
 * @param studioSave allow Sutra Studio to write Sutra files (off by default; turn on for authoring environments)
 * @param packDirs Sutra directories contributed by enabled packs (set by the pack loader)
 * @param packFormatsFiles format files contributed by enabled packs
 * @param maxExpressionDepth the deepest a Rachana-EL expression may nest (default 200); deeper is {@code DRS-2101}
 * @param maxExpressionLength the longest a Rachana-EL expression may be, in characters (default 10000); longer is
 *     {@code DRS-2101}
 */
@ConfigurationProperties("drishti.rachana")
public record RachanaProperties(
        List<String> dirs, Boolean hotReload, Duration reloadDebounce, String formatsFile, Long expressionCacheSize, Boolean studioSave,
        List<String> packDirs, List<String> packFormatsFiles, Integer maxExpressionDepth, Integer maxExpressionLength) {

    public RachanaProperties {
        dirs = dirs == null ? List.of("./sutras") : List.copyOf(dirs);
        hotReload = hotReload == null ? Boolean.TRUE : hotReload;
        reloadDebounce = reloadDebounce == null ? Duration.ofMillis(250) : reloadDebounce;
        expressionCacheSize = expressionCacheSize == null ? 10_000L : expressionCacheSize;
        studioSave = studioSave != null && studioSave;
        packDirs = packDirs == null ? List.of() : List.copyOf(packDirs);
        packFormatsFiles = packFormatsFiles == null ? List.of() : List.copyOf(packFormatsFiles);
        maxExpressionDepth = maxExpressionDepth == null ? ElLimits.DEFAULTS.maxDepth() : maxExpressionDepth;
        maxExpressionLength = maxExpressionLength == null ? ElLimits.DEFAULTS.maxLength() : maxExpressionLength;
    }

    /** The bounds on every Rachana-EL expression (Sutras, alert rules, search conditions). */
    public ElLimits expressionLimits() {
        return new ElLimits(maxExpressionDepth, maxExpressionLength);
    }

    /** Site directories, then pack directories. */
    public List<String> allDirs() {
        List<String> all = new java.util.ArrayList<>(dirs);
        all.addAll(packDirs);
        return all;
    }
}
