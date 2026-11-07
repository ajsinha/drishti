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

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Copies a Drishti database into a fresh one built from the complete schema ({@code drishti.py db copy}; docs/admin/OPERATIONS.md,
 * "Upgrading the Drishti database"). It is the one way to upgrade the schema and to switch between SQLite and PostgreSQL: there are no
 * migrations. Every table of the target's schema is copied in file order (parents first) and in batches; the columns both sides have are
 * copied by name, a column only the new schema has takes its declared default, and a column only the old database has is dropped only
 * with {@code dropColumns} (otherwise the copy is refused, naming them). Each table's row count is checked on both sides; any difference
 * fails the copy. The target is stamped with the schema version; the source is never changed, so going back is pointing
 * config/db/database.yaml at it again.
 *
 * <p>The target must be fresh: a SQLite file that does not exist yet, or a PostgreSQL database whose Drishti tables are empty. The
 * source is read as it is (no schema is applied to it), so stop the servers that use it first. {@code check} reads the source and
 * reports what would be copied, writing nothing.
 */
public final class DatabaseCopy {

    private static final int BATCH = 1_000;

    /** One table's outcome: rows read and written, the columns the new schema adds and the ones the copy drops. */
    public record Table(String name, long sourceRows, long targetRows, List<String> dropped, List<String> added, boolean inSource) {
    }

    /** The whole outcome; {@code checked} when nothing was written ({@code --check}). */
    public record Report(String from, String to, List<Table> tables, boolean checked, int schemaVersion) {
        /** Every table holds as many rows in the target as in the source (always true for a check, which writes nothing). */
        public boolean verified() {
            return checked || tables.stream().allMatch(t -> t.sourceRows() == t.targetRows());
        }

        public long rows() {
            return tables.stream().mapToLong(t -> checked ? t.sourceRows() : t.targetRows()).sum();
        }
    }

    private final UnaryOperator<String> site;

    /** {@code site} answers the site's pool keys ({@code drishti.db.pool.*}); the copy tool passes none. */
    public DatabaseCopy(UnaryOperator<String> site) {
        this.site = site;
    }

