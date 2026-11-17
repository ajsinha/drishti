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
import java.time.LocalDate;
import java.util.List;

/**
 * A field over business days, oldest first: field for field what {@code GET /api/v1/history/{kind}/{id}/series} answers.
 *
 * @param ref    the record
 * @param path   the expression read ({@code $.mtm})
 * @param label  its label
 * @param points one per business day
 * @param dated  false when no dated source answered (one value repeated)
 */
public record Series(EntityRef ref, String path, String label, List<Point> points, boolean dated) {

    /** Copies the points. */
    public Series {
        points = points == null ? List.of() : List.copyOf(points);
    }

    /**
     * One business day.
     *
     * @param date       the business day
     * @param value      the field's value that day, null when the record was not there
     * @param dataDate   the date of the data the source answered with
     * @param source     the source that answered
     * @param generation the source's generation
     */
    public record Point(LocalDate date, Object value, LocalDate dataDate, String source, Long generation) {
    }
}
