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

import com.ash.drishti.api.pool.PoolSettings;
import com.ash.drishti.api.pool.ResourcePool;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * The one place this plugin opens a JDBC connection: a {@link ResourcePool} of connections with the connector's pool settings.
 * Every user borrows a lease in a try-with-resources block, for one unit of work, and gives it back at once (the architecture
 * test {@code ConnectionDisciplineTest} refuses {@code DriverManager.getConnection} anywhere else). A returned connection with an
 * open transaction is rolled back and its auto-commit restored; one that cannot be reset is closed. On PostgreSQL every connection
 * gets {@code statement_timeout} from the {@code statement-timeout} setting, so no statement runs forever.
 */
final class JdbcPool {

    private JdbcPool() {}

    /** A pool of connections to {@code url}; connections are opened on first use. */
    static ResourcePool<Connection> create(String name, PoolSettings settings, String url, Properties connectionProperties) {
        return new ResourcePool<>(name, "jdbc", settings, new Opener(url, connectionProperties, settings));
    }

    private static final class Opener implements ResourcePool.Factory<Connection> {
        private final String url;
        private final Properties properties;
        private final long statementTimeoutMillis;

        Opener(String url, Properties properties, PoolSettings settings) {
            this.url = url;
            this.properties = properties;
            this.statementTimeoutMillis = settings.statementTimeout().toMillis();
        }

        @Override
        public Connection open() throws SQLException {
            Connection c = DriverManager.getConnection(url, properties);
            if (statementTimeoutMillis > 0 && url.startsWith("jdbc:postgresql:")) {
                try (Statement st = c.createStatement()) {
                    st.execute("SET statement_timeout = " + statementTimeoutMillis);
                } catch (SQLException e) {
                    close(c);
                    throw e;
                }
            }
            return c;
        }

        @Override
        public boolean valid(Connection c) {
            try {
                return c.isValid(1);
            } catch (SQLException e) {
                return false;
            }
        }

        @Override
        public boolean reset(Connection c) {
            try {
                if (c.isClosed()) {
                    return false;
                }
                if (!c.getAutoCommit()) {
                    c.rollback();
                    c.setAutoCommit(true);
                }
                return true;
            } catch (SQLException e) {
                return false;
            }
        }
    }
}
