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
package com.ash.drishti.server.http;

import com.ash.drishti.api.pool.PoolSettings;
import com.ash.drishti.api.pool.SitePool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Hands the site-wide database pool defaults ({@code drishti.db.pool.*}) to {@link SitePool} before any bean is built, so the first
 * database connector already inherits them. A value that does not parse fails the start naming the setting. The value is read once
 * at start; a connector's own {@code pool-*} keys are live-reloaded like any other connector setting.
 */
public final class SitePoolEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PREFIX = "drishti.db.pool.";
    private static final List<String> KEYS = List.of("size", "min-idle", "idle-timeout", "max-lifetime", "connection-timeout", "leak-threshold",
            "statement-timeout");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, String> s = new LinkedHashMap<>();
        for (String key : KEYS) {
            String v = env.getProperty(PREFIX + key);
            if (v != null && !v.isBlank()) {
                // the connector's names: pool-size, pool-min-idle, ... except statement-timeout, which has no prefix
                s.put(key.equals("statement-timeout") ? key : "pool-" + key, v.strip());
            }
        }
        SitePool.configure(s);
        try {
            PoolSettings.resolve(Map.of());                      // fails the start on a malformed value
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PREFIX.substring(0, PREFIX.length() - 1) + ": " + e.getMessage(), e);
        }
    }
}
