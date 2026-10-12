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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.duckdb.DuckDBConnection;

/**
 * The embedded analytic engine: one in-process DuckDB (no extra server), a connection per query duplicated from the root so queries run side by
 * side, each with a watchdog that cancels it at the time limit. Memory and threads are settings ({@link QueryProperties}). Extensions are never
 * downloaded at run time: the JDBC jar links {@code parquet}, {@code json}, {@code core_functions} and {@code icu}, which is all the layers need;
 * Parquet and Delta data files are read directly (RUPAKA_POC.md section 7).
 */
public final class DuckDbEngine implements AnalyticEngine {

    private final QueryProperties props;
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("drishti-bi-watchdog").unstarted(r));
    private DuckDBConnection root;

    public DuckDbEngine(QueryProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "duckdb";
    }

    @Override
    public Dialect dialect() {
        return Dialect.DUCKDB;
    }

    private Connection connection() throws SQLException {
        lock.lock();                              // opening DuckDB does I/O: a ReentrantLock (synchronized pins a virtual thread)
        try {
            if (root == null) {
                Path tmp = com.ash.drishti.common.TempFiles.subDir("duckdb");           // DuckDB spills here: inside the process temp folder, removed on shutdown
                try {
                    Files.createDirectories(tmp);
                } catch (java.io.IOException e) {
                    throw new SQLException("cannot create " + tmp + ": " + e.getMessage());
                }
                root = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
                try (Statement s = root.createStatement()) {
                    s.execute("SET memory_limit = '" + props.memoryLimit().replace("'", "") + "'");
                    s.execute("SET threads = " + props.threads());
                    s.execute("SET temp_directory = '" + tmp.toString().replace("'", "''") + "'");
                    s.execute("SET autoinstall_known_extensions = false");
                    s.execute("SET autoload_known_extensions = false");
                }
            }
            return root.duplicate();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Answer query(String sql, List<Object> params, int maxRows, Duration timeout) {
        long t0 = System.nanoTime();
        AtomicBoolean timedOut = new AtomicBoolean();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ScheduledFuture<?> cancel = watchdog.schedule(() -> {
                timedOut.set(true);
                try {
                    ps.cancel();
                } catch (SQLException ignored) {
                    // already finished
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                List<ArrowIpcWriter.Column> cols = read(rs, maxRows);
                cancel.cancel(false);
                return new Answer(cols, cols.isEmpty() ? 0 : cols.get(0).values().length, (System.nanoTime() - t0) / 1e6);
            } finally {
                cancel.cancel(false);
            }
        } catch (SQLException e) {
            if (timedOut.get() || String.valueOf(e.getMessage()).toLowerCase().contains("interrupt")) {
                throw new DrishtiException(ErrorCode.QUERY_TIMEOUT, "the query ran longer than " + timeout.toSeconds() + " s and was stopped (drishti.bi.query.timeout)");
            }
            throw new DrishtiException(ErrorCode.QUERY_ENGINE, "the engine could not run the query: " + firstLine(e));
        }
    }

    @Override
    public void execute(String sql, List<Object> params) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.execute();
        } catch (SQLException e) {
            throw new DrishtiException(ErrorCode.QUERY_ENGINE, "the engine could not run a statement: " + firstLine(e));
        }
    }

    @Override
    public <T> T with(Work<T> work) {
        try (Connection c = connection()) {
            return work.run(c);
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.QUERY_ENGINE, "the engine failed: " + firstLine(e));
        }
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    static List<ArrowIpcWriter.Column> read(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        ArrowIpcWriter.Kind[] kinds = new ArrowIpcWriter.Kind[n];
        for (int i = 0; i < n; i++) {
            String t = md.getColumnTypeName(i + 1).toUpperCase(java.util.Locale.ROOT);
            boolean wholeDecimal = t.startsWith("DECIMAL") && md.getScale(i + 1) == 0;                 // a sum of integers can come back as DECIMAL(38,0)
            kinds[i] = (t.contains("INT") && !t.contains("INTERVAL")) || wholeDecimal ? ArrowIpcWriter.Kind.INT64
                    : t.startsWith("DOUBLE") || t.startsWith("FLOAT") || t.startsWith("DECIMAL") || t.equals("REAL") ? ArrowIpcWriter.Kind.FLOAT64 : ArrowIpcWriter.Kind.TEXT;
        }
        List<Object[]> rows = new ArrayList<>();
        while (rs.next() && rows.size() < maxRows) {
            Object[] row = new Object[n];
            for (int i = 0; i < n; i++) {
                Object v = rs.getObject(i + 1);
                row[i] = v == null ? null : switch (kinds[i]) {
                    case INT64 -> v instanceof Number x ? (Object) x.longValue() : Long.valueOf(v.toString());
                    case FLOAT64 -> v instanceof Number x ? (Object) x.doubleValue() : Double.valueOf(v.toString());
                    case TEXT -> v.toString();
                };
            }
            rows.add(row);
        }
        List<ArrowIpcWriter.Column> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Object[] values = new Object[rows.size()];
            for (int r = 0; r < values.length; r++) {
                values[r] = rows.get(r)[i];
            }
            out.add(new ArrowIpcWriter.Column(md.getColumnLabel(i + 1), kinds[i], values));
        }
        return out;
    }

    private static String firstLine(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.lines().findFirst().orElse(m);
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        lock.lock();
        try {
            if (root != null) {
                try {
                    root.close();
                } catch (SQLException ignored) {
                    // closing at shutdown
                }
                root = null;
            }
        } finally {
            lock.unlock();
        }
    }
}
