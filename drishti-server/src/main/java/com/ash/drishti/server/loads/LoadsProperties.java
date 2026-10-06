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
package com.ash.drishti.server.loads;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Data loads ({@code drishti.loads.*}): the "batch landed" signal your ETL sends and what Drishti does with it.
 *
 * @param dir where the load history and the administrators' expectation overrides are kept (one file per pack)
 * @param keep loads kept per pack in the history (the oldest go first)
 * @param checkInterval how often the expectations are checked for late data
 * @param scheduler false turns the late-data check off (the Data loads page and the API still work)
 * @param verifyLimit most entities counted when a load is verified (a count at the limit reads "N or more")
 * @param verifyTimeout how long the verify, alert and smoke steps may each wait for the data
 * @param lookbackDays business days before today that are still checked for missing data
 * @param defaultNotifyRoles roles told about a load or late data when the pack names none
 * @param smokeMax most sample entities a smoke step may open
 */
@ConfigurationProperties("drishti.loads")
public record LoadsProperties(String dir, Integer keep, Duration checkInterval, Boolean scheduler, Integer verifyLimit, Duration verifyTimeout,
        Integer lookbackDays, List<String> defaultNotifyRoles, Integer smokeMax) {

    public LoadsProperties {
        dir = dir == null || dir.isBlank() ? "./data/loads" : dir;
        keep = keep == null ? 2000 : Math.max(10, keep);
        checkInterval = checkInterval == null ? Duration.ofSeconds(60) : checkInterval;
        scheduler = scheduler == null || scheduler;
        verifyLimit = verifyLimit == null ? 1000 : Math.max(1, verifyLimit);
        verifyTimeout = verifyTimeout == null ? Duration.ofSeconds(20) : verifyTimeout;
        lookbackDays = lookbackDays == null ? 3 : Math.max(0, lookbackDays);
        defaultNotifyRoles = defaultNotifyRoles == null ? List.of("admin") : List.copyOf(defaultNotifyRoles);
        smokeMax = smokeMax == null ? 20 : Math.max(0, smokeMax);
    }
}
