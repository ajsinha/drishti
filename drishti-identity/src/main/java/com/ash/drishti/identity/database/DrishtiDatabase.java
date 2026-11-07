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
package com.ash.drishti.identity.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens the one Drishti database (docs/architecture/DATA_ARCHITECTURE.md, "The one Drishti database"): the pool every Drishti-owned table
 * is reached through (identity, collaboration, the edit log and lanes, governance, BI items and runs, compute approvals, switches, the
 * content store's {@code database} backend), with its complete schema applied and the schema version stamp checked.
 *
 * <p>The stamp ({@code drishti_schema}) holds the version of the schema files that built the database. A fresh database is stamped; one
 * stamped with another version, or one that lacks a column of the complete schema, is refused with the copy command named: a schema
 * change reaches an existing database only through {@code drishti.py db copy}, never through an {@code ALTER}.
 */
public final class DrishtiDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(DrishtiDatabase.class);

    private DrishtiDatabase() {
    }

    /**
     * The pool, with the schema applied and the stamp checked. {@code site} answers the site's pool keys ({@code drishti.db.pool.*}),
     * the defaults of the keys the database does not set itself; PostgreSQL that is not up yet is waited for (about four minutes).
     */
    public static HikariDataSource open(DatabaseSettings s, UnaryOperator<String> site) {
        HikariDataSource ds = pool(s, site, "drishti-db");
        try {
            applySchema(ds, s);
            check(ds, s);
            return ds;
        } catch (RuntimeException e) {
            ds.close();
            throw e;
        }
    }

    /** A pool without the schema step (the copy tool reads a source database as it is). */
    public static HikariDataSource pool(DatabaseSettings s, UnaryOperator<String> site, String name) {
        HikariConfig c = new HikariConfig();
        c.setPoolName(name);
        c.setJdbcUrl(s.jdbcUrl());
        if (s.sqlite()) {
            try {
                Files.createDirectories(s.sqliteFile().getParent());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // WAL: readers never wait for the writer; writers wait (busy_timeout) rather than fail; cascades need foreign keys.
            c.addDataSourceProperty("journal_mode", "WAL");
            c.addDataSourceProperty("busy_timeout", "10000");
            c.addDataSourceProperty("foreign_keys", "true");
            c.addDataSourceProperty("synchronous", "NORMAL");
            // A transaction that reads and then writes must take the write lock when it begins: two that both read first
            // would each wait for the other to finish reading, and SQLite fails one at once (SQLITE_BUSY) rather than wait.
            c.addDataSourceProperty("transaction_mode", "IMMEDIATE");
        } else {
            c.setUsername(s.user());
            c.setPassword(s.password());
        }
        applyPool(c, s, site);
        // Start even when PostgreSQL is not up yet (the pool connects on first use); the schema step then waits.
        c.setInitializationFailTimeout(-1);
        return new HikariDataSource(c);
    }

    /**
     * The pool: each key from {@code drishti.database.pool.*}, else the site's {@code drishti.db.pool.*}. The size defaults to 20 on
     * PostgreSQL; SQLite is an embedded file with one writer at a time, so it keeps 8 unless the database sets its own size. A connection
     * is borrowed per transaction and returned at once; one held past {@code leak-threshold} is logged with the borrower's stack.
     */
    public static void applyPool(HikariConfig c, DatabaseSettings s, UnaryOperator<String> site) {
        UnaryOperator<String> key = k -> s.pool().containsKey(k) ? s.pool().get(k) : site.apply("drishti.db.pool." + k);
        String own = s.pool().get("size");
        int size = own != null ? (int) number("drishti.database.pool.size", own, 8) : s.sqlite() ? 8 : (int) number("drishti.db.pool.size", key.apply("size"), 20);
        c.setMaximumPoolSize(size);
        c.setMinimumIdle((int) Math.min(size, number(name(s, "min-idle"), key.apply("min-idle"), 2)));
        c.setIdleTimeout(millis(name(s, "idle-timeout"), key.apply("idle-timeout"), 600_000));
        c.setMaxLifetime(millis(name(s, "max-lifetime"), key.apply("max-lifetime"), 1_800_000));
        c.setConnectionTimeout(Math.max(250, millis(name(s, "connection-timeout"), key.apply("connection-timeout"), 10_000)));
        c.setLeakDetectionThreshold(millis(name(s, "leak-threshold"), key.apply("leak-threshold"), 60_000));
        long statement = millis(name(s, "statement-timeout"), key.apply("statement-timeout"), 300_000);
        if (!s.sqlite() && statement > 0) {
            c.addDataSourceProperty("options", "-c statement_timeout=" + statement);
        }
    }

    private static String name(DatabaseSettings s, String k) {
        return (s.pool().containsKey(k) ? "drishti.database.pool." : "drishti.db.pool.") + k;
    }

    private static long number(String key, String v, long fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " must be a number, not '" + v + "'");
        }
    }

    private static long millis(String key, String v, long fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        String t = v.strip().toLowerCase(Locale.ROOT);
        try {
            if (t.endsWith("ms")) {
                return Long.parseLong(t.substring(0, t.length() - 2).strip());
            }
            long unit = t.endsWith("h") ? 3_600_000 : t.endsWith("m") ? 60_000 : 1_000;
            return Long.parseLong(t.replaceAll("[hms]$", "").strip()) * unit;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " must be a time such as 30, 500ms, 30s, 10m or 1h, not '" + v + "'");
        }
    }

    private static void applySchema(HikariDataSource ds, DatabaseSettings s) {
        long backoff = 1_000;
        for (int attempt = 1; ; attempt++) {
            try {
                DatabaseSchema.apply(ds, s.sqlite());
                LOG.info("Drishti database ready: {} ({}, schema version {})", s.describe(), DatabaseSchema.resource(s.sqlite()),
                        DatabaseSchema.version(s.sqlite()));
                return;
            } catch (RuntimeException e) {
                if (attempt >= 30) {
                    throw e;
                }
                LOG.warn("Drishti database not reachable yet ({}); retrying in {} ms", e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoff = Math.min(backoff * 2, 10_000);
            }
        }
    }

    /**
     * The stamp and the shape: a database stamped with the files' version and holding every column starts; an unstamped one (fresh, or
     * made by a release before the stamp) is stamped when it holds every column; anything else is refused with the copy command.
     */
    static void check(DataSource ds, DatabaseSettings s) {
        int want = DatabaseSchema.version(s.sqlite());
        OptionalInt have = stamp(ds);
        List<String> missing = DatabaseSchema.missing(ds, s.sqlite());
        if (have.isPresent() && have.getAsInt() != want) {
            throw refused(s, "was made by schema " + have.getAsInt() + "; this server needs schema " + want);
        }
        if (!missing.isEmpty()) {
            throw refused(s, "lacks " + String.join(", ", missing.subList(0, Math.min(8, missing.size())))
                    + (missing.size() > 8 ? " and " + (missing.size() - 8) + " more" : "") + " (made by an older schema)");
        }
        if (have.isEmpty()) {
            writeStamp(ds, want);
        }
    }

    private static IllegalStateException refused(DatabaseSettings s, String why) {
        return new IllegalStateException("the Drishti database " + s.describe() + " " + why + ". Stop the servers and copy it into a fresh "
                + "database: drishti.py db copy --from config/db/database.yaml --to <new database.yaml>, then point config/db/database.yaml at "
                + "the new one (docs/admin/OPERATIONS.md, \"Upgrading the Drishti database\")");
    }

    /** The version the database is stamped with, if any. */
    public static OptionalInt stamp(DataSource ds) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT version FROM drishti_schema WHERE id = 1");
                ResultSet r = ps.executeQuery()) {
            return r.next() ? OptionalInt.of(r.getInt(1)) : OptionalInt.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the Drishti database's schema stamp: " + e.getMessage(), e);
        }
    }

    /** Stamps a database the complete schema built (or one holding all of it) with {@code version}. */
    public static void writeStamp(DataSource ds, int version) {
        String release = DrishtiDatabase.class.getPackage().getImplementationVersion();
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("INSERT INTO drishti_schema (id, version, written_by, written_at) VALUES (1, ?, ?, ?)")) {
            ps.setInt(1, version);
            ps.setString(2, release == null ? "development" : release);
            ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        } catch (SQLException e) {
            if (stamp(ds).orElse(-1) != version) {                       // two nodes stamping at once: the other one's row is the same
                throw new IllegalStateException("cannot stamp the Drishti database: " + e.getMessage(), e);
            }
        }
    }
}
