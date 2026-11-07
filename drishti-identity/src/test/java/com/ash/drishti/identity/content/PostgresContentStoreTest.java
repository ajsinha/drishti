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
package com.ash.drishti.identity.content;

import com.ash.drishti.identity.database.DatabaseSettings;
import com.ash.drishti.identity.database.DrishtiDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The database backend on PostgreSQL: the contract, on a Testcontainers database created from the complete schema file. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresContentStoreTest extends ContentStoreContract {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static HikariDataSource ds;

    @AfterAll
    static void closePool() {
        if (ds != null) {
            ds.close();
        }
    }

    @Override
    protected ContentStore open() throws Exception {
        if (ds == null) {
            ds = DrishtiDatabase.open(new DatabaseSettings("postgres", null, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), java.util.Map.of("size", "8"), "test"),
                    k -> null);
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM drishti_content");
        }
        return reopen();
    }

    @Override
    protected ContentStore reopen() {
        return new JdbcContentStore(ds, "test");
    }
}