    /** Copies {@code from} into the fresh {@code to}; with {@code check}, only reports what it would copy. */
    public Report copy(DatabaseSettings from, DatabaseSettings to, boolean check, boolean dropColumns) {
        if (from.jdbcUrl().equals(to.jdbcUrl())) {
            throw new IllegalArgumentException("--from and --to are the same database (" + from.describe() + ")");
        }
        if (from.sqlite() && !Files.isRegularFile(from.sqliteFile())) {
            throw new IllegalArgumentException("the source database " + from.sqliteFile() + " does not exist");
        }
        if (to.sqlite() && Files.exists(to.sqliteFile())) {
            throw new IllegalArgumentException("the target " + to.sqliteFile() + " exists; the copy goes into a fresh database (move it away or name a new file)");
        }
        Map<String, List<String>> schema = new LinkedHashMap<>(DatabaseSchema.tables(to.sqlite()));
        schema.remove("drishti_schema");                                     // the target's stamp is its own, written last
        int version = DatabaseSchema.version(to.sqlite());
        try (HikariDataSource src = DrishtiDatabase.pool(from, site, "drishti-db-copy-from"); Connection in = src.getConnection()) {
            Map<String, Set<String>> have = DatabaseSchema.columns(in, schema.keySet());
            if (have.isEmpty()) {
                throw new IllegalArgumentException("the source " + from.describe() + " holds no Drishti tables");
            }
            List<String> dropping = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : have.entrySet()) {
                e.getValue().stream().filter(c -> !schema.get(e.getKey()).contains(c)).sorted().forEach(c -> dropping.add(e.getKey() + "." + c));
            }
            if (!dropping.isEmpty() && !dropColumns && !check) {
                throw new IllegalArgumentException("the new schema no longer has " + String.join(", ", dropping)
                        + "; their values would be lost. Run again with --drop-columns to copy without them");
            }
            if (check) {
                List<Table> tables = new ArrayList<>();
                for (Map.Entry<String, List<String>> t : schema.entrySet()) {
                    Set<String> cols = have.get(t.getKey());
                    tables.add(cols == null ? new Table(t.getKey(), 0, 0, List.of(), List.of(), false)
                            : new Table(t.getKey(), count(in, t.getKey()), 0, cols.stream().filter(c -> !t.getValue().contains(c)).sorted().toList(),
                                    t.getValue().stream().filter(c -> !cols.contains(c)).toList(), true));
                }
                return new Report(from.describe(), to.describe(), tables, true, version);
            }
            if (!from.sqlite()) {
                in.setAutoCommit(false);                                      // PostgreSQL streams a result set only inside a transaction
            }
            try (HikariDataSource dst = DrishtiDatabase.pool(to, site, "drishti-db-copy-to")) {
                DatabaseSchema.apply(dst, to.sqlite());
                List<Table> tables = new ArrayList<>();
                try (Connection out = dst.getConnection()) {
                    refuseFilled(out, schema.keySet(), to);
                    if (to.sqlite()) {
                        try (Statement st = out.createStatement()) {
                            st.execute("PRAGMA foreign_keys = OFF");             // rows arrive table by table; the source was consistent
                        }
                    }
                    for (Map.Entry<String, List<String>> t : schema.entrySet()) {
                        tables.add(copyTable(in, out, t.getKey(), t.getValue(), have.get(t.getKey()), to.sqlite()));
                    }
                    if (to.sqlite()) {
                        try (Statement st = out.createStatement()) {
                            st.execute("PRAGMA foreign_keys = ON");
                        }
                    }
                }
                Report r = new Report(from.describe(), to.describe(), tables, false, version);
                if (!r.verified()) {
                    throw new IllegalStateException("row counts differ after the copy: " + tables.stream().filter(x -> x.sourceRows() != x.targetRows())
                            .map(x -> x.name() + " " + x.sourceRows() + " -> " + x.targetRows()).toList());
                }
                DrishtiDatabase.writeStamp(dst, version);
                return r;
            } finally {
                if (!from.sqlite()) {
                    in.rollback();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("database copy failed: " + e.getMessage(), e);
        }
    }

    private static void refuseFilled(Connection out, Iterable<String> tables, DatabaseSettings to) throws SQLException {
        for (String t : tables) {
            if (count(out, t) > 0) {
                throw new IllegalArgumentException("the target " + to.describe() + " is not fresh: " + t + " has rows. Copy into an empty database");
            }
        }
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static Table copyTable(Connection in, Connection out, String table, List<String> want, Set<String> have, boolean toSqlite)
            throws SQLException {
        if (have == null) {
            return new Table(table, 0, 0, List.of(), List.of(), false);       // a table the old schema did not have: empty in the new one
        }
        List<String> cols = want.stream().filter(have::contains).toList();
        List<String> added = want.stream().filter(c -> !have.contains(c)).toList();
        List<String> dropped = have.stream().filter(c -> !want.contains(c)).sorted().toList();
        Map<String, Integer> targetTypes = types(out, table, cols);
        String select = "SELECT " + String.join(", ", cols) + " FROM " + table;
        String insert = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES (" + "?, ".repeat(cols.size() - 1) + "?)";
        boolean auto = out.getAutoCommit();
        out.setAutoCommit(false);
        try (Statement st = in.createStatement(); PreparedStatement ps = out.prepareStatement(insert)) {
            st.setFetchSize(BATCH);
            try (ResultSet r = st.executeQuery(select)) {
                ResultSetMetaData md = r.getMetaData();
                int n = 0;
                while (r.next()) {
                    for (int i = 0; i < cols.size(); i++) {
                        bind(ps, i + 1, r, i + 1, md.getColumnType(i + 1), targetTypes.get(cols.get(i)));
                    }
                    ps.addBatch();
                    if (++n % BATCH == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            out.commit();
        } catch (SQLException e) {
            out.rollback();
            throw new SQLException("table " + table + ": " + e.getMessage(), e.getSQLState(), e);
        } finally {
            out.setAutoCommit(auto);
        }
        if (!toSqlite) {
            resetIdentities(out, table);
        }
        return new Table(table, count(in, table), count(out, table), dropped, added, true);
    }

    /** The target columns' JDBC types: what the value must become (PostgreSQL types by declaration; SQLite by its declared name). */
    private static Map<String, Integer> types(Connection c, String table, List<String> cols) throws SQLException {
        Map<String, Integer> out = new HashMap<>();
        try (ResultSet r = c.getMetaData().getColumns(null, null, table, null)) {
            while (r.next()) {
                String name = r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                String typeName = String.valueOf(r.getString("TYPE_NAME")).toUpperCase(Locale.ROOT);
                int type = r.getInt("DATA_TYPE");
                if (typeName.startsWith("TIMESTAMP")) {
                    type = Types.TIMESTAMP;
                } else if (typeName.equals("DATE")) {
                    type = Types.DATE;
                } else if (typeName.startsWith("BOOL")) {
                    type = Types.BOOLEAN;
                } else if (typeName.equals("BYTEA") || typeName.equals("BLOB")) {
                    type = Types.BINARY;
                }
                out.put(name, type);
            }
        }
        for (String col : cols) {
            out.putIfAbsent(col, Types.OTHER);
        }
        return out;
    }

    /**
     * One value, read in the target's terms: SQLite keeps booleans as 0/1 and times as epoch milliseconds or text, PostgreSQL as their
     * own types, and each driver converts on the typed getter.
     */
    private static void bind(PreparedStatement ps, int to, ResultSet r, int from, int sourceType, int targetType) throws SQLException {
        switch (targetType) {
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                java.sql.Timestamp v = r.getTimestamp(from);
                if (r.wasNull()) {
                    ps.setNull(to, Types.TIMESTAMP);
                } else {
                    ps.setTimestamp(to, v);
                }
            }
            case Types.DATE -> {
                java.sql.Date v = r.getDate(from);
                if (r.wasNull()) {
                    ps.setNull(to, Types.DATE);
                } else {
                    ps.setDate(to, v);
                }
            }
            case Types.BOOLEAN, Types.BIT -> {
                boolean v = r.getBoolean(from);
                if (r.wasNull()) {
                    ps.setNull(to, Types.BOOLEAN);
                } else {
                    ps.setBoolean(to, v);
                }
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                byte[] v = r.getBytes(from);
                if (v == null) {
                    ps.setNull(to, Types.BINARY);
                } else {
                    ps.setBytes(to, v);
                }
            }
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT -> {
                long v = sourceType == Types.BOOLEAN || sourceType == Types.BIT ? (r.getBoolean(from) ? 1 : 0) : r.getLong(from);
                if (r.wasNull()) {
                    ps.setNull(to, Types.BIGINT);
                } else {
                    ps.setLong(to, v);
                }
            }
            case Types.DOUBLE, Types.FLOAT, Types.REAL, Types.NUMERIC, Types.DECIMAL -> {
                double v = r.getDouble(from);
                if (r.wasNull()) {
                    ps.setNull(to, Types.DOUBLE);
                } else {
                    ps.setDouble(to, v);
                }
            }
            default -> {
                String v = r.getString(from);
                if (v == null) {
                    ps.setNull(to, Types.VARCHAR);
                } else {
                    ps.setString(to, v);
                }
            }
        }
    }

    /** PostgreSQL identity columns continue after the copied keys (the copy wrote the keys itself). */
    private static void resetIdentities(Connection out, String table) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = out.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? AND is_identity = 'YES'")) {
            ps.setString(1, table);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    ids.add(r.getString(1));
                }
            }
        }
        for (String col : ids) {
            try (Statement st = out.createStatement()) {
                st.execute("SELECT setval(pg_get_serial_sequence('" + table + "', '" + col + "'), COALESCE((SELECT MAX(" + col + ") FROM " + table
                        + "), 0) + 1, false)");
            }
        }
    }

