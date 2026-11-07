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
package com.ash.drishti.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.identity.database.DatabaseSettings;
import com.ash.drishti.identity.database.DrishtiDatabase;
import com.zaxxer.hikari.HikariConfig;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The Drishti database's pool: 20 by default on PostgreSQL (the site default), 8 on SQLite (embedded), with leak detection on; a key in
 * drishti.database.pool wins over the site's drishti.db.pool.
 */
class IdentityPoolTest {

    private static DatabaseSettings props(String url, Integer size) {
        Map<String, String> pool = size == null ? Map.of() : Map.of("size", size.toString());
        return url.startsWith("jdbc:sqlite:")
                ? new DatabaseSettings("sqlite", Path.of(url.substring("jdbc:sqlite:".length())), null, null, null, pool, "test")
                : new DatabaseSettings("postgres", null, url, null, null, pool, "test");
    }

    private static HikariConfig configure(DatabaseSettings p, MockEnvironment env) {
        HikariConfig c = new HikariConfig();
        DrishtiDatabase.applyPool(c, p, env::getProperty);
        return c;
    }

    @Test
    void postgresqlInheritsTheSiteDefaultOfTwenty() {
        HikariConfig c = configure(props("jdbc:postgresql://db.invalid/x", null), new MockEnvironment());
        assertThat(c.getMaximumPoolSize()).isEqualTo(20);
        assertThat(c.getMinimumIdle()).isEqualTo(2);
        assertThat(c.getConnectionTimeout()).isEqualTo(10_000);
        assertThat(c.getLeakDetectionThreshold()).isEqualTo(60_000);
        assertThat(c.getMaxLifetime()).isEqualTo(1_800_000);
        assertThat(c.getIdleTimeout()).isEqualTo(600_000);
        assertThat(c.getDataSourceProperties().getProperty("options")).isEqualTo("-c statement_timeout=300000");
    }

    @Test
    void theSiteSettingsAndTheDatabasesOwnSizeAreHonoured() {
        MockEnvironment env = new MockEnvironment().withProperty("drishti.db.pool.size", "35").withProperty("drishti.db.pool.leak-threshold", "15s")
                .withProperty("drishti.db.pool.connection-timeout", "2s").withProperty("drishti.db.pool.statement-timeout", "0");
        HikariConfig c = configure(props("jdbc:postgresql://db.invalid/x", null), env);
        assertThat(c.getMaximumPoolSize()).isEqualTo(35);
        assertThat(c.getLeakDetectionThreshold()).isEqualTo(15_000);
        assertThat(c.getConnectionTimeout()).isEqualTo(2_000);
        assertThat(c.getDataSourceProperties().getProperty("options")).isNull();
        assertThat(configure(props("jdbc:postgresql://db.invalid/x", 5), env).getMaximumPoolSize()).isEqualTo(5);
    }

    @Test
    void theDatabasesOwnPoolKeysWinOverTheSite() {
        MockEnvironment env = new MockEnvironment().withProperty("drishti.db.pool.leak-threshold", "15s");
        DatabaseSettings s = new DatabaseSettings("postgres", null, "jdbc:postgresql://db.invalid/x", null, null,
                Map.of("leak-threshold", "5s", "size", "12"), "test");
        HikariConfig c = configure(s, env);
        assertThat(c.getLeakDetectionThreshold()).isEqualTo(5_000);
        assertThat(c.getMaximumPoolSize()).isEqualTo(12);
        DatabaseSettings bad = new DatabaseSettings("postgres", null, "jdbc:postgresql://db.invalid/x", null, null, Map.of("idle-timeout", "soon"), "test");
        assertThatThrownBy(() -> configure(bad, env)).hasMessageContaining("drishti.database.pool.idle-timeout");
    }

    @Test
    void sqliteIsEmbeddedAndKeepsItsSmallPool() {
        MockEnvironment env = new MockEnvironment().withProperty("drishti.db.pool.size", "35");
        HikariConfig c = configure(props("jdbc:sqlite:./data/identity/x.db", null), env);
        assertThat(c.getMaximumPoolSize()).isEqualTo(8);
        assertThat(c.getDataSourceProperties().getProperty("options")).isNull();
    }

    @Test
    void aMalformedTimeFailsNamingTheSetting() {
        MockEnvironment env = new MockEnvironment().withProperty("drishti.db.pool.idle-timeout", "soon");
        assertThatThrownBy(() -> configure(props("jdbc:postgresql://db.invalid/x", null), env)).hasMessageContaining("drishti.db.pool.idle-timeout");
    }
}
