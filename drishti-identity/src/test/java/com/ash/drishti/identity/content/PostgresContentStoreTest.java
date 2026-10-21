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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The PostgreSQL backend: the contract, on a Testcontainers database created from the complete schema file. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresContentStoreTest extends ContentStoreContract {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Override
    protected ContentStore open() throws Exception {
        ContentStore s = reopen();                                          // creates the schema
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement st = c.createStatement()) {
            st.execute("DELETE FROM drishti_content");
        }
        return s;
    }

    @Override
    protected ContentStore reopen() {
        return new JdbcContentStore(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 8);
    }
}
