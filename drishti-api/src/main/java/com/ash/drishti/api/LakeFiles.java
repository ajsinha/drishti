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
import java.util.Map;

/**
 * A kind's data files on one business date, for an engine that reads them itself (the pivot engine's direct lake route,
 * docs/architecture/PIVOT_ENGINE.md section 6.2): the Parquet files of the date's snapshot, and which column of them holds each field. The
 * reader reads only the columns it needs (column pruning) and lets the Parquet footers' statistics skip row groups.
 *
 * @param format {@code delta} or {@code iceberg}
 * @param files the Parquet files to read, as local paths (a source on an object store gives none: the reader goes through the connector)
 * @param columns field (document path) to the physical column that holds it
 * @param idColumn the column of the entity id (the kind's key)
 * @param businessDate the date the files are of (null: undated)
 * @param bytes the files' total size
 * @param rows the rows they hold (-1: unknown without reading)
 */
public record LakeFiles(String format, List<String> files, Map<String, String> columns, String idColumn, LocalDate businessDate, long bytes,
        long rows) {

    public LakeFiles {
        files = List.copyOf(files);
        columns = Map.copyOf(columns);
    }
}
