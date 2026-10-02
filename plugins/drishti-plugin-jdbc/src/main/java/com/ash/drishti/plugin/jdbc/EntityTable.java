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
package com.ash.drishti.plugin.jdbc;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Table mode: every kind of a data domain in one PostgreSQL table, a row per entity per business date,
 * {@code (kind, id, business_date, doc jsonb)}. A {@code snapshot} kind reads the newest date on or before the one
 * asked (within the lookback) and an entity missing from it is gone; an {@code effective} kind reads each entity's
 * last row on or before the date. Reverse lookups use PostgreSQL's SQL/JSON path over the whole document. The table
 * and column names come from settings and are checked to be plain identifiers, so they cannot carry SQL.
 */
final class EntityTable {

    /** A document and the business date it is for. */
    record Hit(String json, LocalDate date) {}

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");
    private static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    private final String table;
    private final String kind;
    private final String id;
    private final String date;
    private final String doc;
    private final Map<String, String> modes;
    private final int lookbackDays;

    EntityTable(String table, String kindCol, String idCol, String dateCol, String docCol, Map<String, String> modes, int lookbackDays) {
        for (String n : new String[] {table, kindCol, idCol, dateCol, docCol}) {
            if (!IDENT.matcher(n).matches()) {
                throw new IllegalArgumentException("not a plain SQL identifier: " + n);
            }
        }
        this.table = table;
        this.kind = kindCol;
        this.id = idCol;
        this.date = dateCol;
        this.doc = docCol;
        this.modes = Map.copyOf(modes);
        this.lookbackDays = lookbackDays;
    }

    private boolean effective(String k) {
        return "effective".equals(modes.get(k));
    }

    /** The newest business date of {@code k} on or before {@code on} (snapshot kinds), as a sub-select. */
    private String snapshotDate() {
        return "(SELECT MAX(" + date + ") FROM " + table + " WHERE " + kind + " = ? AND " + date + " <= ? AND " + date + " >= ?)";
    }

