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
import java.util.List;

/**
 * A bulk read of one kind (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 6.1): the chosen document paths, optional filters, and a business-date range.
 * Paths are document paths without the leading {@code $.} ({@code counterparty.id}). A source answers {@link SourcePlugin#scan} with
 * column batches, one business date per batch at most; an undated source ignores the range.
 *
 * @param kind the kind to read
 * @param paths the document paths wanted (the batch carries exactly these, in this order, after the entity id)
 * @param filters conjunctive filters on those paths, applied by the source where it can and always before a row is returned
 * @param from first business date, or null for the source's latest
 * @param to last business date, or null for {@code from}
 * @param batchRows the most rows in one batch
 */
public record ScanRequest(String kind, List<String> paths, List<Filter> filters, LocalDate from, LocalDate to, int batchRows) {

    /** The comparison a filter makes. */
    public enum Op { EQ, NE, LT, LE, GT, GE, IN }

    /** One condition on a path; {@code values} holds one value (any number for {@code IN}); numbers are compared as numbers, other values as text. */
    public record Filter(String path, Op op, List<Object> values) {
        public Filter {
            values = List.copyOf(values);
        }
    }

    public ScanRequest {
        paths = List.copyOf(paths);
        filters = filters == null ? List.of() : List.copyOf(filters);
        batchRows = batchRows <= 0 ? 65_536 : batchRows;
    }

    public ScanRequest(String kind, List<String> paths, LocalDate from, LocalDate to) {
        this(kind, paths, List.of(), from, to, 65_536);
    }
}
