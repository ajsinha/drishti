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
package com.ash.drishti.engine.wire;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.engine.ViewerContext;
import java.time.LocalDate;
import java.util.List;

/**
 * A virtual table's rows for a panel's {@code source: { table }} (VIRTUAL_TABLES.md V13): read for one viewer on a business date, the source as
 * they may see it, then the table's rules, masks and hidden columns. The server provides it (the table layer); the engine knows nothing of
 * tables beyond this port.
 */
@FunctionalInterface
public interface TableRows {

    /**
     * The rows, each an object of the table's columns by name (masked values as the mask).
     *
     * @param date the business date, or null for the table's latest
     * @throws Refused when the viewer may not read the table, or it cannot be read; its message is what the panel says
     */
    List<DataNode> rows(ViewerContext who, String table, LocalDate date);

    /** A read refused, in words the panel shows. */
    final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    /** No table layer on this node: every table source says so. */
    TableRows NONE = (who, table, date) -> {
        throw new Refused("virtual tables are not served here");
    };
}
