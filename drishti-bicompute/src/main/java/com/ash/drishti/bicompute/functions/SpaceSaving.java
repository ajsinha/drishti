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
package com.ash.drishti.bicompute.functions;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A Space-Saving summary of the most frequent values (Metwally, Agrawal and El Abbadi, 2005; merged as in Agarwal and others, "Mergeable
 * summaries", 2012; PIVOT_ENGINE.md section 9.5): at most {@code capacity} counters, each an over-estimate of its value's count by at most
 * its own error; {@code floor} bounds the count of every value not listed. Capacity 0 is unbounded: every value is counted exactly (the
 * exact form, "exact on request").
 *
 * <p>Counters are kept in value order and ties broken by value, so the summary of the same values merged in the same order is the same.
 */
public final class SpaceSaving {

    static final byte TAG = 'S';

    final int capacity;
    final TreeMap<String, long[]> counters = new TreeMap<>();   // value -> {count, error}
    long floor;
    long n;

    public SpaceSaving(int capacity) {
        this.capacity = Math.max(0, capacity);
    }

    public int capacity() {
        return capacity;
    }

    /** The state as the playground shows it: the values counted. */
    @Override
    public String toString() {
        return "space-saving n=" + n;
    }

    public long n() {
        return n;
    }

    /** The bound of the count of any value not listed. */
    public long floor() {
        return floor;
    }

    /** One value with a weight (rows: 1). */
    public void add(String value, long w) {
        if (value == null || w <= 0) {
            return;
        }
        n += w;
        long[] c = counters.get(value);
        if (c != null) {
            c[0] += w;
            return;
        }
        if (capacity == 0 || counters.size() < capacity) {
            counters.put(value, new long[] {w, 0});
            return;
        }
        String least = least();
        long m = counters.remove(least)[0];
        floor = Math.max(floor, m);
        counters.put(value, new long[] {m + w, m});
    }

    private String least() {
        String at = null;
        long m = Long.MAX_VALUE;
        for (Map.Entry<String, long[]> e : counters.entrySet()) {   // value order: the first of equal counts
            if (e.getValue()[0] < m) {
                m = e.getValue()[0];
                at = e.getKey();
            }
        }
        return at;
    }

    /** Two summaries as one: counts added (a value missing from one counts that one's floor, as count and error), then the largest kept. */
    public SpaceSaving merge(SpaceSaving o) {
        SpaceSaving out = new SpaceSaving(capacity == 0 || o.capacity == 0 ? Math.max(capacity, o.capacity) : Math.min(capacity, o.capacity));
        TreeMap<String, long[]> all = new TreeMap<>();
        for (Map.Entry<String, long[]> e : counters.entrySet()) {
            long[] b = o.counters.get(e.getKey());
            all.put(e.getKey(), b == null ? new long[] {e.getValue()[0] + o.floor, e.getValue()[1] + o.floor}
                    : new long[] {e.getValue()[0] + b[0], e.getValue()[1] + b[1]});
        }
        for (Map.Entry<String, long[]> e : o.counters.entrySet()) {
            if (!counters.containsKey(e.getKey())) {
                all.put(e.getKey(), new long[] {e.getValue()[0] + floor, e.getValue()[1] + floor});
            }
        }
        out.n = n + o.n;
        out.floor = floor + o.floor;
        out.keep(all);
        return out;
    }

    /** The counters kept: all of them (unbounded), or the capacity largest; the largest dropped count raises the floor. */
    void keep(TreeMap<String, long[]> all) {
        if (capacity == 0 || all.size() <= capacity) {
            counters.putAll(all);
            return;
        }
        List<Map.Entry<String, long[]>> ranked = new ArrayList<>(all.entrySet());
        ranked.sort(Comparator.comparing((Map.Entry<String, long[]> e) -> -e.getValue()[0]).thenComparing(Map.Entry::getKey));
        for (int i = 0; i < ranked.size(); i++) {
            if (i < capacity) {
                counters.put(ranked.get(i).getKey(), ranked.get(i).getValue());
            } else {
                floor = Math.max(floor, ranked.get(i).getValue()[0]);
            }
        }
    }

    /** A summary from exact counts (SQL's histogram of a cell), pruned to the capacity. */
    public static SpaceSaving fromCounts(int capacity, List<String> values, List<Long> counts) {
        SpaceSaving s = new SpaceSaving(capacity);
        TreeMap<String, long[]> all = new TreeMap<>();
        for (int i = 0; i < values.size(); i++) {
            long c = counts.get(i) == null ? 0 : counts.get(i);
            if (values.get(i) != null && c > 0) {
                all.merge(values.get(i), new long[] {c, 0}, (a, b) -> new long[] {a[0] + b[0], 0});
                s.n += c;
            }
        }
        s.keep(all);
        return s;
    }

    /** A listed value: its counted figure and the most it may be over. */
    public record Counted(String value, long count, long error) {}

    /** The {@code top} most frequent values, largest first (ties by value). */
    public List<Counted> top(int top) {
        List<Counted> all = new ArrayList<>();
        counters.forEach((v, c) -> all.add(new Counted(v, c[0], c[1])));
        all.sort(Comparator.comparing((Counted c) -> -c.count()).thenComparing(Counted::value));
        return all.subList(0, Math.min(top, all.size()));
    }

    public long bytes() {
        long b = 64;
        for (String v : counters.keySet()) {
            b += 64 + 2L * v.length();
        }
        return b;
    }

    public byte[] encode() {
        List<byte[]> keys = new ArrayList<>();
        int size = 1 + 4 + 8 + 8 + 4;
        for (String v : counters.keySet()) {
            byte[] k = v.getBytes(StandardCharsets.UTF_8);
            keys.add(k);
            size += 4 + k.length + 16;
        }
        ByteBuffer bb = ByteBuffer.allocate(size).put(TAG).putInt(capacity).putLong(floor).putLong(n).putInt(counters.size());
        int i = 0;
        for (long[] c : counters.values()) {
            byte[] k = keys.get(i++);
            bb.putInt(k.length).put(k).putLong(c[0]).putLong(c[1]);
        }
        return bb.array();
    }

    public static SpaceSaving decode(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        if (bb.get() != TAG) {
            throw new IllegalArgumentException("not a top-values summary");
        }
        SpaceSaving s = new SpaceSaving(bb.getInt());
        s.floor = bb.getLong();
        s.n = bb.getLong();
        int c = bb.getInt();
        for (int i = 0; i < c; i++) {
            byte[] k = new byte[bb.getInt()];
            bb.get(k);
            s.counters.put(new String(k, StandardCharsets.UTF_8), new long[] {bb.getLong(), bb.getLong()});
        }
        return s;
    }
}