    Optional<Hit> fetch(Connection c, String k, String entity, LocalDate asked) throws SQLException {
        LocalDate on = asked == null ? LATEST : asked;
        String sql = effective(k)
                ? "SELECT " + doc + "::text, " + date + " FROM " + table + " WHERE " + kind + " = ? AND " + id + " = ? AND " + date
                        + " <= ? ORDER BY " + date + " DESC LIMIT 1"
                : "SELECT " + doc + "::text, " + date + " FROM " + table + " WHERE " + kind + " = ? AND " + id + " = ? AND " + date
                        + " = " + snapshotDate();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, k);
            ps.setString(2, entity);
            if (effective(k)) {
                ps.setDate(3, Date.valueOf(on));
            } else {
                ps.setString(3, k);
                ps.setDate(4, Date.valueOf(on));
                ps.setDate(5, Date.valueOf(asked == null ? LocalDate.MIN.plusYears(10000) : on.minusDays(lookbackDays)));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Hit(rs.getString(1), rs.getDate(2).toLocalDate())) : Optional.empty();
            }
        }
    }

    /**
     * Entities of a kind whose document mentions {@code target} anywhere, reading at most {@code maxRows} documents (a
     * kind of millions a day is answered from its promoted link columns instead; see {@link TableCatalog}).
     */
    List<String> reverse(Connection c, String k, String target, LocalDate asked, int maxRows) throws SQLException {
        LocalDate on = asked == null ? LATEST : asked;
        String match = "jsonb_path_exists(" + doc + ", '$.** ? (@ == $v)', jsonb_build_object('v', ?::text))";
        String sql = effective(k)
                ? "SELECT eid FROM (SELECT DISTINCT ON (" + id + ") " + id + " AS eid, " + doc + " FROM " + table + " WHERE " + kind
                        + " = ? AND " + date + " <= ? ORDER BY " + id + ", " + date + " DESC LIMIT " + maxRows + ") latest WHERE " + match + " ORDER BY eid"
                : "SELECT eid FROM (SELECT " + id + " AS eid, " + doc + " FROM " + table + " WHERE " + kind + " = ? AND " + date + " = " + snapshotDate()
                        + " LIMIT " + maxRows + ") day WHERE " + match + " ORDER BY eid";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, k);
            if (effective(k)) {
                ps.setDate(i++, Date.valueOf(on));
            } else {
                ps.setString(i++, k);
                ps.setDate(i++, Date.valueOf(on));
                ps.setDate(i++, Date.valueOf(asked == null ? LocalDate.MIN.plusYears(10000) : on.minusDays(lookbackDays)));
            }
            ps.setString(i, target);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    /** The schema of the table ({@code trading} for {@code trading.entities}; {@code public} without one). */
    String schema() {
        return table.contains(".") ? table.substring(0, table.indexOf('.')) : "public";
    }

    /** The table's name without its schema. */
    String name() {
        return table.contains(".") ? table.substring(table.indexOf('.') + 1) : table;
    }

    /** True when the schema has the {@link PostgresLayout} dates table (each kind's business dates, kept by the loader). */
    boolean hasDates(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, schema() + "." + PostgresLayout.DATES);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * Each kind's business dates and when the newest load finished: from the dates table when the loader keeps one,
     * else from the index with a skip scan (one probe per date, never a scan of the rows).
     */
    Map<String, java.util.NavigableSet<LocalDate>> dates(Connection c, boolean fromTable, java.util.Collection<String> kinds) throws SQLException {
        Map<String, java.util.NavigableSet<LocalDate>> out = new java.util.TreeMap<>();
        if (fromTable) {
            try (PreparedStatement ps = c.prepareStatement("SELECT kind, business_date FROM " + schema() + "." + PostgresLayout.DATES);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1), k -> new java.util.TreeSet<>()).add(rs.getDate(2).toLocalDate());
                }
            }
            return out;
        }
        String sql = "WITH RECURSIVE d AS (SELECT MIN(" + date + ") AS v FROM " + table + " WHERE " + kind + " = ? UNION ALL SELECT (SELECT MIN("
                + date + ") FROM " + table + " WHERE " + kind + " = ? AND " + date + " > d.v) FROM d WHERE d.v IS NOT NULL) SELECT v FROM d WHERE v IS NOT NULL";
        for (String k : kinds) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, k);
                ps.setString(2, k);
                try (ResultSet rs = ps.executeQuery()) {
                    java.util.NavigableSet<LocalDate> ds = new java.util.TreeSet<>();
                    while (rs.next()) {
                        ds.add(rs.getDate(1).toLocalDate());
                    }
                    out.put(k, ds);
                }
            }
        }
        return out;
    }

    /** When the newest load finished (the dates table), or null. */
    java.time.Instant loadedAt(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT MAX(loaded_at) FROM " + schema() + "." + PostgresLayout.DATES);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null;
        }
    }

    /** When a kind's business date was last loaded (the dates table), or null when it is not recorded. */
    java.time.Instant dayLoadedAt(Connection c, String k, LocalDate day) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT loaded_at FROM " + schema() + "." + PostgresLayout.DATES + " WHERE kind = ? AND business_date = ?")) {
            ps.setString(1, k);
            ps.setDate(2, Date.valueOf(day));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null;
            }
        }
    }

    /** One entity on a known business date: a primary-key lookup in that date's partition. */
    Optional<Hit> fetchOn(Connection c, String k, String entity, LocalDate day) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + doc + "::text FROM " + table + " WHERE " + kind + " = ? AND " + id + " = ? AND "
                + date + " = ?")) {
            ps.setString(1, k);
            ps.setString(2, entity);
            ps.setDate(3, Date.valueOf(day));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Hit(rs.getString(1), day)) : Optional.empty();
            }
        }
    }

    /** Streams a large result in pieces (PostgreSQL sends the rows in batches only inside a transaction). */
    private static <T> T streaming(Connection c, SqlCall<T> call) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            return call.run();
        } finally {
            c.rollback();                                     // read only: nothing to keep
            c.setAutoCommit(auto);
        }
    }

    @FunctionalInterface
    interface SqlCall<T> {
        T run() throws SQLException;
    }

    /** A business date's ids of a kind (snapshot), or every id it ever had (effective, day null), from the index. */
    void ids(Connection c, String k, LocalDate day, java.util.function.Consumer<String> each) throws SQLException {
        String sql = day == null ? "SELECT DISTINCT " + id + " FROM " + table + " WHERE " + kind + " = ?"
                : "SELECT " + id + " FROM " + table + " WHERE " + kind + " = ? AND " + date + " = ?";
        streaming(c, () -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setFetchSize(50_000);
                ps.setString(1, k);
                if (day != null) {
                    ps.setDate(2, Date.valueOf(day));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        each.accept(rs.getString(1));
                    }
                }
            }
            return null;
        });
    }

    /** {@code n - 1} ids that cut a day's ids into {@code n} ranges of about equal size (read from the index). */
    List<String> boundaries(Connection c, String k, LocalDate day, int n) throws SQLException {
        if (n <= 1) {
            return List.of();
        }
        StringBuilder fractions = new StringBuilder();
        for (int i = 1; i < n; i++) {
            fractions.append(i == 1 ? "" : ",").append((double) i / n);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT percentile_disc(ARRAY[" + fractions + "]) WITHIN GROUP (ORDER BY " + id + ") FROM "
                + table + " WHERE " + kind + " = ? AND " + date + " = ?")) {
            ps.setString(1, k);
            ps.setDate(2, Date.valueOf(day));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getArray(1) == null) {
                    return List.of();
                }
                return java.util.Arrays.stream((Object[]) rs.getArray(1).getArray()).map(String::valueOf).distinct().toList();
            }
        }
    }

    /** Receives one row of a day's promoted columns. */
    @FunctionalInterface
    interface ColumnRow {
        void accept(ResultSet rs) throws SQLException;
    }

    /**
     * A day's ids and promoted columns for ids in {@code [from, to)} (either may be null: unbounded), streamed; no
     * document is read.
     */
    void columns(Connection c, String k, LocalDate day, String from, String to, List<String> columns, ColumnRow each) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT ").append(id);
        columns.forEach(col -> sql.append(", \"").append(col).append('"'));
        sql.append(" FROM ").append(table).append(" WHERE ").append(kind).append(" = ? AND ").append(date).append(" = ?");
        if (from != null) {
            sql.append(" AND ").append(id).append(" >= ?");
        }
        if (to != null) {
            sql.append(" AND ").append(id).append(" < ?");
        }
        streaming(c, () -> {
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                ps.setFetchSize(20_000);
                int i = 1;
                ps.setString(i++, k);
                ps.setDate(i++, Date.valueOf(day));
                if (from != null) {
                    ps.setString(i++, from);
                }
                if (to != null) {
                    ps.setString(i, to);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        each.accept(rs);
                    }
                }
            }
            return null;
        });
    }

    List<String> kinds(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT " + kind + " FROM " + table + " ORDER BY 1");
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }
}
