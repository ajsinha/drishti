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

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.SqlTarget;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a connector can do for the query planner (docs/architecture/BI_DESIGN.md section 6.6, the pushdown capability descriptor; it replaces the fallback to
 * a default dialect): whether it is a SQL store and in which dialect, whether it aggregates (an {@code Aggregate} fragment is pushed into it), the column
 * types it filters on, whether it streams (pushes changes: live tables), whether it reads by business date, and how many fragments it runs at once.
 *
 * @param dialect {@code duckdb} or {@code postgresql} for a SQL store; null for a connector the engine reads (scans or documents)
 */
public record ConnectorCapabilities(String connector, String dialect, boolean aggregates, Set<String> filterTypes, boolean streams, boolean dated, int maxFragments) {

    private static final Set<String> SQL_TYPES = Set.of("string", "integer", "decimal", "date", "timestamp", "boolean");

    public ConnectorCapabilities {
        filterTypes = filterTypes == null ? Set.of() : Set.copyOf(filterTypes);
    }

    /** The descriptor of a running connector for a kind. */
    public static ConnectorCapabilities of(SourcePlugin p, String kind, ExecProperties props) {
        String name = p.manifest().name();
        SqlTarget t = p.sqlTarget().orElse(null);
        String dialect = t == null ? null : "postgresql".equalsIgnoreCase(t.dialect()) ? "postgresql" : "duckdb";
        boolean scans = !p.columnar(kind).isEmpty();
        return new ConnectorCapabilities(name, dialect, t != null, t != null ? SQL_TYPES : scans ? Set.of("string", "integer", "decimal", "date") : Set.of(),
                p.manifest().capabilities().live(), p.manifest().capabilities().dated(), props.fragmentsOf(name));
    }

    /** True for a SQL store: a {@code direct} table's fragment is SQL run there. */
    public boolean sql() {
        return dialect != null;
    }

    /** The dialect fragments for this connector are written in (only for a SQL store). */
    public Dialect sqlDialect() {
        if (dialect == null) {
            throw new IllegalStateException("connector '" + connector + "' is not a SQL store");
        }
        return Dialect.of(dialect);
    }

    /** The tier a scan of this connector's table runs on: the source for a SQL store's direct table, else the Java tier over its layers. */
    public String scanTier(String mode) {
        return "direct".equals(mode) && sql() ? "source" : "java";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connector", connector);
        m.put("sql", sql());
        m.put("dialect", dialect);
        m.put("aggregates", aggregates);
        m.put("filterTypes", new java.util.TreeSet<>(filterTypes));
        m.put("streams", streams);
        m.put("dated", dated);
        m.put("maxFragments", maxFragments);
        return m;
    }
}
