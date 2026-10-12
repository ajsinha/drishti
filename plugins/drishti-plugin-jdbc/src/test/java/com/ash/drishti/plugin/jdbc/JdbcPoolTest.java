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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.pool.PoolExhaustedException;
import com.ash.drishti.api.pool.PoolSettings;
import com.ash.drishti.api.pool.ResourcePool;
import com.ash.drishti.api.pool.SitePool;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The connector's pool: its size (default 20, a connector's own, the site's), timeouts, and that every borrow comes back. */
class JdbcPoolTest {

    private static final String URL = "jdbc:h2:mem:drishti-pool;DB_CLOSE_DELAY=-1";

    @AfterEach
    void resetSite() {
        SitePool.reset();
    }

    private static JdbcSourcePlugin started(Map<String, String> extra) throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("create table if not exists trades(trade_id varchar primary key, notional decimal(18,2))");
            s.execute("merge into trades values ('T-1', 100)");
        }
        Map<String, String> settings = new HashMap<>(Map.of("url", URL, "query.trade", "select * from trades where trade_id = ?"));
        settings.putAll(extra);
        JsonCodec codec = new JsonCodec();
        JdbcSourcePlugin p = new JdbcSourcePlugin();
        p.start(new SourceContext() {
            public Map<String, String> settings() {
                return settings;
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return Executors.newSingleThreadScheduledExecutor();
            }
        });
        return p;
    }

    private static ResourcePool<Connection> pool(Map<String, String> settings) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("create table if not exists trades(trade_id varchar primary key, notional decimal(18,2))");
        }
        Properties props = new Properties();
        return JdbcPool.create("pool-test", PoolSettings.resolve(settings), URL, props);
    }

    @Test
    void aConnectorsPoolIsTwentyByDefaultAndItsOwnSizeOverridesIt() throws Exception {
        JdbcSourcePlugin p = started(Map.of());
        try {
            assertThat(p.poolStats().orElseThrow().max()).isEqualTo(20);
        } finally {
            p.close();
        }
        p = started(Map.of("pool-size", "6"));
        try {
            assertThat(p.poolStats().orElseThrow().max()).isEqualTo(6);
        } finally {
            p.close();
        }
    }

    @Test
    void theSiteDefaultIsInheritedUnlessTheConnectorSetsItsOwn() throws Exception {
        SitePool.configure(Map.of("pool-size", "11"));
        JdbcSourcePlugin p = started(Map.of());
        try {
            assertThat(p.poolStats().orElseThrow().max()).isEqualTo(11);
        } finally {
            p.close();
        }
    }

    @Test
    void aReadReturnsItsConnectionAtOnce() throws Exception {
        JdbcSourcePlugin p = started(Map.of("pool-size", "3"));
        try {
            for (int i = 0; i < 50; i++) {
                assertThat(p.fetch(EntityRef.of("trade", "T-1"))).isPresent();
                assertThat(p.fetch(EntityRef.of("trade", "NOPE"))).isEmpty();
            }
            var s = p.poolStats().orElseThrow();
            assertThat(s.inUse()).isZero();
            assertThat(s.open()).isLessThanOrEqualTo(3);
        } finally {
            p.close();
        }
    }

    @Test
    void aFailingStatementStillReturnsItsConnection() throws Exception {
        JdbcSourcePlugin p = started(Map.of("pool-size", "2", "query.broken", "select * from no_such_table where id = ?"));
        try {
            for (int i = 0; i < 20; i++) {
                assertThatThrownBy(() -> p.fetch(EntityRef.of("broken", "x"))).hasMessageContaining("broken");
            }
            assertThat(p.poolStats().orElseThrow().inUse()).isZero();
            assertThat(p.fetch(EntityRef.of("trade", "T-1"))).isPresent();     // and the pool still serves
        } finally {
            p.close();
        }
    }

    @Test
    void aCallerWaitsForTheConnectionTimeoutThenFailsNamingTheLoad() throws Exception {
        try (ResourcePool<Connection> pool = pool(Map.of("pool-size", "1", "pool-connection-timeout", "250ms"))) {
            try (var held = pool.borrow()) {
                long t0 = System.nanoTime();
                assertThatThrownBy(pool::borrow).isInstanceOf(PoolExhaustedException.class).hasMessageContaining("pool-test")
                        .hasMessageContaining("1 of 1 in use");
                assertThat((System.nanoTime() - t0) / 1_000_000).isBetween(200L, 3_000L);
                assertThat(held.get().isValid(1)).isTrue();
            }
            try (var again = pool.borrow()) {                                   // free again
                assertThat(again.get().isValid(1)).isTrue();
            }
            assertThat(pool.stats().timeouts()).isEqualTo(1);
        }
    }

    @Test
    void stressWithInjectedErrorsEndsWithNothingInUse() throws Exception {
        try (ResourcePool<Connection> pool = pool(Map.of("pool-size", "4", "pool-connection-timeout", "30s"));
                ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicInteger bad = new AtomicInteger();
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                int n = i;
                all.add(threads.submit(() -> {
                    try (var lease = pool.borrow(); Statement st = lease.get().createStatement()) {
                        if (n % 5 == 0) {
                            st.execute("select * from no_such_table");               // a SQL error mid-work
                        } else if (n % 11 == 0) {
                            lease.get().setAutoCommit(false);                        // an open transaction left behind
                            st.execute("merge into trades values ('T-" + n + "', 1)");
                        } else {
                            st.execute("select 1");
                        }
                    } catch (SQLException e) {
                        bad.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : all) {
                f.get();
            }
            assertThat(bad.get()).isEqualTo(60);
            assertThat(pool.stats().inUse()).isZero();
            assertThat(pool.stats().waiting()).isZero();
            try (var lease = pool.borrow()) {                                       // a returned connection is back in auto-commit
                assertThat(lease.get().getAutoCommit()).isTrue();
            }
        }
    }

    @Test
    void aConnectionHeldPastTheLeakThresholdIsCountedAndReleasedWhenClosed() throws Exception {
        try (ResourcePool<Connection> pool = pool(Map.of("pool-size", "2", "pool-leak-threshold", "150ms"))) {
            var leaked = pool.borrow();                                             // deliberately held
            long until = System.currentTimeMillis() + 5_000;
            while (pool.stats().leaksDetected() == 0 && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertThat(pool.stats().leaksDetected()).isEqualTo(1);
            assertThat(pool.stats().inUse()).isEqualTo(1);
            assertThat(pool.stats().longestBorrowMillis()).isGreaterThanOrEqualTo(150);
            leaked.close();
            assertThat(pool.stats().inUse()).isZero();
        }
    }
}
