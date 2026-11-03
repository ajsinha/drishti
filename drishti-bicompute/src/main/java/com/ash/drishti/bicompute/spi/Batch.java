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
package com.ash.drishti.bicompute.spi;

/**
 * A columnar batch of a cell's rows for {@link AggFunction#add} (PIVOT_ENGINE.md section 8.1): the function's declared input columns only,
 * already filtered, ruled and masked for the reader. A number column is a {@code double[]} ({@code NaN} where absent), a text column a
 * {@code String[]} (null where absent).
 *
 * @param rows the rows in the batch (columns may be longer: only the first {@code rows} entries count)
 * @param columns one array per input, in the function's input order
 */
public record Batch(int rows, Object[] columns) {

    /** The input {@code i} as numbers (a text column is parsed; unparseable text is absent). */
    public double[] num(int i) {
        Object c = columns[i];
        if (c instanceof double[] d) {
            return d;
        }
        String[] s = (String[]) c;
        double[] out = new double[rows];
        for (int r = 0; r < rows; r++) {
            try {
                out[r] = s[r] == null ? Double.NaN : Double.parseDouble(s[r]);
            } catch (NumberFormatException e) {
                out[r] = Double.NaN;
            }
        }
        return out;
    }

    /** The input {@code i} as text (a number column is written as Java writes a double; {@code NaN} is absent). */
    public String[] text(int i) {
        Object c = columns[i];
        if (c instanceof String[] s) {
            return s;
        }
        double[] d = (double[]) c;
        String[] out = new String[rows];
        for (int r = 0; r < rows; r++) {
            out[r] = Double.isNaN(d[r]) ? null : Double.toString(d[r]);
        }
        return out;
    }

    /** One number column as a batch. */
    public static Batch of(double[] values) {
        return new Batch(values.length, new Object[] {values});
    }

    /** A slice {@code [from, to)} of this batch (a copy of each column's range). */
    public Batch slice(int from, int to) {
        Object[] cols = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            cols[i] = columns[i] instanceof double[] d ? java.util.Arrays.copyOfRange(d, from, to)
                    : java.util.Arrays.copyOfRange((String[]) columns[i], from, to);
        }
        return new Batch(to - from, cols);
    }
}
