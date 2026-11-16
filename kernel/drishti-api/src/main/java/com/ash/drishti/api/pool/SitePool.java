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
package com.ash.drishti.api.pool;

import java.util.Map;

/**
 * The site-wide default for database pools ({@code drishti.db.pool.*} in {@code application.yaml}) that every database connector
 * inherits unless it sets its own {@code pool-*} keys. The server hands it over once at start; {@link PoolSettings#resolve} reads it.
 * The keys are the connector's ({@code pool-size}, {@code pool-min-idle}, {@code pool-idle-timeout}, {@code pool-max-lifetime},
 * {@code pool-connection-timeout}, {@code pool-leak-threshold}, {@code statement-timeout}).
 */
public final class SitePool {

    private static volatile Map<String, String> state = Map.of();

    private SitePool() {}

    /** Sets the site default (empty: the built-in defaults). */
    public static void configure(Map<String, String> settings) {
        state = Map.copyOf(settings);
    }

    /** Back to the built-in defaults (tests). */
    public static void reset() {
        state = Map.of();
    }

    static Map<String, String> settings() {
        return state;
    }
}
