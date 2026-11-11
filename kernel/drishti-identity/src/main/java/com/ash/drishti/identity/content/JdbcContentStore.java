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
package com.ash.drishti.identity.content;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The SQLite and PostgreSQL content store: one table, {@code drishti_content}, from the complete Drishti database schema files
 * ({@code db/schema-sqlite.sql}, {@code db/schema-postgres.sql}; no migrations), applied whole at start. The version is a counter;
 * compare-and-set is a conditional {@code UPDATE} (or an {@code INSERT} racing on the primary key for a create); a delete keeps a tombstone row.
 */
public final class JdbcContentStore implements ContentStore {

    private final HikariDataSource ds;
    private final boolean sqlite;

    public JdbcContentStore(String url, String user, String password, int pool) {
        this.sqlite = url.startsWith("jdbc:sqlite:");
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        if (user != null && !user.isBlank()) {
            c.setUsername(user);
        }
        if (password != null && !password.isBlank()) {
            c.setPassword(password);
        }
        c.setMaximumPoolSize(sqlite ? 1 : Math.max(1, pool));
        c.setPoolName("drishti-content");
        if (sqlite) {
            c.setConnectionInitSql("PRAGMA busy_timeout = 10000");
        }
        this.ds = new HikariDataSource(c);
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource(sqlite ? "db/schema-sqlite.sql" : "db/schema-postgres.sql"));
        schema.setSqlScriptEncoding("UTF-8");
        schema.execute(ds);
    }

    @Override
    public String root() {
        return (sqlite ? "sqlite " : "postgres ") + Pattern.compile("password=[^&;]*").matcher(ds.getJdbcUrl()).replaceAll("password=***");
    }

    @Override
    public Optional<Item> get(String key) {
        ContentStore.check(key);
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT v, version FROM drishti_content WHERE k = ? AND deleted = 0")) {
                s.setString(1, key);
                try (ResultSet r = s.executeQuery()) {
                    return r.next() ? Optional.of(new Item(r.getBytes(1), Long.toString(r.getLong(2)))) : Optional.<Item>empty();
                }
            }
        });
    }

    @Override
    public String put(String key, byte[] bytes, String expectedVersion) {
        ContentStore.check(key);
        long now = System.currentTimeMillis();
        return sql(c -> {
            if (expectedVersion != null) {
                long v = version(key, expectedVersion);
                try (PreparedStatement s = c.prepareStatement(
                        "UPDATE drishti_content SET v = ?, version = version + 1, updated_ms = ? WHERE k = ? AND version = ? AND deleted = 0")) {
                    s.setBytes(1, bytes);
                    s.setLong(2, now);
                    s.setString(3, key);
                    s.setLong(4, v);
                    if (s.executeUpdate() != 1) {
                        throw ContentStore.conflict(key);
                    }
                }
                return Long.toString(v + 1);
            }
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE drishti_content SET v = ?, version = version + 1, updated_ms = ?, deleted = 0 WHERE k = ? AND deleted = 1")) {
                s.setBytes(1, bytes);
                s.setLong(2, now);
                s.setString(3, key);
                if (s.executeUpdate() == 1) {
                    return readVersion(c, key);
                }
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO drishti_content (k, v, version, updated_ms, deleted) VALUES (?, ?, 1, ?, 0)")) {
                s.setString(1, key);
                s.setBytes(2, bytes);
                s.setLong(3, now);
                s.executeUpdate();
                return "1";
            } catch (SQLException e) {
                if (duplicate(e)) {
                    throw ContentStore.conflict(key);
                }
                throw e;
            }
        });
    }

    @Override
    public boolean delete(String key, String expectedVersion) {
        ContentStore.check(key);
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE drishti_content SET v = NULL, version = version + 1, updated_ms = ?, deleted = 1 WHERE k = ? AND version = ? AND deleted = 0")) {
                s.setLong(1, System.currentTimeMillis());
                s.setString(2, key);
                s.setLong(3, version(key, expectedVersion));
                if (s.executeUpdate() == 1) {
                    return true;
                }
            } catch (DrishtiException e) {
                if (!live(c, key)) {
                    return false;
                }
                throw e;
            }
            if (!live(c, key)) {
                return false;
            }
            throw ContentStore.conflict(key);
        });
    }

    private static boolean live(Connection c, String key) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM drishti_content WHERE k = ? AND deleted = 0")) {
            s.setString(1, key);
            try (ResultSet r = s.executeQuery()) {
                return r.next();
            }
        }
    }

    @Override
    public List<String> list(String prefix) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT k FROM drishti_content WHERE deleted = 0 AND substr(k, 1, ?) = ?")) {
                s.setInt(1, prefix.codePointCount(0, prefix.length()));
                s.setString(2, prefix);
                List<String> out = new ArrayList<>();
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        String k = r.getString(1);
                        if (k.startsWith(prefix)) {
                            out.add(k);
                        }
                    }
                }
                out.sort(null);
                return out;
            }
        });
    }

    @Override
    public Changes changes(long since) {
        return sql(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT k, updated_ms FROM drishti_content WHERE updated_ms > ? ORDER BY k")) {
                s.setLong(1, since);
                List<String> out = new ArrayList<>();
                long cursor = since;
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        out.add(r.getString(1));
                        cursor = Math.max(cursor, r.getLong(2));
                    }
                }
                return new Changes(out, cursor);
            }
        });
    }

    @Override
    public void close() {
        ds.close();
    }

    private static long version(String key, String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw ContentStore.conflict(key);                       // not a version this store gave: it cannot be the current one
        }
    }

    private static String readVersion(Connection c, String key) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT version FROM drishti_content WHERE k = ?")) {
            s.setString(1, key);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return Long.toString(r.getLong(1));
            }
        }
    }

    private static boolean duplicate(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return state.equals("23505") || msg.contains("UNIQUE constraint failed") || msg.contains("PRIMARYKEY") || msg.contains("SQLITE_CONSTRAINT");
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T sql(Work<T> w) {
        try (Connection c = ds.getConnection()) {
            return w.run(c);
        } catch (SQLException e) {
            throw new DrishtiException(ErrorCode.CONTENT_UNAVAILABLE, "content store: " + e.getMessage(), e);
        }
    }
}
