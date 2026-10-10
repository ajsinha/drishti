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

/**
 * Some rows of a scan, column by column, in the Arrow type model without Arrow's memory libraries: a text column is a {@code String[]}
 * (null where absent), a number column a {@code double[]} ({@code NaN} where absent), a boolean column a {@code boolean[]}.
 * Row {@code i} is the entity {@code ids[i]} on {@code businessDate}. The columns follow the request's paths.
 *
 * @param businessDate the date every row is for, or null for undated data
 * @param ids the entity ids
 * @param names the document paths, as requested
 * @param types the column types
 * @param data one array per column
 */
public record ColumnBatch(LocalDate businessDate, String[] ids, String[] names, Type[] types, Object[] data) {

    /** A column's type. */
    public enum Type { TEXT, NUMBER, BOOLEAN }

    public int rows() {
        return ids.length;
    }

    /** The value at row {@code r} of column {@code c}: a String, Double, Boolean or null. */
    public Object value(int c, int r) {
        return switch (types[c]) {
            case NUMBER -> {
                double d = ((double[]) data[c])[r];
                yield Double.isNaN(d) ? null : (Object) d;
            }
            case BOOLEAN -> (Object) ((boolean[]) data[c])[r];
            case TEXT -> ((String[]) data[c])[r];
        };
    }
}
