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

    List<String> reverse(Connection c, String k, String target, LocalDate asked) throws SQLException {
        LocalDate on = asked == null ? LATEST : asked;
        String match = "jsonb_path_exists(" + doc + ", '$.** ? (@ == $v)', jsonb_build_object('v', ?::text))";
        String sql = effective(k)
                ? "SELECT eid FROM (SELECT DISTINCT ON (" + id + ") " + id + " AS eid, " + doc + " FROM " + table + " WHERE " + kind
                        + " = ? AND " + date + " <= ? ORDER BY " + id + ", " + date + " DESC) latest WHERE " + match + " ORDER BY eid"
                : "SELECT " + id + " FROM " + table + " WHERE " + kind + " = ? AND " + date + " = " + snapshotDate() + " AND " + match
                        + " ORDER BY " + id;
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

    /** Distinct identifiers containing {@code text} (case-insensitive), for the command-line suggestions. */
    List<String[]> search(Connection c, String k, String text, int limit) throws SQLException {
        String sql = "SELECT DISTINCT " + kind + ", " + id + " FROM " + table + " WHERE " + (k == null ? "" : kind + " = ? AND ")
                + "LOWER(" + id + ") LIKE ? ORDER BY " + id + " LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (k != null) {
                ps.setString(i++, k);
            }
            ps.setString(i++, "%" + text.toLowerCase(java.util.Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%");
            ps.setInt(i, limit);
            List<String[]> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
            return out;
        }
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
