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
package com.ash.drishti.plugin.duckdb;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * How a data domain is kept in a DuckDB file for a million entities a business day, shared by {@link DuckDbLoader}
 * (which writes it) and the connector (which reads it):
 *
 * <ul>
 *   <li>{@code <schema>.entities (kind, id, business_date DATE, doc VARCHAR, <promoted columns>)}, one schema per data
 *       domain, rows written in {@code (kind, business_date, id)} order: DuckDB keeps a minimum and a maximum of every
 *       column for each row group of 122,880 rows (its zone maps), so a query naming a kind, a day and an id reads one
 *       row group and a query naming a day reads only that day's row groups.</li>
 *   <li>Promoted columns: each path a pack declares in {@code layout.<kind>.columns} is a column of its own, named as
 *       in Delta Lake ({@code counterparty.id} is {@code counterparty__id}), {@code DOUBLE} for numbers and
 *       {@code VARCHAR} otherwise; being columnar, a day of them is read without touching the documents.</li>
 *   <li>{@code <schema>.entity_dates (kind, business_date, rows, loaded_at)}: each kind's business dates, so the
 *       connector never asks the large table which days exist.</li>
 * </ul>
 */
final class DuckDbLayout {

    static final String TABLE = "entities";
    static final String DATES = "entity_dates";
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private DuckDbLayout() {
    }

    /** The column a promoted path is kept in: {@code counterparty.id} is {@code counterparty__id}. */
    static String column(String path) {
        String c = path.replace(".", "__");
        if (!NAME.matcher(c).matches() || c.length() > 63) {
            throw new IllegalArgumentException("not a usable column name: " + path);
        }
        return c;
    }

    /** A promoted column, quoted ({@code "tradeId"}). */
    static String quoted(String path) {
        return "\"" + column(path) + "\"";
    }

    /** A schema name from a data domain ({@code market-data} is {@code market_data}), checked to be plain. */
    static String schema(String domain) {
        String s = domain.replace("-", "_").toLowerCase(Locale.ROOT);
        if (!NAME.matcher(s).matches()) {
            throw new IllegalArgumentException("not a usable schema name: " + domain);
        }
        return s;
    }

    /** {@code catalog.schema} or {@code schema}, checked to be plain identifiers. */
    static String checked(String name) {
        for (String part : name.split("\\.", -1)) {
            if (!NAME.matcher(part).matches()) {
                throw new IllegalArgumentException("not a plain SQL identifier: " + name);
            }
        }
        return name;
    }

    /** The DDL of a domain's table with the given promoted columns ({column: true for a number}), in a database. */
    static String createTable(String qualifiedSchema, Map<String, Boolean> columns) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(qualifiedSchema).append('.').append(TABLE)
                .append(" (kind VARCHAR NOT NULL, id VARCHAR NOT NULL, business_date DATE NOT NULL, doc VARCHAR NOT NULL");
        columns.forEach((c, number) -> sql.append(", \"").append(c).append("\" ").append(number ? "DOUBLE" : "VARCHAR"));
        return sql.append(')').toString();
    }

    /** The DDL of a domain's dates table. */
    static String createDates(String qualifiedSchema) {
        return "CREATE TABLE " + qualifiedSchema + "." + DATES
                + " (kind VARCHAR NOT NULL, business_date DATE NOT NULL, rows BIGINT NOT NULL, loaded_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (kind, business_date))";
    }

    /**
     * A schema of a database, fully qualified ({@code "drishti".trading}): the database (catalog) DuckDB names after the
     * file may have the same name as a schema, and only a three-part name is never ambiguous.
     */
    static String qualified(String database, String schema) {
        return "\"" + database.replace("\"", "\"\"") + "\"." + schema;
    }

    /** The domain schemas of a database that hold an entities table. */
    static List<String> schemas(Connection c, String database) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT schema_name FROM duckdb_tables() WHERE table_name = ? AND database_name = ? ORDER BY 1")) {
            ps.setString(1, TABLE);
            ps.setString(2, database);
            List<String> out = new java.util.ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    /** A table's promoted columns and whether each is a number: {column name: true for DOUBLE}. */
    static Map<String, Boolean> columns(Connection c, String database, String schema, String table) throws SQLException {
        Map<String, Boolean> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT column_name, data_type FROM duckdb_columns() WHERE schema_name = ? AND table_name = ? "
                + "AND database_name = ? ORDER BY column_index")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, database);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), "DOUBLE".equals(rs.getString(2)));
                }
            }
        }
        List.of("kind", "id", "business_date", "doc", "seq").forEach(out::remove);
        return out;
    }

    /** True when the schema has the dates table. */
    static boolean hasDates(Connection c, String database, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM duckdb_tables() WHERE schema_name = ? AND table_name = ? AND database_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, DATES);
            ps.setString(3, database);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    /**
     * The schema's dates as a table to select {@code (kind, business_date, rows, loaded_at)} from: the dates table, or
     * for a table written without one, its days counted (one grouped scan of two columns).
     */
    static String datesSource(Connection c, String database, String schema) throws SQLException {
        String q = qualified(database, schema);
        return hasDates(c, database, schema) ? q + "." + DATES
                : "(SELECT kind, business_date, count(*) AS rows, now() AS loaded_at FROM " + q + "." + TABLE + " GROUP BY ALL)";
    }

    /** Each kind's business dates. */
    static Map<String, NavigableSet<LocalDate>> dates(Connection c, String database, String schema) throws SQLException {
        Map<String, NavigableSet<LocalDate>> out = new TreeMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT kind, CAST(business_date AS VARCHAR) FROM " + datesSource(c, database, schema))) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString(1), k -> new TreeSet<>()).add(LocalDate.parse(rs.getString(2)));
            }
        }
        return out;
    }

    /** When the newest load of the schema finished (the dates table), or null. */
    static Instant loadedAt(Connection c, String database, String schema) throws SQLException {
        if (!hasDates(c, database, schema)) {
            return null;
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT CAST(epoch_ms(MAX(loaded_at)) AS BIGINT) FROM " + qualified(database, schema) + "." + DATES)) {
            if (rs.next()) {
                long ms = rs.getLong(1);
                return rs.wasNull() ? null : Instant.ofEpochMilli(ms);
            }
            return null;
        }
    }
}
