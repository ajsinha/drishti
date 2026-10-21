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
package com.ash.drishti.server.ui;

import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.identity.content.FileContentStore;
import com.ash.drishti.identity.content.JdbcContentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Nested;

/** The Chip Store contract on the filesystem and SQLite content store backends; the second handle plays a second node. */
class ChipStoreBackendsTest {

    @Nested
    class Filesystem extends ChipStoreContract {
        private Path root;

        @Override
        protected ContentStore open() throws Exception {
            root = Files.createDirectories(Path.of("target", "ui-store-tests", "fs-" + UUID.randomUUID()));
            return new FileContentStore(root);
        }

        @Override
        protected ContentStore second() {
            return new FileContentStore(root);
        }
    }

    @Nested
    class Sqlite extends ChipStoreContract {
        private String url;

        @Override
        protected ContentStore open() throws Exception {
            Path dir = Files.createDirectories(Path.of("target", "ui-store-tests", "sqlite-" + UUID.randomUUID()));
            url = "jdbc:sqlite:" + dir.resolve("drishti.db");
            return new JdbcContentStore(url, null, null, 1);
        }

        @Override
        protected ContentStore second() {
            return new JdbcContentStore(url, null, null, 1);
        }
    }
}
