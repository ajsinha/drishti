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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The connector starts while its database is down, serves as soon as the database comes up, and recovers by itself
 * after the database is killed and started again: no restart of Drishti. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresReconnectTest {

    private static PostgreSQLContainer database(int port) {
        PostgreSQLContainer c = new PostgreSQLContainer("postgres:18-alpine");
        c.withCreateContainerCmdModifier(cmd -> ((com.github.dockerjava.api.command.CreateContainerCmd) cmd).getHostConfig()
                .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(5432))));   // the same host port each time
        c.start();
        try (Connection conn = DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword()); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE entities (kind text, id text, business_date date, doc jsonb, PRIMARY KEY (kind, id, business_date))");
            st.execute("INSERT INTO entities VALUES ('trade', 'T-1', '2026-09-29', '{\"tradeId\":\"T-1\",\"mtm\":110}')");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    private static boolean eventually(BooleanSupplier ok, Duration within) throws InterruptedException {
        long until = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < until) {
            if (ok.getAsBoolean()) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private static boolean reads(JdbcSourcePlugin p) {
        try {
            return p.fetch(EntityRef.of("trade", "T-1"), new AsOf(java.time.LocalDate.of(2026, 9, 29), null)).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void startsWithoutItsDatabaseAndRecoversAfterEveryOutage() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        JdbcSourcePlugin p = new JdbcSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("url", "jdbc:postgresql://localhost:" + port + "/test?connectTimeout=2&loginTimeout=3", "user", "test", "password", "test",
                "table", "entities", "kinds", "trade", "source-name", "flaky-db", "pool-size", "2")));
        assertThat(reads(p)).isFalse();                                         // nothing to read yet, but the connector is up
        assertThat(p.health()).startsWith("DOWN");

        PostgreSQLContainer db = database(port);
        try {
            assertThat(eventually(() -> reads(p), Duration.ofSeconds(30))).as("serves once the database is up").isTrue();
            // the catalogue (dates, type-ahead ids) is read again within 10 s of the database coming back
            assertThat(eventually(() -> p.health().equals("UP"), Duration.ofSeconds(30))).as(p.health()).isTrue();
        } finally {
            db.stop();                                                          // the database dies under the pooled connections
        }
        assertThat(reads(p)).isFalse();
        PostgreSQLContainer again = database(port);
        try {
            assertThat(eventually(() -> reads(p), Duration.ofSeconds(30))).as("recovers after the outage").isTrue();
        } finally {
            again.stop();
            p.close();
        }
    }
}
