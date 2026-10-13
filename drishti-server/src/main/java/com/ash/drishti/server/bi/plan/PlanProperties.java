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
package com.ash.drishti.server.bi.plan;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The logical plan's settings, {@code drishti.bi.plan.*} (docs/admin/CONFIGURATION.md).
 *
 * @param cacheEntries answers the result cache keeps (L1 of section 6.1); 0 turns it off
 * @param cacheTtl how long an answer for the current state (not an as-of date) is served before it is asked again, even when no generation moved
 * @param cacheIdle an answer nobody asked for this long is dropped (as-of answers have no other expiry)
 * @param guards {@code refuse}: a sum across currencies ({@code DRS-9158}) and a balance added across dates ({@code DRS-9159}) are refused (wave A);
 *     {@code warn}: answered with a warning, as in wave W1
 */
@ConfigurationProperties("drishti.bi.plan")
public record PlanProperties(Integer cacheEntries, Duration cacheTtl, Duration cacheIdle, String guards) {

    public PlanProperties {
        cacheEntries = cacheEntries == null || cacheEntries < 0 ? 2000 : cacheEntries;
        cacheTtl = cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero() ? Duration.ofMinutes(15) : cacheTtl;
        cacheIdle = cacheIdle == null || cacheIdle.isNegative() || cacheIdle.isZero() ? Duration.ofHours(1) : cacheIdle;
        guards = guards == null || guards.isBlank() ? "refuse" : guards.strip().toLowerCase(Locale.ROOT);
        if (!"refuse".equals(guards) && !"warn".equals(guards)) {
            throw new IllegalArgumentException("drishti.bi.plan.guards is refuse or warn, not '" + guards + "'");
        }
    }

    public boolean refuse() {
        return "refuse".equals(guards);
    }

    /** No result cache, guards that warn: what the tests of the language and the engine use. */
    public static PlanProperties off() {
        return new PlanProperties(0, null, null, "warn");
    }

    public static PlanProperties defaults() {
        return new PlanProperties(null, null, null, null);
    }
}
