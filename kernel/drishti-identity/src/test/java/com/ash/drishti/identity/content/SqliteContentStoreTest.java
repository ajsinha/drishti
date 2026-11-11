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

import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

/** The SQLite backend: the contract, on a database file created from the complete schema file. */
class SqliteContentStoreTest extends ContentStoreContract {

    @TempDir
    Path tmp;

    private String url;

    @Override
    protected ContentStore open() throws Exception {
        url = "jdbc:sqlite:" + tmp.resolve("drishti.db");
        return new JdbcContentStore(url, null, null, 1);
    }

    @Override
    protected ContentStore reopen() {
        return new JdbcContentStore(url, null, null, 1);
    }
}
