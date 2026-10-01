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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How a data domain is kept in PostgreSQL for a million entities a business day over years, shared by
 * {@link PostgresLoader} (which writes it) and the connector's table mode (which reads it):
 *
 * <ul>
 *   <li>{@code <schema>.entities (kind, id, business_date, doc jsonb, <promoted columns>)}, partitioned by range of
 *       {@code business_date}, one partition a month ({@code entities_y2026m09}): a day's rows sit in one partition,
 *       and dropping a month of history is dropping a table. The document is compressed with LZ4.</li>
 *   <li>Promoted columns: each path a pack declares in {@code layout.<kind>.columns} is a column of its own, named as
 *       in Delta Lake ({@code counterparty.id} is {@code "counterparty__id"}), {@code double precision} for numbers and
 *       {@code text} otherwise, so searches and aggregates read narrow columns and never the documents.</li>
 *   <li>Indexes: the primary key {@code (kind, id, business_date)} serves single reads; {@code (kind, business_date,
 *       id)} gives a day's ids from the index alone (type-ahead) and a day's rows by id range (columns in parallel).</li>
 *   <li>{@code <schema>.entity_dates (kind, business_date, rows, loaded_at)}: each kind's business dates, so the
 *       connector never asks the large table which days exist.</li>
 * </ul>
 */
final class PostgresLayout {

    static final String TABLE = "entities";
    static final String DATES = "entity_dates";
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private PostgresLayout() {
    }

    /** The column a promoted path is kept in: {@code counterparty.id} is {@code counterparty__id}. */
    static String column(String path) {
        String c = path.replace(".", "__");
        if (!NAME.matcher(c).matches() || c.length() > 63) {
            throw new IllegalArgumentException("not a usable column name: " + path);
        }
        return c;
    }

    /** A promoted column, quoted (it keeps its case: {@code "tradeId"}). */
    static String quoted(String path) {
        return "\"" + column(path) + "\"";
    }

    /** A schema name from a data domain ({@code market-data} is {@code market_data}), checked to be plain. */
    static String schema(String domain) {
        String s = domain.replace("-", "_").toLowerCase(java.util.Locale.ROOT);
        if (!NAME.matcher(s).matches()) {
            throw new IllegalArgumentException("not a usable schema name: " + domain);
        }
        return s;
    }

    /** The partition holding {@code day}: one a month. */
    static String partition(LocalDate day) {
        return String.format(java.util.Locale.ROOT, "%s_y%04dm%02d", TABLE, day.getYear(), day.getMonthValue());
    }

    /** True when the schema's table exists and is partitioned (written by this layout), false when it is a plain table. */
    static Boolean partitioned(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?")) {
            ps.setString(1, schema);
            ps.setString(2, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? "p".equals(rs.getString(1)) : null;
            }
        }
    }

    /** Creates the schema, the partitioned table, its indexes and the dates table when they are missing. */
    static void create(Connection c, String schema) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute("CREATE TABLE IF NOT EXISTS " + schema + "." + TABLE + " (kind text NOT NULL, id text NOT NULL, business_date date NOT NULL, "
                    + "doc jsonb COMPRESSION lz4 NOT NULL, PRIMARY KEY (kind, id, business_date)) PARTITION BY RANGE (business_date)");
            st.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_day_ids ON " + schema + "." + TABLE + " (kind, business_date, id)");
            st.execute("CREATE TABLE IF NOT EXISTS " + schema + "." + DATES + " (kind text NOT NULL, business_date date NOT NULL, rows bigint NOT NULL, "
                    + "loaded_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (kind, business_date))");
        }
    }

    /** Drops the domain's table and dates (a reload from scratch, or a plain table of the old layout). */
    static void drop(Connection c, String schema) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + schema + "." + TABLE + " CASCADE");
            st.execute("DROP TABLE IF EXISTS " + schema + "." + DATES);
        }
    }

    /** The month's partition for {@code day}, created when missing. */
    static void ensurePartition(Connection c, String schema, LocalDate day) throws SQLException {
        LocalDate from = day.withDayOfMonth(1);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + schema + "." + partition(day) + " PARTITION OF " + schema + "." + TABLE
                    + " FOR VALUES FROM ('" + from + "') TO ('" + from.plusMonths(1) + "')");
        }
    }

    /** The table's promoted columns and whether each is a number: {column name: true for double precision}. */
    static Map<String, Boolean> columns(Connection c, String schema, String table) throws SQLException {
        Map<String, Boolean> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), "double precision".equals(rs.getString(2)));
                }
            }
        }
        List.of("kind", "id", "business_date", "doc").forEach(out::remove);
        return out;
    }

    /** Adds a promoted column (a cheap change: nullable, no default, so no row is rewritten). */
    static void addColumn(Connection c, String schema, String path, boolean number) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + schema + "." + TABLE + " ADD COLUMN IF NOT EXISTS " + quoted(path) + (number ? " double precision" : " text"));
        }
    }

    /** Records a kind's business date and how many rows it has. */
    static void recordDate(Connection c, String schema, String kind, LocalDate day, long rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + schema + "." + DATES + " (kind, business_date, rows) VALUES (?, ?, ?) "
                + "ON CONFLICT (kind, business_date) DO UPDATE SET rows = EXCLUDED.rows, loaded_at = now()")) {
            ps.setString(1, kind);
            ps.setDate(2, java.sql.Date.valueOf(day));
            ps.setLong(3, rows);
            ps.executeUpdate();
        }
    }

    /** The monthly partitions whose whole month ends before {@code before}, oldest first. */
    static List<String> partitionsBefore(Connection c, String schema, LocalDate before) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT child.relname FROM pg_inherits i JOIN pg_class parent ON parent.oid = i.inhparent "
                + "JOIN pg_class child ON child.oid = i.inhrelid JOIN pg_namespace n ON n.oid = parent.relnamespace "
                + "WHERE n.nspname = ? AND parent.relname = ? ORDER BY child.relname")) {
            ps.setString(1, schema);
            ps.setString(2, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    var m = Pattern.compile(TABLE + "_y(\\d{4})m(\\d{2})").matcher(name);
                    if (m.matches() && LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1).plusMonths(1).compareTo(before) <= 0) {
                        out.add(name);
                    }
                }
            }
        }
        return out;
    }
}
