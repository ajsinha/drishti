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
package com.ash.drishti.server.bi;

import com.ash.lake.tck.TestRoots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A test's BI folder with its tables and models in place: the files of {@code src/test/resources/bi/folders/<case>} (tables and models of the table layer, under
 * {@code bi/}) are copied under {@code <folder>/tables}, which is the server's {@code drishti.tables.root} in the test.
 */
public final class TablesFolder {

    private TablesFolder() {}

    /** Copies the tables and models of the case {@code name} under {@code <folder>/tables}. */
    public static void place(Path folder, String name) throws IOException {
        Path from = Path.of(TestRoots.module(), "src", "test", "resources", "bi", "folders", name);
        Path to = folder.resolve("tables");
        try (var walk = Files.walk(from)) {
            for (Path x : (Iterable<Path>) walk::iterator) {
                Path t = to.resolve(from.relativize(x).toString());
                if (Files.isDirectory(x)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(x, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
