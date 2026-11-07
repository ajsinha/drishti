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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The complete schema of the Drishti database: exactly two files, {@code db/schema-sqlite.sql} and {@code db/schema-postgres.sql},
 * each the whole schema (every statement {@code CREATE ... IF NOT EXISTS}), applied at every start. There are no migrations and no
 * {@code ALTER}: a database made from an older schema is upgraded by copying it into a fresh one ({@link DatabaseCopy},
 * {@code drishti.py db copy}). {@link #missing} tells a start-up on such a database what it lacks, so it stops with that advice.
 */
public final class DatabaseSchema {

    private static final Pattern TABLE = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\((.*?)\\);", Pattern.DOTALL);

    private DatabaseSchema() {
    }

    /** The class-path resource of the schema file. */
    public static String resource(boolean sqlite) {
        return sqlite ? "db/schema-sqlite.sql" : "db/schema-postgres.sql";
    }

    /** The schema file's text. */
    public static String text(boolean sqlite) {
        try (InputStream in = new ClassPathResource(resource(sqlite)).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource(sqlite), e);
        }
    }

    private static final Pattern VERSION = Pattern.compile("(?m)^-- schema-version: (\\d+)\\s*$");

    /** The schema version written in the file ({@code -- schema-version: N}); it moves only when an existing table changes shape. */
    public static int version(boolean sqlite) {
        return version(text(sqlite));
    }

    /** The schema version of schema text. */
    public static int version(String sql) {
        Matcher m = VERSION.matcher(sql);
        if (!m.find()) {
            throw new IllegalStateException("a schema file without its '-- schema-version: N' line");
        }
        return Integer.parseInt(m.group(1));
    }

    /** Applies the whole schema (idempotent: every table and index is created only when absent). */
    public static void apply(DataSource ds, boolean sqlite) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(resource(sqlite)));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(ds);
    }

    /** The tables of the schema in file order (parents before the tables that refer to them), each with its columns in order. */
    public static Map<String, List<String>> tables(boolean sqlite) {
        return tables(text(sqlite));
    }

    /** The tables of schema text in order, each with its column names (lower case; table constraints left out). */
    public static Map<String, List<String>> tables(String sql) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Matcher m = TABLE.matcher(sql.replaceAll("--[^\\n]*", ""));
        while (m.find()) {
            List<String> cols = new ArrayList<>();
            for (String part : splitTopLevel(m.group(2))) {
                String p = part.strip();
                if (p.isEmpty()) {
                    continue;
                }
                String upper = p.toUpperCase(Locale.ROOT);
                if (upper.startsWith("PRIMARY KEY") || upper.startsWith("UNIQUE") || upper.startsWith("FOREIGN KEY")
                        || upper.startsWith("CONSTRAINT") || upper.startsWith("CHECK")) {
                    continue;
                }
                cols.add(p.split("\\s+")[0].toLowerCase(Locale.ROOT));
            }
            out.put(m.group(1).toLowerCase(Locale.ROOT), cols);
        }
        return out;
    }

    /** Splits a column list on the commas outside parentheses. */
    static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** The columns a live database has, by table (lower case), for the tables named. */
    public static Map<String, Set<String>> columns(Connection c, Iterable<String> tables) throws SQLException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        DatabaseMetaData md = c.getMetaData();
        for (String t : tables) {
            Set<String> cols = new TreeSet<>();
            try (ResultSet r = md.getColumns(null, null, t, null)) {
                while (r.next()) {
                    cols.add(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            if (!cols.isEmpty()) {
                out.put(t, cols);
            }
        }
        return out;
    }

    /**
     * What a database lacks of the complete schema after it was applied: {@code table.column} for each column the file declares and
     * the database does not have (a table made by an older schema; {@code CREATE TABLE IF NOT EXISTS} leaves it as it is). Empty when
     * the database matches.
     */
    public static List<String> missing(DataSource ds, boolean sqlite) {
        Map<String, List<String>> want = tables(sqlite);
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            Map<String, Set<String>> have = columns(c, want.keySet());
            for (Map.Entry<String, List<String>> e : want.entrySet()) {
                Set<String> got = have.getOrDefault(e.getKey(), Set.of());
                for (String col : e.getValue()) {
                    if (!got.contains(col)) {
                        out.add(e.getKey() + "." + col);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the database's columns: " + e.getMessage(), e);
        }
        return out;
    }
}
