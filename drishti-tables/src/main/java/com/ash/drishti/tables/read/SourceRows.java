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
package com.ash.drishti.tables.read;

import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.preview.Preview;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where a virtual table's documents come from, for one reader (VIRTUAL_TABLES.md section 10, V9 and V11): the server reads a kind through
 * its connector as the Data view does, the reader's row rules and masks on the source applied to every document; a test reads JSONL. The
 * layer never reads a connector itself, so it calls no SQL engine of its own (V14).
 */
public interface SourceRows {

    /** The business dates the source holds, oldest first; empty for an undated source (it gives its current rows). */
    List<LocalDate> dates(TableDef.Source source);

    /** True when the source keeps what was known when ({@code knownAt} time travel). */
    default boolean travels(TableDef.Source source) {
        return false;
    }

    /**
     * Hands the source's documents on {@code date} (null: an undated source's current rows) as known at {@code knownAt} (null: now) to
     * {@code sink}, which answers false to stop. Every document as the reader may see it.
     */
    void read(TableDef.Source source, LocalDate date, Instant knownAt, Predicate<Preview.Doc> sink);

    /** The rows the source holds on {@code date}, or -1 when it cannot say (the scan estimate's input). */
    default long estimate(TableDef.Source source, LocalDate date) {
        return -1;
    }

    /** The connector that serves the source (its {@code connector:}, else the kind's own), or null when not known. */
    default String connector(TableDef.Source source) {
        return source.connector();
    }
}
