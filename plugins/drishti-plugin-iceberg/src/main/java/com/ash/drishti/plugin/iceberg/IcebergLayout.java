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
package com.ash.drishti.plugin.iceberg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The table layout the connector reads fastest, shared by the connector, {@link IcebergLoader} and
 * {@link IcebergMaintenance}: one table per kind with columns {@code id}, {@code doc} (the whole JSON document),
 * {@code business_date} (an identity partition) and the pack's promoted fields beside them ({@code counterparty.id} is
 * column {@code counterparty__id}; numbers are {@code double}, everything else {@code string}; a vector field,
 * {@code layout.<kind>.vectors}, is an optional {@code list<double>} column whose missing elements are null, never 0); each business day
 * sorted by id and cut into files of {@code file-rows} rows, each file its own id range; small Parquet row groups, so
 * one entity is one small row group; column metrics (minimum and maximum) for every column but {@code doc}.
 */
public final class IcebergLayout {

    public static final String ID = "id";
    public static final String DOC = "doc";
    public static final String DATE = "business_date";
    /** Table property: the rows per file the layout cuts each day into (maintenance re-cuts drifted days to it). */
    public static final String FILE_ROWS = "drishti.layout.file-rows";
    public static final int DEFAULT_FILE_ROWS = 250_000;
    /**
     * About 900 trade documents a row group (measured: zstd packs a 6.3 KB trade into about 0.7 KB, and the writer
     * measures a row group compressed), so one entity's read decodes about 6 MB of documents.
     */
    public static final long DEFAULT_ROW_GROUP_BYTES = 1024L * 1024;

    /** One entity's row for a business day: its document and its promoted fields (path to a Double, a String or null). */
    public record Row(String id, String doc, Map<String, Object> columns) {}

    private IcebergLayout() {
    }

    /** The column a promoted document path is stored in. */
    public static String column(String path) {
        return path.replace(".", "__");
    }

    /**
     * A vector value as a list of doubles, a null element kept null (never 0); null for anything that is not a list of numbers (the
     * loader rejected such a row already).
     */
    static List<Double> vector(Object v) {
        if (!(v instanceof List<?> list)) {
            return null;
        }
        List<Double> out = new ArrayList<>(list.size());
        for (Object e : list) {
            if (e != null && !(e instanceof Number)) {
                return null;
            }
            out.add(e == null ? null : ((Number) e).doubleValue());
        }
        return out;
    }
}
