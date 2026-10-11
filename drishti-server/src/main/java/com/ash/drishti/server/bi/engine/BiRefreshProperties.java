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
package com.ash.drishti.server.bi.engine;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scheduled dataset refresh, {@code drishti.bi.refresh.*} (docs/admin/CONFIGURATION.md): a dataset's {@code refresh:} schedule rebuilds its columns and
 * rollup layers; a failed run is retried with the shared policy ({@code drishti.retry.bi.*}, falling back to {@code drishti.retry.*}).
 *
 * @param enabled false: no schedule runs (a data-load signal and the Refresh button still do)
 * @param tick how often the scheduler looks for a refresh that is due or a retry whose wait is over
 * @param maxAttempts tries for one scheduled refresh, the first included, before it is left failed until its next scheduled time
 * @param loadDelay for {@code after-load}: how long after the data-load signal the refresh starts
 */
@ConfigurationProperties("drishti.bi.refresh")
public record BiRefreshProperties(Boolean enabled, Duration tick, Integer maxAttempts, Duration loadDelay) {

    public BiRefreshProperties {
        enabled = enabled == null || enabled;
        tick = tick == null || tick.isNegative() || tick.isZero() ? Duration.ofSeconds(30) : tick;
        maxAttempts = maxAttempts == null || maxAttempts <= 0 ? 4 : Math.min(maxAttempts, 20);
        loadDelay = loadDelay == null || loadDelay.isNegative() ? Duration.ofSeconds(2) : loadDelay;
    }

    public static BiRefreshProperties defaults() {
        return new BiRefreshProperties(null, null, null, null);
    }
}
