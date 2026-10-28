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

    /**
     * Several column sets one after the other (a sharded date's shards, VAR_SHARDING.md): the rows of the first, then of the second ...; the
     * columns of the first, each dictionary the union of theirs (codes renumbered). Every part has the same columns.
     */
    static Columns concat(List<Columns> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        int n = 0;
        for (Columns p : parts) {
            n += p.rows;
        }
        Columns out = new Columns(n);
        Columns head = parts.get(0);
        for (String col : head.codes.keySet()) {
            Map<String, Integer> pool = new LinkedHashMap<>();
            int[] codes = new int[n];
            int at = 0;
            for (Columns p : parts) {
                int[] c = p.codes.get(col);
                String[] dict = p.dicts.get(col);
                if (c == null) {
                    throw new IllegalArgumentException("a shard has no column " + col);
                }
                int[] remap = new int[dict.length];
                for (int k = 0; k < dict.length; k++) {
                    remap[k] = pool.computeIfAbsent(dict[k], x -> pool.size());
                }
                for (int r = 0; r < p.rows; r++) {
                    codes[at + r] = c[r] < 0 ? -1 : remap[c[r]];
                }
                at += p.rows;
            }
            out.codes.put(col, codes);
            out.dicts.put(col, pool.keySet().toArray(new String[0]));
        }
        for (String col : head.numbers.keySet()) {
            double[] v = new double[n];
            int at = 0;
            for (Columns p : parts) {
                double[] x = p.numbers.get(col);
                if (x == null) {
                    throw new IllegalArgumentException("a shard has no column " + col);
                }
                System.arraycopy(x, 0, v, at, p.rows);
                at += p.rows;
            }
            out.numbers.put(col, v);
        }
        return out;
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

    /**
     * These columns with some rows replaced and rows appended (an intraday batch, VAR_GUIDE.md section 8.4): each row given as its texts
     * in {@link #textColumns()} order and its numbers in {@link #numberColumns()} order (a null array: that part unchanged; a null text:
     * none). Every array is copied once, the dictionaries extended where a text is new.
     */
    public Columns edited(Map<Integer, String[]> texts, Map<Integer, double[]> nums, List<String[]> addTexts, List<double[]> addNums) {
        int n = rows + addTexts.size();
        Columns out = new Columns(n);
        List<String> tcols = textColumns();
        for (int i = 0; i < tcols.size(); i++) {
            String col = tcols.get(i);
            int[] c = java.util.Arrays.copyOf(codes.get(col), n);
            List<String> dict = new ArrayList<>(java.util.Arrays.asList(dicts.get(col)));
            Map<String, Integer> pool = null;
            int ci = i;
            java.util.function.Function<String, Integer> codeOf = null;
            for (int r = rows; r < n; r++) {
                c[r] = -1;
            }
            if (!texts.isEmpty() || !addTexts.isEmpty()) {
                pool = new HashMap<>();
                for (int k = 0; k < dict.size(); k++) {
                    pool.putIfAbsent(dict.get(k), k);
                }
                Map<String, Integer> p = pool;
                codeOf = t -> t == null ? -1 : p.computeIfAbsent(t, x -> {
                    dict.add(x);
                    return dict.size() - 1;
                });
            }
            if (codeOf != null) {
                for (Map.Entry<Integer, String[]> e : texts.entrySet()) {
                    if (e.getValue() != null) {
                        c[e.getKey()] = codeOf.apply(ci < e.getValue().length ? e.getValue()[ci] : null);
                    }
                }
                for (int a = 0; a < addTexts.size(); a++) {
                    String[] t = addTexts.get(a);
                    c[rows + a] = codeOf.apply(t != null && ci < t.length ? t[ci] : null);
                }
            }
            out.codes.put(col, c);
            out.dicts.put(col, dict.toArray(new String[0]));
        }
        List<String> ncols = numberColumns();
        for (int i = 0; i < ncols.size(); i++) {
            String col = ncols.get(i);
            double[] v = java.util.Arrays.copyOf(numbers.get(col), n);
            for (int r = rows; r < n; r++) {
                double[] a = addNums.get(r - rows);
                v[r] = a != null && i < a.length ? a[i] : Double.NaN;
            }
            for (Map.Entry<Integer, double[]> e : nums.entrySet()) {
                if (e.getValue() != null) {
                    v[e.getKey()] = i < e.getValue().length ? e.getValue()[i] : Double.NaN;
                }
            }
            out.numbers.put(col, v);
        }
        return out;
    }

    /** These columns with some rows of one text column replaced ({@code values[row]}, null: the row unchanged). */
    Columns withTexts(String column, String[] values) {
        Columns out = new Columns(rows);
        out.codes.putAll(codes);
        out.dicts.putAll(dicts);
        out.numbers.putAll(numbers);
        int[] c = java.util.Arrays.copyOf(codes.get(column), rows);
        List<String> dict = new ArrayList<>(java.util.Arrays.asList(dicts.get(column)));
        Map<String, Integer> pool = new HashMap<>();
        for (int k = 0; k < dict.size(); k++) {
            pool.putIfAbsent(dict.get(k), k);
        }
        for (int r = 0; r < rows; r++) {
            if (values[r] != null) {
                c[r] = pool.computeIfAbsent(values[r], x -> {
                    dict.add(x);
                    return dict.size() - 1;
                });
            }
        }
        out.codes.put(column, c);
        out.dicts.put(column, dict.toArray(new String[0]));
        return out;
    }

    /** Row {@code row}'s texts in {@link #textColumns()} order. */
    public String[] textsOf(int row) {
        List<String> t = textColumns();
        String[] out = new String[t.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = text(t.get(i), row);
        }
        return out;
    }

    /** Row {@code row}'s numbers in {@link #numberColumns()} order. */
    public double[] numbersOf(int row) {
        List<String> t = numberColumns();
        double[] out = new double[t.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = number(t.get(i), row);
        }
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
