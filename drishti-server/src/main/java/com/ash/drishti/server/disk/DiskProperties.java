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
package com.ash.drishti.server.disk;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The server's local disk (RESOURCES_AND_SCALE.md section 7, 2.9.7), {@code drishti.disk.*}: the free-space guard every large writer asks
 * before it writes, how often the folders are measured, and how many heap dumps are kept.
 *
 * @param minFreePercent the free space a write must leave, as a share of the disk (10); both this and {@code minFreeGb} must hold
 * @param minFreeGb the free space a write must leave, in GB (20)
 * @param checkInterval how often every folder's size is measured and each brought under its cap (60 s)
 * @param maxDumps the heap dumps kept in the dump folder (2): the oldest beyond are deleted at start
 * @param dumpsDir the dump folder when the JVM's {@code -XX:HeapDumpPath} does not say ({@code <data dir>/dumps})
 */
@ConfigurationProperties("drishti.disk")
public record DiskProperties(Double minFreePercent, Double minFreeGb, Duration checkInterval, Integer maxDumps, String dumpsDir) {

    public DiskProperties {
        minFreePercent = minFreePercent == null || minFreePercent < 0 ? 10.0 : minFreePercent;
        minFreeGb = minFreeGb == null || minFreeGb < 0 ? 20.0 : minFreeGb;
        checkInterval = checkInterval == null || checkInterval.isNegative() || checkInterval.isZero() ? Duration.ofSeconds(60) : checkInterval;
        maxDumps = maxDumps == null || maxDumps < 0 ? 2 : maxDumps;
        dumpsDir = dumpsDir == null || dumpsDir.isBlank() ? com.ash.drishti.api.DataDir.under("dumps") : dumpsDir;
    }

    /** The defaults (tests). */
    public static DiskProperties defaults() {
        return new DiskProperties(null, null, null, null, null);
    }
}