    /** The report as plain lines. */
    public static List<String> lines(Report r) {
        List<String> out = new ArrayList<>();
        out.add((r.checked() ? "would copy " : "copied ") + r.from() + " -> " + r.to() + " (schema version " + r.schemaVersion() + ")");
        for (Table t : r.tables()) {
            String state = !t.inSource() ? "new table" : r.checked() ? "to copy" : t.sourceRows() == t.targetRows() ? "verified"
                    : "DIFFERENT (source " + t.sourceRows() + ")";
            StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "  %-28s %8d rows  %s", t.name(),
                    r.checked() ? t.sourceRows() : t.targetRows(), state));
            if (t.inSource() && !t.added().isEmpty()) {
                s.append("; new columns ").append(String.join(", ", t.added()));
            }
            if (!t.dropped().isEmpty()) {
                s.append(r.checked() ? "; columns to drop (needs --drop-columns) " : "; dropped columns ").append(String.join(", ", t.dropped()));
            }
            out.add(s.toString());
        }
        out.add(String.format(Locale.ROOT, r.checked() ? "%d tables, %d rows to copy; nothing written" : "%d tables, %d rows, every count verified",
                r.tables().size(), r.rows()));
        return out;
    }

    /** The report as a map (JSON). */
    public static Map<String, Object> json(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", r.from());
        m.put("to", r.to());
        m.put("check", r.checked());
        m.put("schemaVersion", r.schemaVersion());
        m.put("verified", r.verified());
        m.put("rows", r.rows());
        List<Map<String, Object>> ts = new ArrayList<>();
        for (Table t : r.tables()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("table", t.name());
            x.put("rows", t.targetRows());
            x.put("sourceRows", t.sourceRows());
            x.put("inSource", t.inSource());
            x.put("added", t.added());
            x.put("dropped", t.dropped());
            ts.add(x);
        }
        m.put("tables", ts);
        return m;
    }
}
