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

import com.ash.drishti.api.EntityRef;
import java.util.List;
import java.util.Map;

/**
 * A search's answer, field for field what {@code GET /api/v1/search} answers.
 *
 * @param query      the search as typed
 * @param kind       the kind searched
 * @param mnemonic   the kind's mnemonic
 * @param condition  the condition run (with the person's row rule joined)
 * @param orderBy    the order, or null
 * @param descending whether the order is descending
 * @param limit      the most rows asked
 * @param columns    the fields shown, as paths ({@code $.CURRENCY})
 * @param labels     each column's label
 * @param scanned    records read
 * @param matched    records that matched (an entity two nodes matched counted once)
 * @param partial    true when a source failed or did not answer in time
 * @param failed     the sources that failed, and why
 * @param elapsedMs  the time taken
 * @param pivot      the pivot offer when the kind's pack opts in, else null
 * @param noData     why nothing was read (no data of the kind on the date), else null
 * @param rows       the rows, masked for the person
 * @param nodes      which node answered what: for administrators only, else null
 */
public record SearchAnswer(String query, String kind, String mnemonic, String condition, String orderBy, boolean descending, int limit,
        List<String> columns, Map<String, String> labels, long scanned, long matched, boolean partial, List<Failure> failed, double elapsedMs,
        Map<String, Object> pivot, Map<String, Object> noData, List<Row> rows, List<Map<String, Object>> nodes) {

    /** Copies every list and map (null ones become empty, except the optional {@code pivot}, {@code noData} and {@code nodes}). */
    public SearchAnswer {
        columns = columns == null ? List.of() : List.copyOf(columns);
        labels = labels == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(labels));
        failed = failed == null ? List.of() : List.copyOf(failed);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * One matching record.
     *
     * @param ref    the entity
     * @param title  its title in the search index
     * @param values the search's fields for it, by path, masked
     */
    public record Row(EntityRef ref, String title, Map<String, Object> values) {
    }

    /**
     * A source that failed or did not answer in time.
     *
     * @param source the source
     * @param reason why
     */
    public record Failure(String source, String reason) {
    }
}
