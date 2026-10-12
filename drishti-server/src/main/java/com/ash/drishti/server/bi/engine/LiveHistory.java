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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Histories;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The history of a live table's aggregates (docs/guides/LIVE_BI.md, "History"): an in-memory table of the analytic engine with one row per sample and group,
 * {@code ts}, the group columns and one column per sampled measure, bounded to the newest {@code keep} samples. It holds raw values, like the live table:
 * nothing here knows a reader, row rules and masks are applied by the query compiler.
 */
final class LiveHistory implements AutoCloseable {

    private final String sqlName;
    private final Dataset.History spec;
    private final Dataset.Table def;
    private final AnalyticEngine engine;
    private final ReentrantLock write = new ReentrantLock();
    private final AtomicLong samples = new AtomicLong();
    private final List<String> groupColumns;
    private final List<String> measureColumns = new ArrayList<>();
    private volatile Instant lastSample;
    private volatile String error;

    /** @param def the history table of the dataset (see {@link Histories}): {@code ts}, the group columns, one column per sampled measure, in that order */
    LiveHistory(String sqlName, Dataset.History spec, Dataset.Table def, AnalyticEngine engine) {
        this.sqlName = sqlName;
        this.spec = spec;
        this.def = def;
        this.engine = engine;
        this.groupColumns = List.copyOf(spec.by());
        List<String> taken = new ArrayList<>(List.of(Dataset.TS));
        taken.addAll(groupColumns);
        for (String m : spec.measures()) {
            String col = Histories.columnName(m, taken);
            taken.add(col);
            measureColumns.add(col);
        }
        StringBuilder sql = new StringBuilder("CREATE TABLE " + sqlName + " (\"ts\" TIMESTAMP");
        for (String g : groupColumns) {
            sql.append(", ").append(Dialect.quote(g)).append(' ').append(TableRelations.sqlType(def.columns().get(g).type(), Dialect.DUCKDB));
        }
        for (String c : measureColumns) {
            sql.append(", ").append(Dialect.quote(c)).append(" DOUBLE");
        }
        engine.execute(sql.append(")").toString(), List.of());
    }

    Dataset.History spec() {
        return spec;
    }

    String sqlName() {
        return sqlName;
    }

    long samples() {
        return samples.get();
    }

    Instant lastSample() {
        return lastSample;
    }

    String error() {
        return error;
    }

    void error(String problem) {
        this.error = problem;
    }

    /**
     * Adds one sample: {@code rows} each hold the group values then the measure values, in the order of the spec. Trims to the newest {@code keep} samples in the
     * same transaction. Returns the number of rows written.
     */
    int append(Instant at, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        write.lock();
        try {
            LocalDateTime ts = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
            engine.with(c -> {
                c.setAutoCommit(false);
                try {
                    StringBuilder q = new StringBuilder("INSERT INTO " + sqlName + " VALUES (?");
                    groupColumns.forEach(x -> q.append(", ?"));
                    measureColumns.forEach(x -> q.append(", ?"));
                    try (PreparedStatement ps = c.prepareStatement(q.append(")").toString())) {
                        for (Object[] r : rows) {
                            ps.setObject(1, ts);
                            for (int i = 0; i < r.length; i++) {
                                ps.setObject(i + 2, r[i]);
                            }
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + sqlName + " WHERE \"ts\" < (SELECT min(\"ts\") FROM (SELECT DISTINCT \"ts\" FROM " + sqlName
                            + " ORDER BY \"ts\" DESC LIMIT ?))")) {
                        ps.setInt(1, spec.keep());
                        ps.executeUpdate();
                    }
                    c.commit();
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
                return null;
            });
            samples.incrementAndGet();
            lastSample = at;
            error = null;
            return rows.size();
        } finally {
            write.unlock();
        }
    }

    /** The table as the FROM item of a query: {@code id} (the sample time as text), {@code businessDate}, then the declared columns. */
    SqlCompiler.Source source(Dataset ds, Dialect d) {
        return TableRelations.history(ds, def, sqlName, d);
    }

    /** What the Admin pages and the live tick show: how many samples are held, the newest, the limits. */
    java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("table", def.name());
        m.put("every", spec.every().toString());
        m.put("keep", spec.keep());
        m.put("samples", samples.get());
        m.put("lastSample", lastSample == null ? null : lastSample.toString());
        m.put("error", error);
        return m;
    }

    @Override
    public void close() {
        write.lock();
        try {
            engine.execute("DROP TABLE IF EXISTS " + sqlName, List.of());
        } catch (RuntimeException e) {
            // the engine is shutting down
        } finally {
            write.unlock();
        }
    }
}
