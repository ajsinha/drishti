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
package com.ash.drishti.server.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.live.LiveCheckpoints;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The live intake's checkpoints in the Drishti database (LIVE_CONNECTORS_DESIGN.md section 6) on SQLite: written whole per kind, read back by
 * the node that takes a feed over, moving only forward, one connector's rows apart from another's, and working through
 * {@link LiveCheckpoints} as the feed uses them.
 */
class JdbcLiveCheckpointStoreTest {

    @TempDir
    Path dir;

    private HikariDataSource ds;
    private JdbcLiveCheckpointStore store;

    @BeforeEach
    void open() {
        HikariConfig c = new HikariConfig();
        c.setPoolName("live-checkpoints-test");
        c.setJdbcUrl("jdbc:sqlite:" + dir.resolve("drishti.db"));
        c.addDataSourceProperty("journal_mode", "WAL");
        c.addDataSourceProperty("busy_timeout", "10000");
        c.setMaximumPoolSize(4);
        ds = new HikariDataSource(c);
        ResourceDatabasePopulator p = new ResourceDatabasePopulator(new ClassPathResource("db/schema-sqlite.sql"));
        p.setSqlScriptEncoding("UTF-8");
        p.execute(ds);
        store = new JdbcLiveCheckpointStore(ds);
    }

    @AfterEach
    void close() {
        ds.close();
    }

    @Test
    void aCheckpointIsReadBackWholeByTheNodeThatTakesTheFeedOver() {
        Instant cutoff = Instant.parse("2026-11-13T18:00:00Z");
        store.save("trade-live", "trade", new LiveCheckpoints.Kind(cutoff, Map.of("trades/0", 41L, "trades/1", 7L)));
        Map<String, LiveCheckpoints.Kind> got = store.load("trade-live");
        assertThat(got).containsOnlyKeys("trade");
        assertThat(got.get("trade").cutoff()).isEqualTo(cutoff);
        assertThat(got.get("trade").positions()).containsOnly(Map.entry("trades/0", 41L), Map.entry("trades/1", 7L));
    }

    @Test
    void aStreamNameWithASlashKeepsItsPartition() {
        store.save("c", "k", new LiveCheckpoints.Kind(null, Map.of("a/b/c/3", 9L)));
        assertThat(store.load("c").get("k").positions()).containsOnly(Map.entry("a/b/c/3", 9L));
        assertThat(store.load("c").get("k").cutoff()).isNull();
    }

    @Test
    void aCheckpointOnlyMovesForward() {
        store.save("c", "k", new LiveCheckpoints.Kind(Instant.parse("2026-11-13T18:00:00Z"), Map.of("t/0", 10L)));
        store.save("c", "k", new LiveCheckpoints.Kind(Instant.parse("2026-11-13T12:00:00Z"), Map.of("t/0", 4L)));
        LiveCheckpoints.Kind k = store.load("c").get("k");
        assertThat(k.cutoff()).isEqualTo(Instant.parse("2026-11-13T18:00:00Z"));
        assertThat(k.positions()).containsEntry("t/0", 10L);
        store.save("c", "k", new LiveCheckpoints.Kind(Instant.parse("2026-11-16T09:00:00Z"), Map.of("t/0", 12L)));
        k = store.load("c").get("k");
        assertThat(k.cutoff()).isEqualTo(Instant.parse("2026-11-16T09:00:00Z"));
        assertThat(k.positions()).containsEntry("t/0", 12L);
    }

    @Test
    void connectorsAndKindsAreKeptApart() {
        store.save("a", "trade", new LiveCheckpoints.Kind(Instant.parse("2026-11-13T18:00:00Z"), Map.of()));
        store.save("b", "trade", new LiveCheckpoints.Kind(Instant.parse("2026-11-14T18:00:00Z"), Map.of()));
        store.save("a", "position", new LiveCheckpoints.Kind(null, Map.of("p/0", 3L)));
        assertThat(store.load("a")).containsOnlyKeys("trade", "position");
        assertThat(store.load("b")).containsOnlyKeys("trade");
        assertThat(store.load("b").get("trade").cutoff()).isEqualTo(Instant.parse("2026-11-14T18:00:00Z"));
        assertThat(store.load("nobody")).isEmpty();
    }

    @Test
    void theFeedsCheckpointsSurviveARestartThroughTheDatabase() {
        LiveCheckpoints first = new LiveCheckpoints("trade-live", store);
        first.cutoff("trade", Instant.parse("2026-11-13T18:00:00Z"));
        first.reset("position", Map.of("positions/0", 100L));
        LiveCheckpoints restarted = new LiveCheckpoints("trade-live", store);
        assertThat(restarted.of("trade").cutoff()).isEqualTo(Instant.parse("2026-11-13T18:00:00Z"));
        assertThat(restarted.of("position").positions()).containsEntry("positions/0", 100L);
    }

    @Test
    void aDatabaseThatFailsLeavesTheCheckpointsInMemory() {
        LiveCheckpoints cp = new LiveCheckpoints("c", store);
        ds.close();                                                  // the database goes away: a write is logged, never thrown
        cp.cutoff("trade", Instant.parse("2026-11-13T18:00:00Z"));
        assertThat(cp.of("trade").cutoff()).isEqualTo(Instant.parse("2026-11-13T18:00:00Z"));
    }
}
