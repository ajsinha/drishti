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
package com.ash.drishti.server.bi.sql;

import com.ash.drishti.common.Branding;
import java.util.Set;

/** The functions of Drishti SQL (docs/guides/BI_LANGUAGE_REFERENCE.md): which aggregate, which only run over the answer's rows, which compute a value, and the refused ones. */
final class SqlFunctions {

    /** Aggregates over a dataset's rows: each becomes a local measure (DEL) of the model query. */
    static final Set<String> AGGREGATES = Set.of("SUM", "AVG", "MIN", "MAX", "COUNT", "PERCENTILE_CONT", "PERCENTILE_DISC", "QUANTILE_CONT");
    /** Window functions: they run over the answer's rows ({@code OVER (...)}), never a dataset's. */
    static final Set<String> WINDOWS = Set.of("RANK", "DENSE_RANK", "ROW_NUMBER", "PERCENT_RANK", "CUME_DIST", "NTILE", "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE");
    /** Value functions allowed over answers (a composite statement's outer query). */
    static final Set<String> SCALARS = Set.of("COALESCE", "ABS", "ROUND", "UPPER", "LOWER", "LENGTH", "CHAR_LENGTH", "NULLIF", "GREATEST", "LEAST", "YEAR", "QUARTER",
            "MONTH", "WEEK", "DAY", "FLOOR", "CEIL", "SIGN");
    /** Functions that read files, URLs, the environment or chance: never. */
    static final Set<String> FORBIDDEN = Set.of("READ_PARQUET", "READ_CSV", "READ_CSV_AUTO", "READ_JSON", "READ_JSON_AUTO", "READ_TEXT", "READ_BLOB", "PARQUET_SCAN",
            "GLOB", "DELTA_SCAN", "ICEBERG_SCAN", "SQLITE_SCAN", "POSTGRES_SCAN", "RANDOM", "UUID", "GEN_RANDOM_UUID", "SETSEED", "GETENV", "CURRENT_SETTING", "QUERY",
            "QUERY_TABLE", "HTTPFS", "PRAGMA");

    private SqlFunctions() {}

    static DsqlException refused(String name, int at) {
        if (FORBIDDEN.contains(name)) {
            boolean file = name.startsWith("READ_") || name.endsWith("_SCAN") || "GLOB".equals(name);
            return new DsqlException(file ? DsqlException.SCOPE : DsqlException.FUNCTION, name.toLowerCase(java.util.Locale.ROOT) + "() is refused: "
                    + (file ? Branding.sqlName() + " reads the model's datasets only, never a file, URL or connector" : Branding.sqlName() + " computes the same answer every time"), null, at);
        }
        return new DsqlException(DsqlException.FUNCTION, name.toLowerCase(java.util.Locale.ROOT) + "() is not a function of " + Branding.sqlName(),
                "aggregates: SUM, AVG, MIN, MAX, COUNT, COUNT(DISTINCT), PERCENTILE_CONT; values: COALESCE, ABS, ROUND, UPPER, LOWER, LENGTH, CASE, CAST; windows over the answer: RANK, DENSE_RANK, ROW_NUMBER, PERCENT_RANK, LAG, LEAD", at);
    }
}
