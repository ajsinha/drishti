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
 * A page of a live set, field for field what {@code GET /api/v1/live/query} answers.
 *
 * @param set         the set as described to the person (id, kind, columns...)
 * @param live        whether the set streams changes (the live date and a kind with a live binding)
 * @param total       rows in the set
 * @param seq         the set's sequence number at this page
 * @param offset      the first row's position
 * @param rows        the rows, masked for the person
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
