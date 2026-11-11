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
package com.ash.drishti.server.api;

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.pool.PoolStats;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.Map;
import java.util.Optional;

/**
 * The state of the database connection pools, as Admin and {@code /api/v1/admin/health} show it: a connector's pool from
 * {@link SourcePlugin#poolStats()}, and the identity database's Hikari pool. Open, in use, idle, waiting, the longest current borrow
 * (-1 where the pool cannot tell), and the leaks and timeouts counted since start.
 */
public final class PoolReport {

    private PoolReport() {}

    /** The pool of a connector's plugin, or {@code null} when it has none (files, queues, REST). */
    public static Map<String, Object> of(SourcePlugin plugin) {
        try {
            return plugin.poolStats().map(PoolStats::toMap).orElse(null);
        } catch (RuntimeException e) {
            return null;                                           // a plugin that cannot say is shown without a pool
        }
    }

    /** The identity database's pool; SQLite is embedded and exempt, PostgreSQL is pooled with the site's defaults. */
    public static Optional<PoolStats> identity(HikariDataSource ds, boolean sqlite) {
        if (ds == null) {
            return Optional.empty();
        }
        HikariPoolMXBean pool = ds.getHikariPoolMXBean();
        int max = ds.getMaximumPoolSize();
        if (pool == null) {
            return Optional.of(new PoolStats(sqlite ? "sqlite" : "identity", max, 0, 0, 0, 0, -1, 0, 0));
        }
        return Optional.of(new PoolStats(sqlite ? "sqlite" : "identity", max, pool.getTotalConnections(), pool.getActiveConnections(),
                pool.getIdleConnections(), pool.getThreadsAwaitingConnection(), -1, 0, 0));
    }

    /** True when callers are waiting because every connection is in use, or a borrow has passed the leak threshold. */
    public static boolean needsAttention(Map<String, Object> pool) {
        if (pool == null) {
            return false;
        }
        int max = ((Number) pool.get("max")).intValue();
        return ((Number) pool.get("waiting")).intValue() > 0 && ((Number) pool.get("inUse")).intValue() >= max
                || ((Number) pool.get("leaksDetected")).longValue() > 0;
    }
}
