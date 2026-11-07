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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Switching SQLite to PostgreSQL and back with {@code db copy} (Docker; skipped where Docker is not reachable): every row arrives,
 * booleans, times, dates and bytes keep their values, PostgreSQL's auto-numbered keys continue after the copied ones, the target is
 * stamped, and a target that is not fresh is refused.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresDatabaseCopyTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @TempDir
    Path tmp;

    @Test
    void sqliteToPostgresAndBackKeepsEveryRow() throws Exception {
        DatabaseSettings sqlite = DatabaseSettings.sqlite(tmp.resolve("a/drishti.db"));
        try (HikariDataSource ds = DrishtiDatabase.open(sqlite, k -> null); Connection c = ds.getConnection()) {
            DatabaseCopyTest.fill(c, false);
        }
        DatabaseSettings pg = DatabaseSettings.postgres(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        DatabaseCopy copy = new DatabaseCopy(k -> null);

        DatabaseCopy.Report there = copy.copy(sqlite, pg, false, false);
        assertThat(there.verified()).isTrue();
        assertThat(DatabaseCopyTest.table(there, "drishti_audit").targetRows()).isEqualTo(2_500);

        try (HikariDataSource ds = DrishtiDatabase.open(pg, k -> null); Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(DrishtiDatabase.stamp(ds)).hasValue(DatabaseSchema.version(false));
            try (ResultSet rs = st.executeQuery("SELECT enabled, created_at FROM drishti_user WHERE username = 'user1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isFalse();
                assertThat(rs.getTimestamp(2)).isEqualTo(Timestamp.valueOf("2026-11-06 09:30:15.250"));
            }
            try (ResultSet rs = st.executeQuery("SELECT v FROM drishti_content WHERE k = 'designs/a.json'")) {
                assertThat(rs.next()).isTrue();
                assertThat(new String(rs.getBytes(1), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
            }
            st.executeUpdate("INSERT INTO drishti_audit (at, actor, action) VALUES (now(), 'x', 'y')");
            try (ResultSet rs = st.executeQuery("SELECT MAX(id) FROM drishti_audit")) {
                rs.next();
                assertThat(rs.getLong(1)).as("the identity continues after the copied keys").isEqualTo(2_501);
            }
        }
        assertThatThrownBy(() -> copy.copy(sqlite, pg, false, false)).as("the target must be fresh").hasMessageContaining("not fresh");

        DatabaseSettings back = DatabaseSettings.sqlite(tmp.resolve("b/drishti.db"));
        DatabaseCopy.Report home = copy.copy(pg, back, false, false);
        assertThat(home.verified()).isTrue();
        assertThat(DatabaseCopyTest.table(home, "drishti_audit").targetRows()).isEqualTo(2_501);
        try (HikariDataSource ds = DrishtiDatabase.open(back, k -> null); Connection c = ds.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT enabled, created_at FROM drishti_user WHERE username = 'user0'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).isTrue();
            assertThat(rs.getTimestamp(2)).isEqualTo(Timestamp.valueOf("2026-11-06 09:30:15.250"));
        }
    }
}
