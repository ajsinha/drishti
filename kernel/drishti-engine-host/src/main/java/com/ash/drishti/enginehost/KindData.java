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
package com.ash.drishti.enginehost;

import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.VectorSink;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The read API an engine's inputs come from (EN-40): kinds and lake tables as the router serves them, which for a lake connector is Drishti
 * Lake's read API ({@code TableSnapshot.columns}, {@code vectors}). The engine never sees a connector, a credential or a path. The server
 * implements it over its {@code SourceRouter}; a test over arrays.
 */
public interface KindData {

    /** True when a connector serves the kind (or the lake table of that name) on this node. */
    boolean serves(String kind);

    /** The kind's newest business date; empty for undated data or nothing held. */
    Optional<LocalDate> latest(String kind);

    /** Changes when, and only when, the kind's rows for the date change (the table's version); empty when the source cannot say. */
    Optional<String> stamp(String kind, LocalDate date);

    /**
     * Some columns of every row of the kind on the date ({@code null}: the latest), the arrays handed over by reference, not copied (the
     * read API's {@code ColumnBlock}). Empty when the kind has no rows then or its source keeps no columns.
     */
    Optional<ColumnSet> columns(String kind, Collection<String> paths, LocalDate date);

    /**
     * As {@link #columns(String, Collection, LocalDate)}, read as the engine file binds the input (ENGINE SPEC, {@code inputs.<name>}):
     * {@code onOrBefore}, each record's latest version on or before the date (a reference kind loaded once, not every day: the read an
     * in-tree loader makes of documents as of a date); {@code field}, the rows are the items of each record's list-of-records field, the
     * record's own fields beside them (a parent's columns a child row lacks). Empty when the source cannot read so.
     */
    default Optional<ColumnSet> columns(String kind, Collection<String> paths, LocalDate date, boolean onOrBefore, String field) {
        return !onOrBefore && field == null ? columns(kind, paths, date) : Optional.empty();
    }

    /**
     * Every business date the kind's sources hold, oldest first (an input the engine declares {@code per: []}, not read per date, is read
     * on each of them: an official-records history, the desk P&amp;L of every day). Empty when the source cannot say or holds undated data.
     */
    default Optional<java.util.NavigableSet<LocalDate>> dates(String kind) {
        return Optional.empty();
    }

    /**
     * The columns the kind's source keeps as columns (its promoted columns): a read asks only these, a contract column outside them reads
     * as absent. Empty: the source cannot say, and every asked column is tried.
     */
    default java.util.Set<String> columnar(String kind) {
        return java.util.Set.of();
    }

    /** One vector column with some text columns, row by row in a stable order; false when the kind keeps no such vector column. */
    /**
     * A read-only view {@code name} of a kind's vectors on a date that the host already holds (R2_CUT_PLAN 3a: zero copy), or null:
     * what {@code InputTable.vectorView} answers for a view name that is not a vector column.
     */
    default Object vectorView(String kind, String name, LocalDate date) {
        return null;
    }

    boolean vectors(String kind, String column, List<String> texts, LocalDate date, VectorSink sink) throws Exception;
}
