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

import com.ash.drishti.server.bi.expr.DelTablesTranslator;
import com.ash.drishti.tables.migrate.MigrationAudit;
import com.ash.drishti.tables.migrate.TablesMigrator;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A test's BI folder moved onto the table layer the way an installation is (docs/architecture/ARCHITECTURE.md §28 section 4.3): {@code tables migrate} over
 * the folder's {@code datasets/} and {@code models/}, the tables and models written under {@code <folder>/tables} (the server's
 * {@code drishti.tables.root} in the test), the reports that named a dataset rebound in place, the old files moved aside.
 */
public final class TablesFolder {

    private TablesFolder() {}

    /** Migrates the BI folder's datasets and models into {@code <folder>/tables}; refuses (an exception) when an item is refused. */
    public static TablesMigrator.Plan migrate(Path folder) throws IOException {
        return migrate(folder, true);
    }

    /**
     * As {@link #migrate(Path)}; {@code moveOriginals} false leaves the old files where they are (a test of BI's own dataset endpoints, which
     * go with the old dataset code).
     */
    public static TablesMigrator.Plan migrate(Path folder, boolean moveOriginals) throws IOException {
        Path tables = folder.resolve("tables");
        TablesMigrator m = new TablesMigrator(new DelTablesTranslator());
        TablesMigrator.Plan p = m.plan(folder, tables);
        p.items().stream().filter(i -> i.refusal() != null).findFirst().ifPresent(i -> {
            throw new IllegalStateException(i.source() + ": " + i.refusal());
        });
        TablesMigrator.write(p, tables, folder, moveOriginals ? folder.resolveSibling(folder.getFileName() + "-migrated") : null, MigrationAudit.NONE, null);
        return p;
    }
}
