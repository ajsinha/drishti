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
package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The two identity schema files are one schema (docs/architecture/DATA_ARCHITECTURE.md: no migrations, two complete schema files): the same tables,
 * each with the same columns and the same nullability (their order may differ), and the same indexes. Only the column types differ (SQLite types by
 * value, PostgreSQL by declaration). A table added to one file and not the other fails here before it fails on someone's database.
 */
class SchemaParityTest {

    private static final Path DB = Path.of("drishti-identity", "src", "main", "resources", "db");
    private static final Pattern TABLE = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\((.*?)\\);", Pattern.DOTALL);
    /** Columns the SQLite file leaves to IdentityDatabase, which adds them in code (SQLite has no ADD COLUMN IF NOT EXISTS), and why. */
    private static final Map<String, String> SQLITE_ADDS_IN_CODE = Map.of(
            "drishti_share.view_state", "IdentityDatabase.ensureShareViewState adds it to a share table made before view state");

    private static final Pattern ALTER = Pattern.compile("ALTER TABLE (\\w+) ADD COLUMN IF NOT EXISTS (\\w+)([^;]*);");
    private static final Pattern INDEX = Pattern.compile("CREATE (?:UNIQUE )?INDEX IF NOT EXISTS (\\w+) ON (\\w+) \\(([^)]*)\\)");

    @Test
    void theSqliteAndPostgresSchemasHoldTheSameTablesColumnsAndIndexes() throws IOException {
        Path root = RepositoryFiles.root();
        String sqlite = strip(Files.readString(root.resolve(DB).resolve("schema-sqlite.sql")));
        String postgres = strip(Files.readString(root.resolve(DB).resolve("schema-postgres.sql")));
        Map<String, List<String>> a = tables(sqlite);
        SQLITE_ADDS_IN_CODE.keySet().forEach(k -> a.get(k.substring(0, k.indexOf('.'))).add(k.substring(k.indexOf('.') + 1)));
        Map<String, List<String>> b = tables(postgres);
        assertThat(b.keySet()).as("tables").containsExactlyInAnyOrderElementsOf(a.keySet());
        for (String t : a.keySet()) {
            assertThat(b.get(t)).as("columns of " + t + " (name and NOT NULL; the order may differ)").containsExactlyInAnyOrderElementsOf(a.get(t));
        }
        assertThat(indexes(postgres)).as("indexes").isEqualTo(indexes(sqlite));
    }

    @Test
    void theBiRunTablesAreInBothFiles() throws IOException {
        Path root = RepositoryFiles.root();
        for (String f : List.of("schema-sqlite.sql", "schema-postgres.sql")) {
            Map<String, List<String>> t = tables(strip(Files.readString(root.resolve(DB).resolve(f))));
            assertThat(t).as(f).containsKeys("drishti_bi_run", "drishti_bi_run_share", "drishti_bi_run_schedule");
            assertThat(t.get("drishti_bi_run")).as(f).contains("id", "runner!", "owner!", "question!", "fingerprint!", "signature!", "tier!",
                    "engine!", "state!", "estimate!", "node!", "attempt!", "submitted_at!", "result_key", "lease_until");
        }
    }

    private static String strip(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }

    /** Table to its columns, each {@code name} or {@code name!} when NOT NULL (constraints such as PRIMARY KEY (a, b) are kept as written). */
    private static Map<String, List<String>> tables(String sql) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Matcher m = TABLE.matcher(sql);
        while (m.find()) {
            List<String> cols = new ArrayList<>();
            for (String part : split(m.group(2))) {
                String p = part.strip().replaceAll("\\s+", " ");
                if (p.isEmpty()) {
                    continue;
                }
                String upper = p.toUpperCase(Locale.ROOT);
                if (upper.startsWith("PRIMARY KEY") || upper.startsWith("UNIQUE") || upper.startsWith("FOREIGN KEY") || upper.startsWith("CONSTRAINT")) {
                    cols.add(upper.replaceAll("\\s", ""));
                    continue;
                }
                String name = p.split(" ")[0].toLowerCase(Locale.ROOT);
                cols.add(upper.contains("NOT NULL") ? name + "!" : name);
            }
            out.put(m.group(1).toLowerCase(Locale.ROOT), cols);
        }
        Matcher a = ALTER.matcher(sql);                      // a column added later to an existing table (PostgreSQL: ADD COLUMN IF NOT EXISTS)
        while (a.find()) {
            List<String> cols = out.get(a.group(1).toLowerCase(Locale.ROOT));
            String name = a.group(2).toLowerCase(Locale.ROOT) + (a.group(3).toUpperCase(Locale.ROOT).contains("NOT NULL") ? "!" : "");
            if (cols != null && !cols.contains(name)) {
                cols.add(name);
            }
        }
        return out;
    }

    /** Splits a column list on the commas outside parentheses. */
    private static List<String> split(String body) {
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

    private static TreeSet<String> indexes(String sql) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = INDEX.matcher(sql);
        while (m.find()) {
            out.add((m.group(1) + " " + m.group(2) + " " + m.group(3).replaceAll("\\s", "")).toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
