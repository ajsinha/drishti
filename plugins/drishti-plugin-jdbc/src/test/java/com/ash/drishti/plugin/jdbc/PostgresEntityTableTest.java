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

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The JDBC connector's table mode against a real PostgreSQL 18 in Docker, loaded with the contract's rows the way
 * {@code tools/samplegen/pgload.py} loads a domain: the same tests as the Delta Lake connector. Skipped where
 * Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresEntityTableTest extends DatedSourceContract {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    private static JdbcSourcePlugin plugin;

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA desk");
            st.execute("CREATE TABLE desk.entities (kind text NOT NULL, id text NOT NULL, business_date date NOT NULL, doc jsonb NOT NULL, "
                    + "PRIMARY KEY (kind, id, business_date))");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO desk.entities VALUES (?, ?, ?, ?::jsonb)")) {
                for (Row r : ROWS) {
                    ps.setString(1, r.kind());
                    ps.setString(2, r.id());
                    ps.setDate(3, Date.valueOf(r.date()));
                    ps.setString(4, r.json());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        JdbcSourcePlugin p = new JdbcSourcePlugin();
        p.start(context(Map.of("url", POSTGRES.getJdbcUrl(), "user", POSTGRES.getUsername(), "password", POSTGRES.getPassword(),
                "table", "desk.entities", "mode.counterparty", "effective", "source-name", "desk-db", "pool-size", "2")));
        plugin = p;
        return p;
    }
}
