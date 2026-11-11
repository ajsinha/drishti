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
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code drishti.rachana.*}.
 *
 * @param dirs directories scanned recursively for {@code *.yaml} Sutra files
 * @param hotReload watch the directories and reload changed files
 * @param reloadDebounce quiet period before a burst of file events triggers one reload
 * @param watch how hot reload notices edits: {@code auto} (the operating system's file events, falling back to polling when
 *     they are not available, e.g. the inotify watch limit is reached) or {@code poll} (always poll; network file systems)
 * @param pollInterval how often polling looks at the Sutra files
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
        List<String> packDirs, List<String> packFormatsFiles, Integer maxExpressionDepth, Integer maxExpressionLength, String watch,
        Duration pollInterval) {

    /** Without the hot-reload mode and poll interval (both default). */
    public RachanaProperties(List<String> dirs, Boolean hotReload, Duration reloadDebounce, String formatsFile, Long expressionCacheSize,
            Boolean studioSave, List<String> packDirs, List<String> packFormatsFiles, Integer maxExpressionDepth, Integer maxExpressionLength) {
        this(dirs, hotReload, reloadDebounce, formatsFile, expressionCacheSize, studioSave, packDirs, packFormatsFiles, maxExpressionDepth,
                maxExpressionLength, null, null);
    }

    @ConstructorBinding                                  // the canonical constructor binds the settings (there is a shorter one too)
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
        watch = watch == null || watch.isBlank() ? "auto" : watch.strip().toLowerCase(java.util.Locale.ROOT);
        if (!watch.equals("auto") && !watch.equals("poll")) {
            throw new IllegalArgumentException("drishti.rachana.watch must be auto or poll, not " + watch);
        }
        pollInterval = pollInterval == null || pollInterval.isNegative() || pollInterval.isZero() ? Duration.ofSeconds(2) : pollInterval;
    }

    /** The bounds on every Rachana-EL expression (Sutras, alert rules, search conditions). */
    public ElLimits expressionLimits() {
        return new ElLimits(maxExpressionDepth, maxExpressionLength);
    }

    /** The same settings with other pack Sutra directories (packs were loaded, changed or unloaded while the server runs). */
    public RachanaProperties withPackDirs(List<String> newPackDirs) {
        return new RachanaProperties(dirs, hotReload, reloadDebounce, formatsFile, expressionCacheSize, studioSave, newPackDirs, packFormatsFiles,
                maxExpressionDepth, maxExpressionLength, watch, pollInterval);
    }

    /** Site directories, then pack directories. */
    public List<String> allDirs() {
        List<String> all = new java.util.ArrayList<>(dirs);
        all.addAll(packDirs);
        return all;
    }
}
