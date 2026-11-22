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
package com.ash.drishti.enginehost;

import com.ash.engine.Batch;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rows as column arrays over columns the read API handed over (EN-48: column arrays by reference). When a batch is the whole of the rows,
 * unfiltered, an array of the asked carrier is the reader's own array, not a copy; otherwise the asked rows are gathered into arrays this
 * batch owns and reuses for the next batch (engine specification 5.2: the arrays belong to the host, an engine copies what it keeps).
 * A masked column reads as null in every row.
 */
final class ColumnBatch implements Batch {

    /** One input column as read: exactly one of numbers, texts, mixed is set; {@code masked}: every value reads as null. */
    record Column(double[] numbers, String[] texts, Object[] mixed, boolean masked) {

        static final Column ABSENT = new Column(null, null, null, true);

        Object value(int row) {
            if (masked) {
                return null;
            }
            if (numbers != null) {
                return Double.isNaN(numbers[row]) ? null : numbers[row];
            }
            if (texts != null) {
                return texts[row];
            }
            return mixed == null ? null : mixed[row];
        }

        /** The value as text (a whole number without a decimal point), null for none: what a scope filter and a group key compare. */
        String text(int row) {
            Object v = value(row);
            if (v instanceof Double d) {
                return whole(d) ? Long.toString((long) (double) d) : Double.toString(d);
            }
            return v == null ? null : v.toString();
        }

        /** The value as a number, NaN for none or for text that is not a number. */
        double number(int row) {
            Object v = value(row);
            if (v instanceof Double d) {
                return d;
            }
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s.strip());
                } catch (NumberFormatException e) {
                    return Double.NaN;
                }
            }
            return Double.NaN;
        }
    }

    static boolean whole(double d) {
        return d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15;
    }

    private final Map<String, Column> columns;
    private final List<String> asked;
    private int[] rows;            // the rows of this batch (indexes into the columns), or null: rows 0..n-1
    private int n;
    private final Map<String, Object> own = new HashMap<>();

    ColumnBatch(Map<String, Column> columns, List<String> asked) {
        this.columns = columns;
        this.asked = List.copyOf(asked);
    }

    /** The next batch: {@code rows} (null: the first {@code n} rows, in order). */
    void at(int[] rows, int n) {
        this.rows = rows;
        this.n = n;
    }

    private Column column(String c) {
        if (!asked.contains(c)) {
            throw new IllegalArgumentException("the column " + c + " was not asked in this scan; asked: " + asked);
        }
        return columns.getOrDefault(c, Column.ABSENT);
    }

    private int row(int i) {
        return rows == null ? i : rows[i];
    }

    @Override
    public int rows() {
        return n;
    }

    @Override
    public String[] texts(String c) {
        Column col = column(c);
        if (!col.masked() && col.texts() != null && rows == null) {
            return col.texts();                                   // by reference
        }
        String[] a = buffer(c + "#t", String[].class);
        for (int i = 0; i < n; i++) {
            a[i] = col.text(row(i));
        }
        return a;
    }

    @Override
    public long[] integers(String c) {
        Column col = column(c);
        long[] a = buffer(c + "#i", long[].class);
        for (int i = 0; i < n; i++) {
            double d = col.number(row(i));
            if (Double.isNaN(d)) {
                Object v = col.value(row(i));
                if (v != null) {
                    throw new IllegalArgumentException(c + " is not an integer column: " + v);
                }
                a[i] = 0;
            } else if (whole(d)) {
                a[i] = (long) d;
            } else {
                throw new IllegalArgumentException(c + " is not an integer column: " + d);
            }
        }
        return a;
    }

    @Override
    public double[] numbers(String c) {
        Column col = column(c);
        if (!col.masked() && col.numbers() != null && rows == null) {
            return col.numbers();                                 // by reference: NaN is a null
        }
        double[] a = buffer(c + "#n", double[].class);
        for (int i = 0; i < n; i++) {
            a[i] = col.number(row(i));
        }
        return a;
    }

    @Override
    public boolean[] flags(String c) {
        Column col = column(c);
        boolean[] a = buffer(c + "#f", boolean[].class);
        for (int i = 0; i < n; i++) {
            Object v = col.value(row(i));
            a[i] = v instanceof Boolean b ? b : v instanceof String s ? Boolean.parseBoolean(s.strip()) : v instanceof Double d && d != 0;
        }
        return a;
    }

    @Override
    public boolean isNull(String c, int row) {
        return column(c).value(row(row)) == null;
    }

    /** This batch's own array for a column and carrier, reused by the next batch (at least as long as the largest batch so far). */
    @SuppressWarnings("unchecked")
    private <T> T buffer(String key, Class<T> type) {
        Object a = own.get(key);
        if (a == null || java.lang.reflect.Array.getLength(a) < n) {
            a = java.lang.reflect.Array.newInstance(type.getComponentType(), Math.max(n, 1));
            own.put(key, a);
        } else if (a instanceof String[] s) {
            Arrays.fill(s, n, s.length, null);
        }
        return (T) a;
    }
}
