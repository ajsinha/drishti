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
package com.ash.drishti.api;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One business date of one entity in a {@link HistoryRead}: the document (or only the asked fields, as a document) and where it came from
 * ({@link EntityDocument#provenance()}: source, generation, business date).
 *
 * @param date the business date this day is for (equal to the provenance's business date)
 * @param document the entity on that date; {@link EntityDocument#deleted()} when the source keeps a tombstone for the date
 * @param versions how many versions of this date the store keeps, or null when it cannot say cheaply
 */
public record HistoryDay(LocalDate date, EntityDocument document, Integer versions) {

    public HistoryDay {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(document, "document");
    }

    /** True when the store keeps more than one version of the date (a restatement, a second load). */
    public boolean restated() {
        return versions != null && versions > 1;
    }
}
