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
package com.ash.drishti.server.edit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** A Drishti database for the edit tests: a SQLite file in a test's folder, the complete schema applied, the same settings as the server's. */
final class EditTestDb {

    private EditTestDb() {}

    static HikariDataSource sqlite(Path dir) {
        HikariConfig c = new HikariConfig();
        c.setPoolName("edit-test");
        c.setJdbcUrl("jdbc:sqlite:" + dir.resolve("drishti.db"));
        c.addDataSourceProperty("journal_mode", "WAL");
        c.addDataSourceProperty("busy_timeout", "10000");
        c.addDataSourceProperty("foreign_keys", "true");
        c.addDataSourceProperty("transaction_mode", "IMMEDIATE");
        c.setMaximumPoolSize(8);
        HikariDataSource ds = new HikariDataSource(c);
        ResourceDatabasePopulator p = new ResourceDatabasePopulator(new ClassPathResource("db/schema-sqlite.sql"));
        p.setSqlScriptEncoding("UTF-8");
        p.execute(ds);
        return ds;
    }
}
