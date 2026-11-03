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
package com.ash.drishti.bicompute.sql;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/**
 * The node's pivot executor (PIVOT_ENGINE.md section 9.2): one in-process DuckDB database, a bounded number of connections running at once
 * (each a duplicate of the base connection, sharing the database), each statement with its memory limit and threads. Tables staged for a
 * pivot (a source read through its connector, masked for the reader) live in it; a memory-only source is never spilled: the temporary
 * directory is off unless the host names one.
 */
public final class DuckDbExecutor implements AutoCloseable {

    /**
     * @param connections statements running at once (0: one per four cores)
     * @param threads DuckDB's threads (0: its default)
     * @param memoryLimitMb DuckDB's memory limit
     * @param tempDirectory where DuckDB may spill (null or blank: nowhere; a memory-only source is refused before it would spill)
     */
    public record Settings(int connections, int threads, int memoryLimitMb, String tempDirectory) {
        public static Settings small() {
            return new Settings(2, 2, 512, null);
        }
    }

    /** Reads a result set. */
    @FunctionalInterface
    public interface Reader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** Works on a connection. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws Exception;
    }

    private final DuckDBConnection base;
    private final Semaphore slots;
    private final Settings settings;

    public DuckDbExecutor(Settings settings) {
        this.settings = settings;
        try {
            Class.forName("org.duckdb.DuckDBDriver");
            this.base = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
            try (Statement st = base.createStatement()) {
                st.execute("SET autoinstall_known_extensions = false");
                st.execute("SET autoload_known_extensions = false");
                if (settings.threads() > 0) {
                    st.execute("SET threads = " + settings.threads());
                }
                st.execute("SET memory_limit = '" + Math.max(64, settings.memoryLimitMb()) + "MB'");
                if (settings.tempDirectory() != null && !settings.tempDirectory().isBlank()) {
                    Files.createDirectories(Path.of(settings.tempDirectory()));
                    st.execute("SET temp_directory = '" + settings.tempDirectory().replace("'", "''") + "'");
                } else {
                    st.execute("SET temp_directory = ''");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("the pivot executor's DuckDB could not start: " + e.getMessage(), e);
        }
        int n = settings.connections() > 0 ? settings.connections() : Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        this.slots = new Semaphore(n, true);
    }

    public Settings settings() {
        return settings;
    }

    /** Work on a connection of its own (waits for a slot). */
    public <T> T with(Work<T> work) {
        boolean got = false;
        try {
            slots.acquire();
            got = true;
            try (Connection c = base.duplicate()) {
                return work.run(c);
            }
        } catch (DrishtiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.QUERY_TIMEOUT, "the pivot was stopped");
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (m.contains("Out of Memory") || m.contains("out of memory")) {
                throw new DrishtiException(ErrorCode.PIVOT_TOO_MANY_CELLS, "the pivot ran out of its memory ("
                        + settings.memoryLimitMb() + " MB, drishti.pivot.duckdb.memory-limit-mb): filter it or collapse a level");
            }
            throw new DrishtiException(ErrorCode.QUERY_ENGINE, "the pivot's query failed: " + m, e);
        } finally {
            if (got) {
                slots.release();
            }
        }
    }

    /** A SELECT with bound parameters, read by {@code reader}. */
    public <T> T query(String sql, List<Object> params, Reader<T> reader) {
        return with(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return reader.read(rs);
                }
            }
        });
    }

    /** A statement that returns nothing. */
    public void execute(String sql) {
        with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }

    /** True when the table exists. */
    public boolean exists(String table) {
        return query("SELECT count(*) FROM information_schema.tables WHERE table_name = ?", List.of(table), rs -> rs.next() && rs.getLong(1) > 0);
    }

    /** A column of a staged table: its name and kind (text, number, date as ISO text). */
    public record Column(String name, String kind) {
        String sqlType() {
            return "number".equals(kind) ? "DOUBLE" : "VARCHAR";
        }
    }

    /** Feeds rows to a staging table. */
    @FunctionalInterface
    public interface RowFeed {
        /** Calls {@code sink} for every row (values in column order: String, Number or null); returns the rows fed. */
        long feed(RowSink sink) throws Exception;
    }

    /** Takes one row. */
    @FunctionalInterface
    public interface RowSink {
        void row(Object[] values) throws Exception;
    }

    /**
     * A table created and filled through DuckDB's appender (no SQL per row): {@code table} replaced if it exists. Returns the rows written.
     */
    public long stage(String table, List<Column> columns, RowFeed feed) {
        return with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + quote(table));
                StringBuilder ddl = new StringBuilder("CREATE TABLE " + quote(table) + " (");
                for (int i = 0; i < columns.size(); i++) {
                    ddl.append(i == 0 ? "" : ", ").append(quote(columns.get(i).name())).append(' ').append(columns.get(i).sqlType());
                }
                st.execute(ddl.append(')').toString());
            }
            long[] n = {0};
            try (DuckDBAppender app = ((DuckDBConnection) c).createAppender(DuckDBConnection.DEFAULT_SCHEMA, table)) {
                feed.feed(values -> {
                    app.beginRow();
                    for (int i = 0; i < columns.size(); i++) {
                        Object v = i < values.length ? values[i] : null;
                        if (v == null) {
                            app.appendNull();
                        } else if ("number".equals(columns.get(i).kind())) {
                            double d = v instanceof Number x ? x.doubleValue() : parse(String.valueOf(v));
                            if (Double.isNaN(d)) {
                                app.appendNull();
                            } else {
                                app.append(d);
                            }
                        } else {
                            app.append(String.valueOf(v));
                        }
                    }
                    app.endRow();
                    n[0]++;
                });
            }
            return n[0];
        });
    }

    /** Drops a staged table. */
    public void drop(String table) {
        execute("DROP TABLE IF EXISTS " + quote(table));
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A double-quoted identifier. */
    public static String quote(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    @Override
    public void close() {
        try {
            base.close();
        } catch (SQLException e) {
            // closing: nothing to do
        }
    }
}
