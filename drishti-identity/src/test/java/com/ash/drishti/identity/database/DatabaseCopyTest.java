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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The schema-change path on SQLite (docs/admin/OPERATIONS.md, "Upgrading the Drishti database"): a database an older schema made is
 * refused at start with the copy command named; {@code db copy} builds a fresh database from the complete schema, copies every row
 * (counts verified), gives new columns their default, drops an old column only with {@code --drop-columns}, stamps the target, and
 * {@code --check} writes nothing.
 */
class DatabaseCopyTest {

    @TempDir
    Path tmp;

    /** The current SQLite schema as an older release had it: no stamp, two columns missing, one table missing, one column since dropped. */
    static String olderSchema() {
        String s = DatabaseSchema.text(true);
        s = s.replaceFirst("(?s)CREATE TABLE IF NOT EXISTS drishti_schema \\(.*?\\);", "");
        s = s.replace("    hash VARCHAR(64) NOT NULL,\n    view_state VARCHAR(4100)\n", "    hash VARCHAR(64) NOT NULL\n");
        s = s.replaceFirst("(?s)(CREATE TABLE IF NOT EXISTS drishti_api_token \\(.*?),\\s*scopes\\s+TEXT\\s*\\n\\);", "$1\n);");
        s = s.replaceFirst("(?s)CREATE TABLE IF NOT EXISTS drishti_edit_cursor \\(.*?\\);", "");
        s = s.replace("    body       TEXT NOT NULL,\n    created_at TIMESTAMP NOT NULL,", "    body       TEXT NOT NULL,\n    legacy_flag INTEGER,\n    created_at TIMESTAMP NOT NULL,");
        return s;
    }

