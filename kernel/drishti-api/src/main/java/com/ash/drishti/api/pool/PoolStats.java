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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A snapshot of one connection pool, for Admin and the health page.
 *
 * @param type what is pooled (jdbc, mongodb, redis, aerospike)
 * @param max the configured size
 * @param open connections open now (in use plus idle)
 * @param inUse connections borrowed now
 * @param idle connections open and waiting
 * @param waiting callers waiting for a connection
 * @param longestBorrowMillis how long the longest current borrow has lasted (0 when none, -1 when the pool cannot tell)
 * @param leaksDetected borrows that passed the leak threshold since start
 * @param timeouts callers that gave up waiting since start
 */
public record PoolStats(String type, int max, int open, int inUse, int idle, int waiting, long longestBorrowMillis, long leaksDetected,
        long timeouts) {

    /** True for an embedded, in-process database (DuckDB, SQLite): not pooled, so the pool defaults do not apply. */
    public boolean embedded() {
        return "duckdb".equals(type) || "sqlite".equals(type);
    }

    /** The snapshot as JSON-ready fields; a borrow time of -1 means the pool cannot say. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("exempt", embedded());
        m.put("max", max);
        m.put("open", open);
        m.put("inUse", inUse);
        m.put("idle", idle);
        m.put("waiting", waiting);
        m.put("longestBorrowMillis", longestBorrowMillis);
        m.put("leaksDetected", leaksDetected);
        m.put("timeouts", timeouts);
        return m;
    }
}
