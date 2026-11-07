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
package com.ash.drishti.nativ.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The native libraries the server needs and that have no Java fallback, by the product owner's decision (DRISHTI_NATIVE.md sections 2.1
 * and 2.2): DuckDB (the BI engine, the SQL service, the pivot executor) and SQLite (the default database of a single server). Each is
 * probed once through JDBC, by opening an in-memory database and asking its version, so the native library really loads; the result is
 * the registry's entry for Health and Admin -> Engines ({@code native.duckdb}, {@code native.sqlite}). Nothing here names a driver class:
 * the drivers are found on the class path through JDBC.
 */
public final class RequiredLibraries {

    /**
     * One required library.
     *
     * @param name {@code duckdb} or {@code sqlite}
     * @param loaded the library loaded and answered
     * @param version its version, when loaded
     * @param reason why it did not load, or null
     */
    public record Status(String name, boolean loaded, String version, String reason) {

        /** For Health and Admin: {@code status} is {@code OK}, or {@code DOWN} (required, no fallback). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", loaded ? "OK" : "DOWN");
            m.put("required", true);
            m.put("fallback", "none (required)");
            m.put("version", version);
            m.put("reason", reason);
            m.put("line", line());
            return m;
        }

        /** One line for the log and the Native libraries row. */
        public String line() {
            String label = "duckdb".equals(name) ? "DuckDB" : "SQLite";
            return loaded ? label + " " + version + " (required, no fallback)" : label + " cannot load (required, no fallback): " + reason;
        }
    }

    private static volatile List<Status> probed;

    private RequiredLibraries() {
    }

    /** DuckDB and SQLite, probed on the first call and remembered. */
    public static List<Status> status() {
        List<Status> p = probed;
        if (p == null) {
            synchronized (RequiredLibraries.class) {
                if (probed == null) {
                    probed = List.of(probe("duckdb", "jdbc:duckdb:", "SELECT version()"),
                            probe("sqlite", "jdbc:sqlite::memory:", "SELECT sqlite_version()"));
                }
                p = probed;
            }
        }
        return p;
    }

    /** The entries as Health and Admin show them, keyed by name. */
    public static Map<String, Map<String, Object>> toMap() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (Status s : status()) {
            m.put(s.name(), s.toMap());
        }
        return m;
    }

    /** Opens an in-memory database at {@code url} and asks {@code versionSql}; any failure, a native link error included, is the reason. */
    static Status probe(String name, String url, String versionSql) {
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(versionSql)) {
            String v = rs.next() ? rs.getString(1) : null;
            return new Status(name, true, v == null ? "?" : v.replaceFirst("^v", ""), null);
        } catch (Exception | LinkageError e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (m.startsWith("No suitable driver")) {
                m = "no JDBC driver for " + url + " on the class path";
            }
            return new Status(name, false, null, m.replaceAll("\\s+", " "));
        }
    }
}
