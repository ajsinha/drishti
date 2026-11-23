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

import com.ash.engine.Aggregate;
import com.ash.engine.Batch;
import com.ash.engine.BatchSink;
import com.ash.engine.EngineException;
import com.ash.engine.GroupScan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The grouped scan the host answers at the source (engine specification 5.2 rule 6; EN-46): the grouping columns and the closed list of
 * aggregates (SUM, COUNT, MIN, MAX) computed over the rows the caller may read, after the row rules, masks, column map and the site's row
 * filter, in one pass over the column arrays (no row is made). Offered only when it is exact for what is asked: grouping by declared columns,
 * aggregates of a declared column or of rows, no expression. Whole numbers are summed as longs (exact); others with a compensated sum, so a
 * floating-point total is within the engine's declared relative class.
 */
final class GroupedScan {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private GroupedScan() {
    }

    /** True when this host can answer {@code scan} exactly over {@code declared} columns. */
    static boolean offers(GroupScan scan, java.util.Set<String> declared) {
        if (scan.where() != null && !scan.where().isBlank()) {
            return false;
        }
        for (List<String> set : scan.sets()) {
            if (!declared.containsAll(set)) {
                return false;
            }
        }
        for (Aggregate a : scan.aggregates()) {
            if (a.where() != null && !a.where().isBlank()) {
                return false;
            }
            if (a.of() != null && !(NAME.matcher(a.of()).matches() && declared.contains(a.of()))) {
                return false;
            }
            if (a.of() == null && a.fn() != Aggregate.Fn.COUNT) {
                return false;
            }
        }
        return true;
    }

    /** One aggregate's state: exact while every value is whole, compensated otherwise. */
    private static final class State {
        long count;
        long whole;
        boolean allWhole = true;
        double sum;
        double comp;
        double min = Double.NaN;
        double max = Double.NaN;

        void add(double v) {
            count++;
            if (allWhole && ColumnBatch.whole(v)) {
                whole = Math.addExact(whole, (long) v);
            } else {
                if (allWhole) {
                    allWhole = false;
                    sum = whole;
                }
                double t = sum + v;                                  // Neumaier
                comp += Math.abs(sum) >= Math.abs(v) ? (sum - t) + v : (v - t) + sum;
                sum = t;
            }
            min = Double.isNaN(min) || v < min ? v : min;
            max = Double.isNaN(max) || v > max ? v : max;
        }

        Object result(Aggregate.Fn fn) {
            return switch (fn) {
                case COUNT -> count;
                case SUM -> count == 0 ? null : allWhole ? (Object) whole : (Object) (sum + comp);
                case MIN -> count == 0 ? null : (Object) min;
                case MAX -> count == 0 ? null : (Object) max;
            };
        }
    }

