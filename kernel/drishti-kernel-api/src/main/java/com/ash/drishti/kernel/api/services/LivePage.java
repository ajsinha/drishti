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
package com.ash.drishti.kernel.api.services;

import java.util.List;
import java.util.Map;

/**
 * The first page of a search read as a query on a live table ({@code GET /api/v1/rdp/searches}).
 *
 * @param set         {@code id} and {@code table} (the table's name) and the query ({@code table}, {@code query}: filter, sort, limit) the next pages and changes are read with
 * @param live        whether the table follows the kind's changes (the live business date)
 * @param total       rows matching
 * @param seq         0 (the table's position is in the Data Protocol's {@code version})
 * @param offset      the first row's position
 * @param rows        the rows, {@code {id, doc}} (the row by column name), masked for the person
 * @param search      what the search said (columns, labels, matched, scanned, partial, failed, noData, pivot)
 * @param asOfNotices the sources that carried an earlier date forward, on a past date
 */
public record LivePage(Map<String, Object> set, boolean live, int total, long seq, int offset, List<Map<String, Object>> rows,
        Map<String, Object> search, List<Map<String, Object>> asOfNotices) {

    /** Copies the lists (null ones become empty). */
    public LivePage {
        rows = rows == null ? List.of() : List.copyOf(rows);
        asOfNotices = asOfNotices == null ? List.of() : List.copyOf(asOfNotices);
    }
}
