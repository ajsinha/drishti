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
package com.ash.drishti.compute.var;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attributes of many rows, column by column (the position index of VAR_GUIDE.md section 25): a text column is an {@code int}
 * code per row into a dictionary (books, desks, currencies repeat, so a few hundred bytes a row at most), a number column a
 * {@code double[]} (NaN where none). No object per row.
 */
public final class Columns {

    private final Map<String, String[]> dicts = new LinkedHashMap<>();
    private final Map<String, int[]> codes = new LinkedHashMap<>();
    private final Map<String, double[]> numbers = new LinkedHashMap<>();
    private final int rows;

    private Columns(int rows) {
        this.rows = rows;
    }

    /**
     * Columns from their parts (a hot file read back, {@link VarHotFile}): per text column its dictionary and a code per row, per number
     * column a value per row, every array {@code rows} long.
     */
    static Columns of(int rows, Map<String, String[]> dicts, Map<String, int[]> codes, Map<String, double[]> numbers) {
        Columns c = new Columns(rows);
        codes.forEach((k, v) -> {
            c.codes.put(k, v);
            c.dicts.put(k, dicts.getOrDefault(k, new String[0]));
        });
        c.numbers.putAll(numbers);
        return c;
    }

    public int rows() {
        return rows;
    }

    /** The text columns, in order. */
    public List<String> textColumns() {
        return List.copyOf(codes.keySet());
    }

    /** The number columns, in order. */
    public List<String> numberColumns() {
        return List.copyOf(numbers.keySet());
    }

    public boolean has(String column) {
        return codes.containsKey(column) || numbers.containsKey(column);
    }

    /** The text of row {@code row}, null where none (a number column's value as text). */
    public String text(String column, int row) {
        int[] c = codes.get(column);
        if (c != null) {
            return c[row] < 0 ? null : dicts.get(column)[c[row]];
        }
        double[] n = numbers.get(column);
        return n == null || Double.isNaN(n[row]) ? null : (n[row] == Math.rint(n[row]) && Math.abs(n[row]) < 1e15
                ? Long.toString((long) n[row]) : Double.toString(n[row]));
    }

    /** The number of row {@code row}, NaN where none or not a number column. */
    public double number(String column, int row) {
        double[] n = numbers.get(column);
        return n == null ? Double.NaN : n[row];
    }

    /** A text column's code of row {@code row} (-1: none), and its dictionary: grouping without strings. */
    public int code(String column, int row) {
        int[] c = codes.get(column);
        return c == null ? -1 : c[row];
    }

    public String[] dictionary(String column) {
        return dicts.getOrDefault(column, new String[0]);
    }

    /** The value a rule reads: a number for a number column, a list for a list column (written {@code a|b|c}), else the text. */
    public Object value(String column, int row) {
        if (numbers.containsKey(column)) {
            double v = numbers.get(column)[row];
            return Double.isNaN(v) ? null : v;
        }
        String t = text(column, row);
        if (t != null && column.equals("TAGS")) {
            return t.isEmpty() ? List.of() : List.of(t.split("\\|"));
        }
        return t;
    }

    /** These columns with their rows in another order: row {@code i} of the result is row {@code order[i]} of this. */
    public Columns permuted(int[] order) {
        Columns out = new Columns(order.length);
        codes.forEach((k, c) -> {
            int[] p = new int[order.length];
            for (int i = 0; i < order.length; i++) {
                p[i] = c[order[i]];
            }
            out.codes.put(k, p);
            out.dicts.put(k, dicts.get(k));
        });
        numbers.forEach((k, n) -> {
            double[] p = new double[order.length];
            for (int i = 0; i < order.length; i++) {
                p[i] = n[order[i]];
            }
            out.numbers.put(k, p);
        });
        return out;
    }

    /** Builds columns row by row. */
    public static final class Builder {
        private final List<String> textCols;
        private final List<String> numberCols;
        private final List<Map<String, Integer>> pools = new ArrayList<>();
        private final List<List<String>> dictLists = new ArrayList<>();
        private final List<int[]> codeArrays = new ArrayList<>();
        private final List<double[]> numberArrays = new ArrayList<>();
        private int rows;

        public Builder(List<String> textColumns, List<String> numberColumns) {
            this.textCols = List.copyOf(textColumns);
            this.numberCols = List.copyOf(numberColumns);
            for (int i = 0; i < textCols.size(); i++) {
                pools.add(new HashMap<>());
                dictLists.add(new ArrayList<>());
                codeArrays.add(new int[64]);
            }
            for (int i = 0; i < numberCols.size(); i++) {
                numberArrays.add(new double[64]);
            }
        }

        public List<String> textColumns() {
            return textCols;
        }

        public List<String> numberColumns() {
            return numberCols;
        }

        /** A row: its texts in {@link #textColumns()} order, its numbers in {@link #numberColumns()} order (null: none). */
        public int add(String[] texts, double[] nums) {
            for (int i = 0; i < textCols.size(); i++) {
                int[] a = codeArrays.get(i);
                if (rows == a.length) {
                    a = java.util.Arrays.copyOf(a, a.length * 2);
                    codeArrays.set(i, a);
                }
                String t = texts == null || i >= texts.length ? null : texts[i];
                if (t == null) {
                    a[rows] = -1;
                } else {
                    Map<String, Integer> pool = pools.get(i);
                    Integer code = pool.get(t);
                    if (code == null) {
                        code = dictLists.get(i).size();
                        dictLists.get(i).add(t);
                        pool.put(t, code);
                    }
                    a[rows] = code;
                }
            }
            for (int i = 0; i < numberCols.size(); i++) {
                double[] a = numberArrays.get(i);
                if (rows == a.length) {
                    a = java.util.Arrays.copyOf(a, a.length * 2);
                    numberArrays.set(i, a);
                }
                a[rows] = nums == null || i >= nums.length ? Double.NaN : nums[i];
            }
            return rows++;
        }

        public int rows() {
            return rows;
        }

        public Columns build() {
            Columns c = new Columns(rows);
            for (int i = 0; i < textCols.size(); i++) {
                c.codes.put(textCols.get(i), java.util.Arrays.copyOf(codeArrays.get(i), rows));
                c.dicts.put(textCols.get(i), dictLists.get(i).toArray(new String[0]));
            }
            for (int i = 0; i < numberCols.size(); i++) {
                c.numbers.put(numberCols.get(i), java.util.Arrays.copyOf(numberArrays.get(i), rows));
            }
            return c;
        }
    }
}
