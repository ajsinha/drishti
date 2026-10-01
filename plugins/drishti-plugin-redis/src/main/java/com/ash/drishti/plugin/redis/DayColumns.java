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
package com.ash.drishti.plugin.redis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One business day of one kind's promoted values, gathered by the loader while the day's documents stream to Redis and
 * written column-wise when the input ends ({@link #chunks}). Compact: a number column is a {@code double[]} (NaN for
 * null), a text column a {@code String[]} whose repeated values share one string, each allocated only when the column
 * first holds such a value; about 230 MB per million trades with the trading pack's nineteen fields. A later row for
 * the same id replaces the earlier one, so loading the same rows twice gives the same columns.
 */
final class DayColumns {

    private static final int GROW = 65_536;

    private final Map<String, Integer> rowOf = new HashMap<>();
    private final Map<String, Column> columns = new LinkedHashMap<>();
    private final Map<String, String> pool = new HashMap<>();
    private String[] ids = new String[1024];
    private int rows;

    /** A promoted path's values: numbers and texts, either array absent until a row needs it. */
    private static final class Column {
        double[] numbers;
        String[] texts;

        void grow(int capacity) {
            if (numbers != null && numbers.length < capacity) {
                int old = numbers.length;
                numbers = Arrays.copyOf(numbers, capacity);
                Arrays.fill(numbers, old, capacity, Double.NaN);
            }
            if (texts != null && texts.length < capacity) {
                texts = Arrays.copyOf(texts, capacity);
            }
        }
    }

    synchronized int rows() {
        return rows;
    }

    /** Adds (or replaces) an entity's promoted values: path to a Double, a String or null. */
    synchronized void put(String id, Map<String, Object> values) {
        Integer at = rowOf.get(id);
        int row;
        if (at == null) {
            row = rows++;
            if (row == ids.length) {
                ids = Arrays.copyOf(ids, ids.length + Math.max(GROW, ids.length / 2));
                columns.values().forEach(c -> c.grow(ids.length));
            }
            ids[row] = id;
            rowOf.put(id, row);
        } else {
            row = at;
            columns.values().forEach(c -> {
                if (c.numbers != null) {
                    c.numbers[row] = Double.NaN;
                }
                if (c.texts != null) {
                    c.texts[row] = null;
                }
            });
        }
        values.forEach((path, v) -> {
            Column c = columns.computeIfAbsent(path, p -> new Column());
            if (v instanceof Number n) {
                if (c.numbers == null) {
                    c.numbers = new double[ids.length];
                    Arrays.fill(c.numbers, Double.NaN);
                }
                c.numbers[row] = n.doubleValue();
            } else if (v != null) {
                if (c.texts == null) {
                    c.texts = new String[ids.length];
                }
                String s = String.valueOf(v);
                c.texts[row] = pool.size() > 500_000 ? s : pool.computeIfAbsent(s, k -> k);
            }
        });
    }

    /** True when the day already holds the id (the loader merges an earlier load's rows only where this is false). */
    synchronized boolean has(String id) {
        return rowOf.containsKey(id);
    }

    /** The ids and columns in id order, cut into chunks of {@code chunkRows}: what the day's column hash holds. */
    synchronized Chunks chunks(int chunkRows, long version) {
        Integer[] boxed = new Integer[rows];
        for (int i = 0; i < rows; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, (a, b) -> ids[a].compareTo(ids[b]));
        int[] order = new int[rows];
        for (int i = 0; i < rows; i++) {
            order[i] = boxed[i];
        }
        Map<String, Boolean> types = new LinkedHashMap<>();
        columns.forEach((path, c) -> types.put(path, c.texts == null && c.numbers != null));
        int chunks = Math.max(1, -(-rows / chunkRows));
        Map<String, byte[]> fields = new LinkedHashMap<>();
        for (int k = 0; k < chunks; k++) {
            int from = k * chunkRows;
            int to = Math.min(rows, from + chunkRows);
            String[] sortedIds = new String[to - from];
            for (int i = from; i < to; i++) {
                sortedIds[i - from] = ids[order[i]];
            }
            fields.put(RedisLayout.field(RedisLayout.IDS, k), ColumnCodec.texts(sortedIds, 0, sortedIds.length));
            for (Map.Entry<String, Column> e : columns.entrySet()) {
                Column c = e.getValue();
                if (types.get(e.getKey())) {
                    double[] v = new double[to - from];
                    for (int i = from; i < to; i++) {
                        v[i - from] = c.numbers[order[i]];
                    }
                    fields.put(RedisLayout.field(e.getKey(), k), ColumnCodec.numbers(v, 0, v.length));
                } else {
                    String[] v = new String[to - from];
                    for (int i = from; i < to; i++) {
                        int r = order[i];
                        String t = c.texts == null ? null : c.texts[r];
                        // a column with both: numbers become text, as the plugin and Aerospike read them
                        v[i - from] = t != null || c.numbers == null || Double.isNaN(c.numbers[r]) ? t : ColumnCodec.text(c.numbers[r]);
                    }
                    fields.put(RedisLayout.field(e.getKey(), k), ColumnCodec.texts(v, 0, v.length));
                }
            }
        }
        return new Chunks(new ColumnCodec.Meta(version, rows, chunkRows, chunks, types), fields);
    }

    /** What a flush writes: the meta and every chunk field. */
    record Chunks(ColumnCodec.Meta meta, Map<String, byte[]> fields) {

        long bytes() {
            return fields.values().stream().mapToLong(b -> b.length).sum();
        }

        List<String> names() {
            return new ArrayList<>(fields.keySet());
        }
    }
}
