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

import java.time.Duration;
import java.util.Map;

/**
 * The pool settings every database connector shares: a connector's own keys (under its {@code settings:}) win, then the site
 * default ({@link SitePool}, {@code drishti.db.pool.*}), then the built-in default. Sizes and times are validated at start so a
 * typo fails the connector with the setting named, not later under load.
 *
 * <ul>
 *   <li>{@code pool-size}: most connections open at once (default 20);
 *   <li>{@code pool-min-idle}: idle connections kept when quiet (default 2, never more than the size);
 *   <li>{@code pool-idle-timeout}: an idle connection beyond the minimum is closed after this long (default 10m);
 *   <li>{@code pool-max-lifetime}: a connection is replaced after this long, so a firewall or a failover never meets a
 *       years-old connection (default 30m);
 *   <li>{@code pool-connection-timeout}: how long a caller waits for a free connection before failing (default 10s);
 *   <li>{@code pool-leak-threshold}: a connection borrowed longer than this is logged with the borrower's stack (default 60s; 0 off);
 *   <li>{@code statement-timeout}: longest one statement may run (default 5m; 0 off).
 * </ul>
 * A time is a number of seconds, or a number with {@code ms}, {@code s}, {@code m} or {@code h}.
 *
 * @param size most connections open at once
 * @param minIdle idle connections kept
 * @param idleTimeout idle time before an extra connection is closed
 * @param maxLifetime age at which a connection is replaced
 * @param connectionTimeout wait for a free connection
 * @param leakThreshold borrow time that is logged as a probable leak (zero: off)
 * @param statementTimeout longest statement (zero: none)
 */
public record PoolSettings(int size, int minIdle, Duration idleTimeout, Duration maxLifetime, Duration connectionTimeout,
        Duration leakThreshold, Duration statementTimeout) {

    /** The built-in pool size, used where neither the connector nor the site says. */
    public static final int DEFAULT_SIZE = 20;

    public PoolSettings {
        if (size < 1) {
            throw new IllegalArgumentException("pool-size must be at least 1, not " + size);
        }
        if (minIdle < 0) {
            throw new IllegalArgumentException("pool-min-idle must not be negative, not " + minIdle);
        }
        minIdle = Math.min(minIdle, size);
    }

    /** The settings for a connector: its own keys, else the site default, else the built-in default. */
    public static PoolSettings resolve(Map<String, String> connector) {
        Map<String, String> site = SitePool.settings();
        return new PoolSettings(
                integer("pool-size", connector, site, DEFAULT_SIZE),
                integer("pool-min-idle", connector, site, 2),
                time("pool-idle-timeout", connector, site, Duration.ofMinutes(10)),
                time("pool-max-lifetime", connector, site, Duration.ofMinutes(30)),
                time("pool-connection-timeout", connector, site, Duration.ofSeconds(10)),
                time("pool-leak-threshold", connector, site, Duration.ofSeconds(60)),
                time("statement-timeout", connector, site, Duration.ofMinutes(5)));
    }

    /** The same with a different size (a loader that needs its writers plus one). */
    public PoolSettings withSize(int newSize) {
        return new PoolSettings(newSize, minIdle, idleTimeout, maxLifetime, connectionTimeout, leakThreshold, statementTimeout);
    }

    /** The same with other waits (a bulk loader queues many units of work behind few connections, and holds one for the whole load). */
    public PoolSettings withWaits(Duration newConnectionTimeout, Duration newLeakThreshold) {
        return new PoolSettings(size, minIdle, idleTimeout, maxLifetime, newConnectionTimeout, newLeakThreshold, statementTimeout);
    }

    private static String pick(String key, Map<String, String> connector, Map<String, String> site) {
        String v = connector.get(key);
        if (v == null || v.isBlank()) {
            v = site.get(key);
        }
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static int integer(String key, Map<String, String> connector, Map<String, String> site, int fallback) {
        String v = pick(key, connector, site);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, not '" + v + "'");
        }
    }

    private static Duration time(String key, Map<String, String> connector, Map<String, String> site, Duration fallback) {
        String v = pick(key, connector, site);
        if (v == null) {
            return fallback;
        }
        try {
            return parse(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a time such as 30, 500ms, 30s, 10m or 1h, not '" + v + "'");
        }
    }

    /** {@code 30} (seconds), {@code 500ms}, {@code 30s}, {@code 10m}, {@code 1h}. */
    static Duration parse(String v) {
        String t = v.strip().toLowerCase(java.util.Locale.ROOT);
        if (t.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2).strip()));
        }
        if (t.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(t.substring(0, t.length() - 1).strip()));
        }
        if (t.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(t.substring(0, t.length() - 1).strip()));
        }
        if (t.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(t.substring(0, t.length() - 1).strip()));
        }
        return Duration.ofSeconds(Long.parseLong(t));
    }
}