    private static final Comparator<List<String>> KEYS = (a, b) -> {
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i);
            String y = b.get(i);
            int c = x == null ? (y == null ? 0 : -1) : y == null ? 1 : x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    };

    /** Runs {@code scan} over the rows {@code rows} (null: every row of {@code size}) and delivers one batch stream per set. */
    static void run(GroupScan scan, Map<String, ColumnBatch.Column> columns, int[] rows, int size, BatchSink sink, LakeInputs.Check check)
            throws EngineException {
        int n = rows == null ? size : rows.length;
        for (List<String> set : scan.sets()) {
            TreeMap<List<String>, State[]> groups = new TreeMap<>(KEYS);
            List<ColumnBatch.Column> keyCols = set.stream().map(c -> columns.getOrDefault(c, ColumnBatch.Column.ABSENT)).toList();
            List<ColumnBatch.Column> ofCols = scan.aggregates().stream()
                    .map(a -> a.of() == null ? null : columns.getOrDefault(a.of(), ColumnBatch.Column.ABSENT)).toList();
            for (int i = 0; i < n; i++) {
                if ((i & 0xFFFF) == 0) {
                    check.run();
                }
                int r = rows == null ? i : rows[i];
                List<String> key = new ArrayList<>(keyCols.size());
                for (ColumnBatch.Column k : keyCols) {
                    key.add(k.text(r));
                }
                State[] states = groups.computeIfAbsent(key, k -> {
                    State[] s = new State[scan.aggregates().size()];
                    for (int j = 0; j < s.length; j++) {
                        s[j] = new State();
                    }
                    return s;
                });
                for (int j = 0; j < states.length; j++) {
                    ColumnBatch.Column of = ofCols.get(j);
                    if (of == null) {
                        states[j].count++;                            // COUNT of rows
                        continue;
                    }
                    double v = of.number(r);
                    if (!Double.isNaN(v)) {
                        states[j].add(v);
                    } else if (scan.aggregates().get(j).fn() == Aggregate.Fn.COUNT && of.value(r) != null) {
                        states[j].count++;                            // COUNT of a text column: its non-null values
                    }
                }
            }
            deliver(scan, set, groups, sink);
        }
    }

    private static void deliver(GroupScan scan, List<String> set, TreeMap<List<String>, State[]> groups, BatchSink sink) throws EngineException {
        int size = scan.batchRows() <= 0 ? 4_096 : scan.batchRows();
        List<Map.Entry<List<String>, State[]>> all = new ArrayList<>(groups.entrySet());
        for (int from = 0; from < Math.max(1, all.size()); from += size) {
            List<Map.Entry<List<String>, State[]>> part = all.subList(Math.min(from, all.size()), Math.min(all.size(), from + size));
            if (part.isEmpty() && from > 0) {
                break;
            }
            Map<String, Object[]> values = new TreeMap<>();
            for (int k = 0; k < set.size(); k++) {
                Object[] col = new Object[part.size()];
                for (int i = 0; i < part.size(); i++) {
                    col[i] = part.get(i).getKey().get(k);
                }
                values.put(set.get(k), col);
            }
            for (int j = 0; j < scan.aggregates().size(); j++) {
                Aggregate a = scan.aggregates().get(j);
                Object[] col = new Object[part.size()];
                for (int i = 0; i < part.size(); i++) {
                    col[i] = part.get(i).getValue()[j].result(a.fn());
                }
                values.put(a.name(), col);
            }
            sink.batch(new ValuesBatch(values, part.size()));
            if (part.isEmpty()) {
                break;
            }
        }
    }

    /** A grouped batch: each column's values as objects (String, Long, Double or null), read through any accessor. */
    private record ValuesBatch(Map<String, Object[]> values, int rows) implements Batch {

        private Object[] col(String c) {
            Object[] v = values.get(c);
            if (v == null) {
                throw new IllegalArgumentException("the column " + c + " is not in this grouped scan; it has " + values.keySet());
            }
            return v;
        }

        @Override
        public String[] texts(String c) {
            Object[] v = col(c);
            String[] a = new String[Math.max(rows, 1)];
            for (int i = 0; i < rows; i++) {
                a[i] = v[i] == null ? null : v[i] instanceof Double d && ColumnBatch.whole(d) ? Long.toString((long) (double) d) : v[i].toString();
            }
            return a;
        }

        @Override
        public long[] integers(String c) {
            Object[] v = col(c);
            long[] a = new long[Math.max(rows, 1)];
            for (int i = 0; i < rows; i++) {
                if (v[i] instanceof Long l) {
                    a[i] = l;
                } else if (v[i] instanceof Double d && ColumnBatch.whole(d)) {
                    a[i] = (long) (double) d;
                } else if (v[i] != null) {
                    throw new IllegalArgumentException(c + " is not an integer column: " + v[i]);
                }
            }
            return a;
        }

        @Override
        public double[] numbers(String c) {
            Object[] v = col(c);
            double[] a = new double[Math.max(rows, 1)];
            for (int i = 0; i < rows; i++) {
                a[i] = v[i] instanceof Number n ? n.doubleValue() : Double.NaN;
            }
            return a;
        }

        @Override
        public boolean[] flags(String c) {
            Object[] v = col(c);
            boolean[] a = new boolean[Math.max(rows, 1)];
            for (int i = 0; i < rows; i++) {
                a[i] = v[i] instanceof Boolean b && b;
            }
            return a;
        }

        @Override
        public boolean isNull(String c, int row) {
            return col(c)[row] == null;
        }
    }
}