    /** An older SQLite database with rows in tables of every kind of column (booleans, times, dates, blobs, auto-numbered keys). */
    static Path olderDatabase(Path file) throws Exception {
        String schema = olderSchema();
        assertThat(schema).doesNotContain("drishti_schema").doesNotContain("view_state").contains("legacy_flag");
        Files.createDirectories(file.getParent());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            for (String stmt : schema.replaceAll("--[^\\n]*", "").split(";")) {
                if (!stmt.isBlank()) {
                    st.execute(stmt);
                }
            }
            fill(c, true);
        }
        return file;
    }

    /** Rows in a database built from a schema (old or current): the same on both kinds. */
    static void fill(Connection c, boolean legacy) throws Exception {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);                                             // one transaction: SQLite syncs once
        fillRows(c, legacy);
        c.commit();
        c.setAutoCommit(auto);
    }

    private static void fillRows(Connection c, boolean legacy) throws Exception {
        Timestamp t = Timestamp.valueOf("2026-11-06 09:30:15.250");
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO drishti_user (username, display_name, enabled, must_change_password, "
                + "password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < 3; i++) {
                ps.setString(1, "user" + i);
                ps.setString(2, "User " + i);
                ps.setBoolean(3, i != 1);
                ps.setBoolean(4, false);
                ps.setString(5, "hash" + i);
                ps.setTimestamp(6, t);
                ps.setTimestamp(7, t);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO drishti_audit (at, actor, action, subject, detail) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < 2_500; i++) {                              // more than a batch
                ps.setTimestamp(1, new Timestamp(t.getTime() + i));
                ps.setString(2, "user" + (i % 3));
                ps.setString(3, "sign-in");
                ps.setString(4, "s" + i);
                ps.setString(5, "");
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO drishti_content (k, v, version, updated_ms, deleted) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, "designs/a.json");
            ps.setBytes(2, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            ps.setLong(3, 3);
            ps.setLong(4, 1_700_000_000_000L);
            ps.setInt(5, 0);
            ps.executeUpdate();
        }
        String note = legacy ? "INSERT INTO drishti_note (kind, entity_id, username, body, legacy_flag, created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?)"
                : "INSERT INTO drishti_note (kind, entity_id, username, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(note)) {
            ps.setString(1, "trade");
            ps.setString(2, "MX-1");
            ps.setString(3, "user0");
            ps.setString(4, "a note");
            ps.setTimestamp(5, t);
            ps.setTimestamp(6, t);
            ps.executeUpdate();
        }
    }

    private static Path settings(Path file, String body) throws Exception {
        Files.writeString(file, "drishti:\n  database:\n" + body, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aDatabaseAnOlderSchemaMadeIsRefusedAtStartWithTheCopyCommand() throws Exception {
        Path old = olderDatabase(tmp.resolve("old/drishti.db"));
        assertThatThrownBy(() -> DrishtiDatabase.open(DatabaseSettings.sqlite(old), k -> null).close())
                .hasMessageContaining("lacks").hasMessageContaining("drishti_share.view_state").hasMessageContaining("drishti.py db copy");
    }

    @Test
    void aStampFromAnotherVersionIsRefused() throws Exception {
        DatabaseSettings s = DatabaseSettings.sqlite(tmp.resolve("v/drishti.db"));
        try (HikariDataSource ds = DrishtiDatabase.open(s, k -> null); Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(DrishtiDatabase.stamp(ds)).hasValue(DatabaseSchema.version(true));
            st.executeUpdate("UPDATE drishti_schema SET version = version + 1");
        }
        assertThatThrownBy(() -> DrishtiDatabase.open(s, k -> null).close()).hasMessageContaining("was made by schema " + (DatabaseSchema.version(true) + 1))
                .hasMessageContaining("drishti.py db copy");
    }

    @Test
    void theCopyUpgradesAnOlderDatabaseIntoAFreshOneWithEveryRow() throws Exception {
        Path old = olderDatabase(tmp.resolve("old/drishti.db"));
        DatabaseSettings from = DatabaseSettings.sqlite(old);
        DatabaseSettings to = DatabaseSettings.sqlite(tmp.resolve("new/drishti.db"));
        DatabaseCopy copy = new DatabaseCopy(k -> null);

        assertThatThrownBy(() -> copy.copy(from, to, false, false)).hasMessageContaining("drishti_note.legacy_flag").hasMessageContaining("--drop-columns");
        assertThat(to.sqliteFile()).doesNotExist();

        DatabaseCopy.Report check = copy.copy(from, to, true, false);
        assertThat(check.checked()).isTrue();
        assertThat(to.sqliteFile()).as("--check writes nothing").doesNotExist();
        assertThat(table(check, "drishti_audit").sourceRows()).isEqualTo(2_500);
        assertThat(table(check, "drishti_note").dropped()).containsExactly("legacy_flag");

        DatabaseCopy.Report r = copy.copy(from, to, false, true);
        assertThat(r.verified()).isTrue();
        assertThat(table(r, "drishti_audit").targetRows()).isEqualTo(2_500);
        assertThat(table(r, "drishti_user").targetRows()).isEqualTo(3);
        assertThat(table(r, "drishti_share").added()).containsExactly("view_state");
        assertThat(table(r, "drishti_edit_cursor").inSource()).isFalse();
        assertThat(DatabaseCopy.lines(r).get(DatabaseCopy.lines(r).size() - 1)).contains("every count verified");

        try (HikariDataSource ds = DrishtiDatabase.open(to, k -> null); Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(DrishtiDatabase.stamp(ds)).hasValue(DatabaseSchema.version(true));
            try (ResultSet rs = st.executeQuery("SELECT enabled, created_at FROM drishti_user WHERE username = 'user1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isFalse();
                assertThat(rs.getTimestamp(2)).isEqualTo(Timestamp.valueOf("2026-11-06 09:30:15.250"));
            }
            try (ResultSet rs = st.executeQuery("SELECT v FROM drishti_content WHERE k = 'designs/a.json'")) {
                assertThat(rs.next()).isTrue();
                assertThat(new String(rs.getBytes(1), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
            }
            st.executeUpdate("INSERT INTO drishti_audit (at, actor, action) VALUES (0, 'x', 'y')");   // the auto-numbered key continues
            try (ResultSet rs = st.executeQuery("SELECT MAX(id) FROM drishti_audit")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2_501);
            }
        }
        assertThat(old).as("the source is never changed").exists();
        assertThatThrownBy(() -> copy.copy(from, to, false, true)).as("the target must be fresh").hasMessageContaining("exists");
    }

    @Test
    void theCommandReadsTwoSettingsFilesAndReportsAsJson() throws Exception {
        olderDatabase(tmp.resolve("data/identity/drishti.db"));
        Path a = settings(tmp.resolve("a.yaml"), "    kind: sqlite\n    sqlite:\n      file: ${NO_SUCH_DRISHTI_VAR:${drishti.data.dir}/identity/drishti.db}\n");
        Path b = settings(tmp.resolve("b.yaml"), "    kind: ${NO_SUCH_DRISHTI_KIND:sqlite}\n    sqlite:\n      file: " + tmp.resolve("copy/drishti.db") + "\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        DatabaseCli cli = new DatabaseCli(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        String data = tmp.resolve("data").toString();
        assertThat(cli.run(List.of("copy", "--from", a.toString(), "--to", b.toString(), "--data-dir", data))).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--drop-columns");
        assertThat(cli.run(List.of("copy", "--from", a.toString(), "--to", b.toString(), "--data-dir", data, "--drop-columns", "--json"))).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(out.toString(StandardCharsets.UTF_8), Map.class);
        assertThat(json).containsEntry("verified", true).containsEntry("rows", 2_505);
        assertThat(tmp.resolve("copy/drishti.db")).exists();
        assertThat(cli.run(List.of("move"))).isEqualTo(2);
    }

    static DatabaseCopy.Table table(DatabaseCopy.Report r, String name) {
        return r.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }
}
