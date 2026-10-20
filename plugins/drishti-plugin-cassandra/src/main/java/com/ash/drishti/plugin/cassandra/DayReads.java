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
package com.ash.drishti.plugin.cassandra;

import com.ash.drishti.api.ColumnSet;
import com.datastax.oss.driver.api.core.cql.Row;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Reads of one business day of a kind, partition by partition (each bucket of the day's generation), {@code scan-threads}
 * partitions at a time and paged within each: the ids (type-ahead), the promoted columns (searches, pick lists, derived kinds,
 * impact, reverse lookups) and the documents (derived pages, reverse lookups without promoted columns). Every statement names
 * the whole partition key; nothing scans the table.
 */
final class DayReads {

    private final CassandraStore store;
    private final String keyspace;

    DayReads(CassandraStore store, String keyspace) {
        this.store = store;
        this.keyspace = keyspace;
    }

    /** The day's ids, sorted. */
    List<String> ids(String kind, CassandraLayout.Day day) {
        String cql = CassandraLayout.selectIds(keyspace, kind);
        List<List<String>> parts = perBucket(day);
        store.eachBucket(day.buckets(), b -> store.forEach(cql, r -> parts.get(b).add(r.getString(0)), day.date(), day.generation(), (short) b));
        List<String> out = new ArrayList<>();
        parts.forEach(out::addAll);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * The day's promoted columns as a column set, sorted by id; {@code paths} maps each path to its column and whether the
     * column is a double. The document column is never selected.
     */
    ColumnSet columns(String kind, CassandraLayout.Day day, Map<String, String> columns, Map<String, Boolean> numeric) {
        List<String> paths = new ArrayList<>(columns.keySet());
        List<String> cols = paths.stream().map(columns::get).toList();
        String cql = CassandraLayout.selectColumns(keyspace, kind, cols);
        List<List<Object[]>> parts = perBucket(day);
        store.eachBucket(day.buckets(), b -> store.forEach(cql, r -> parts.get(b).add(row(r, cols.size())), day.date(), day.generation(), (short) b));
        List<Object[]> rows = new ArrayList<>();
        parts.forEach(rows::addAll);
        rows.sort(Comparator.comparing(r -> (String) r[0]));
        String[] ids = new String[rows.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = (String) rows.get(i)[0];
        }
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        for (int c = 0; c < paths.size(); c++) {
            int col = c + 1;
            if (Boolean.TRUE.equals(numeric.get(cols.get(c)))) {
                double[] v = new double[ids.length];
                for (int i = 0; i < ids.length; i++) {
                    Object x = rows.get(i)[col];
                    v[i] = x == null ? Double.NaN : ((Number) x).doubleValue();
                }
                nums.put(paths.get(c), v);
            } else {
                String[] v = new String[ids.length];
                Map<String, String> pool = new HashMap<>();                  // books, desks, currencies repeat: one string each
                for (int i = 0; i < ids.length; i++) {
                    Object x = rows.get(i)[col];
                    String s = x == null ? null : String.valueOf(x);
                    v[i] = s == null || pool.size() > 200_000 ? s : pool.computeIfAbsent(s, k -> k);
                }
                texts.put(paths.get(c), v);
            }
        }
        return new ColumnSet(ids, nums, texts, day.date());
    }

    private static Object[] row(Row r, int columns) {
        Object[] out = new Object[columns + 1];
        out[0] = r.getString(0);
        for (int i = 0; i < columns; i++) {
            out[i + 1] = r.getObject(i + 1);
        }
        return out;
    }

    /**
     * The day's documents, at most {@code limit} (0: all), each given to {@code each} as id and JSON text; calls are serialised,
     * so {@code each} need not be thread-safe. Returns how many were given.
     */
    long documents(String kind, CassandraLayout.Day day, long limit, BiConsumer<String, String> each) {
        String cql = CassandraLayout.selectDocs(keyspace, kind);
        AtomicLong n = new AtomicLong();
        // a lock, not synchronized: the consumer may parse or block, and the partitions are read on virtual threads
        java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        store.eachBucket(day.buckets(), b -> store.forEach(cql, r -> {
            if (limit > 0 && n.get() >= limit) {
                return;
            }
            lock.lock();
            try {
                if (limit <= 0 || n.get() < limit) {
                    n.incrementAndGet();
                    each.accept(r.getString(0), r.getString(1));
                }
            } finally {
                lock.unlock();
            }
        }, day.date(), day.generation(), (short) b));
        return n.get();
    }

    private static <T> List<List<T>> perBucket(CassandraLayout.Day day) {
        List<List<T>> parts = new ArrayList<>(day.buckets());
        for (int i = 0; i < day.buckets(); i++) {
            parts.add(new ArrayList<>());
        }
        return parts;
    }

    /** The newest day of a kind on or before {@code asked} (its newest when null), within {@code lookbackDays}. */
    static CassandraLayout.Day floor(java.util.NavigableMap<LocalDate, CassandraLayout.Day> days, LocalDate asked, int lookbackDays) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        Map.Entry<LocalDate, CassandraLayout.Day> e = asked == null ? days.lastEntry() : days.floorEntry(asked);
        if (e == null || asked != null && e.getKey().isBefore(asked.minusDays(lookbackDays))) {
            return null;
        }
        return e.getValue();
    }
}
