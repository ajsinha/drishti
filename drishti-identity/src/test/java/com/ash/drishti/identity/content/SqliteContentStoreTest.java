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
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The database backend on SQLite: the contract, on a Drishti database file created from the complete schema file. */
class SqliteContentStoreTest extends ContentStoreContract {

    @TempDir
    Path tmp;

    private HikariDataSource ds;

    @AfterEach
    void closePool() {
        if (ds != null) {
            ds.close();
        }
    }

    @Override
    protected ContentStore open() throws Exception {
        ds = DrishtiDatabase.open(new DatabaseSettings("sqlite", tmp.resolve("drishti.db"), null, null, null, null, "test"), k -> null);
        return reopen();
    }

    @Override
    protected ContentStore reopen() {
        return new JdbcContentStore(ds, "test");
    }
}
