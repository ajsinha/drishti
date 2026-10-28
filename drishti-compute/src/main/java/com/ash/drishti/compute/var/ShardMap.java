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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One business date's shard map (VAR_SHARDING.md section 3): the date's trades cut into contiguous ranges of the shard key (legal entity,
 * then desk), in the hot file's own order, each near the target size, a key (a desk) never split. A pure function of the date's row
 * counts per key, so every member that reads the same data version computes the same map; its {@link #digest()} is sent with every
 * partial sum and skeleton, and an answer built from another map is refused.
 *
 * <p>Shard {@code i} holds every key from its first one up to (excluded) the next shard's first: the ranges cover the whole key space, so
 * a row whose key was not counted (it cannot be, at one data version) still has exactly one shard.
 *
 * @param date the business date
 * @param version the data version of the date's trades the counts were read at
 * @param key the shard key's columns, a prefix of the hot file's order ({@code LEGAL_ENTITY}, {@code DESK})
 * @param shards the shards in key order, indexed from 0
 * @param targetBytes the target size of a shard (its float32 rows)
 * @param note why the date was not cut, or null (one shard covers the whole date)
 * @param digest SHA-256 (hex) of the date, the version, the key and every shard's range and rows
 */
public record ShardMap(LocalDate date, String version, List<String> key, List<Shard> shards, long targetBytes, String note, String digest) {

    /** The key columns a shard key may use, in the hot file's order: a prefix of these (the legal entity, or it and the desk). */
    public static final List<String> ORDER = List.of("LEGAL_ENTITY", "DESK");

    /**
     * One shard.
     *
     * @param index its place in the map
     * @param first the first key counted in it (shard 0 also holds every key before it; empty for a shard of the whole date)
     * @param last the last key counted in it (empty for a shard of the whole date)
     * @param rows the rows counted in it
     * @param bytes its estimated float32 rows ({@code rows} × width × 4)
     */
    public record Shard(int index, List<String> first, List<String> last, long rows, long bytes) {
        public Shard {
            first = clean(first);
            last = clean(last);
        }

        /** A key's values, a missing one as empty (the hot file sorts it so). */
        private static List<String> clean(List<String> key) {
            List<String> out = new ArrayList<>(key.size());
            for (String v : key) {
                out.add(v == null ? "" : v);
            }
            return java.util.Collections.unmodifiableList(out);
        }

        /** {@code S<index>}. */
        public String id() {
            return "S" + index;
        }

        /** The range in words: {@code LE-A/DESK-X..LE-B/DESK-Y}; {@code all} for a shard of the whole date. */
        public String range() {
            return first.isEmpty() && last.isEmpty() ? "all" : text(first) + ".." + text(last);
        }

        /**
         * The key its owners are chosen by (rendezvous hashing): its range, not its index or the date, so the same desks keep the same owners
         * from one day to the next while the cut does not move.
         */
        public String ownershipKey() {
            return range();
        }

        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shard", id());
            m.put("index", index);
            m.put("range", range());
            m.put("first", first);
            m.put("last", last);
            m.put("rows", rows);
            m.put("bytes", bytes);
            return m;
        }
    }

    public ShardMap {
        key = List.copyOf(key);
        shards = List.copyOf(shards);
    }

    /** Checks a configured shard key: a non-empty prefix of {@link #ORDER} (else the ranges would not be runs of the hot file). */
    public static List<String> checkKey(List<String> key) {
        List<String> k = key == null || key.isEmpty() ? ORDER : key.stream().map(c -> c.strip().toUpperCase(java.util.Locale.ROOT)).toList();
        if (k.size() > ORDER.size() || !ORDER.subList(0, k.size()).equals(k)) {
            throw new IllegalArgumentException("the shard key is " + k + ": it must be [LEGAL_ENTITY] or [LEGAL_ENTITY, DESK], the hot file's own "
                    + "order (a desk is never split)");
        }
        return k;
    }

    /** Compares two keys column by column, a missing value as empty (the hot file's sort). */
    public static int compare(List<String> a, List<String> b) {
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            String x = i < a.size() && a.get(i) != null ? a.get(i) : "";
            String y = i < b.size() && b.get(i) != null ? b.get(i) : "";
            int c = x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** The key comparator ({@link #compare}). */
    public static final Comparator<List<String>> KEYS = ShardMap::compare;

    /** A sorted map for the counts {@link #cut} takes. */
    public static SortedMap<List<String>, Long> counts() {
        return new TreeMap<>(KEYS);
    }

    /**
     * Cuts a date into shards: the keys in order, a shard closed when the next key would take it over {@code targetBytes} (a shard holds at
     * least one key, however large), each key's bytes its rows × {@code width} × 4.
     *
     * @param rowsByKey rows per key value (each list as long as {@code key}), sorted by {@link #compare}
     */
    public static ShardMap cut(LocalDate date, String version, List<String> key, SortedMap<List<String>, Long> rowsByKey, int width,
            long targetBytes) {
        List<String> k = checkKey(key);
        if (rowsByKey.isEmpty()) {
            return whole(date, version, k, targetBytes, "the date has no rows to cut");
        }
        long per = (long) Math.max(1, width) * Float.BYTES;
        List<Shard> out = new ArrayList<>();
        List<String> first = null;
        List<String> last = null;
        long rows = 0;
        for (Map.Entry<List<String>, Long> e : rowsByKey.entrySet()) {
            long r = Math.max(0, e.getValue());
            if (first != null && (rows + r) * per > targetBytes) {
                out.add(new Shard(out.size(), first, last, rows, rows * per));
                first = null;
                rows = 0;
            }
            if (first == null) {
                first = e.getKey();
            }
            last = e.getKey();
            rows += r;
        }
        out.add(new Shard(out.size(), first, last, rows, rows * per));
        return new ShardMap(date, version, k, out, targetBytes, null, digest(date, version, k, targetBytes, out));
    }

    /** A map of one shard holding the whole date (the date could not be cut: {@code note} says why). */
    public static ShardMap whole(LocalDate date, String version, List<String> key, long targetBytes, String note) {
        List<Shard> one = List.of(new Shard(0, List.of(), List.of(), 0, 0));
        return new ShardMap(date, version, checkKey(key), one, targetBytes, note, digest(date, version, checkKey(key), targetBytes, one));
    }

    /** The shard a key falls in: the last shard whose first key is not after it (shard 0 for anything before every first key). */
    public int shardOf(List<String> keyValues) {
        int lo = 0;
        int hi = shards.size() - 1;
        int at = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Shard s = shards.get(mid);
            if (s.first().isEmpty() || compare(s.first(), keyValues) <= 0) {
                at = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return at;
    }

    public Shard shard(int index) {
        return shards.get(index);
    }

    public int size() {
        return shards.size();
    }

    /** The map in words and numbers (the API's {@code GET /api/v1/var/shards}). */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessDate", date.toString());
        m.put("dataVersion", version);
        m.put("key", key);
        m.put("targetBytes", targetBytes);
        m.put("digest", digest);
        if (note != null) {
            m.put("note", note);
        }
        m.put("shards", shards.stream().map(Shard::describe).toList());
        return m;
    }

    private static String text(List<String> key) {
        List<String> out = new ArrayList<>(key.size());
        for (String v : key) {
            out.add(v == null ? "" : v);
        }
        return String.join("/", out);
    }

    private static String digest(LocalDate date, String version, List<String> key, long target, List<Shard> shards) {
        StringBuilder b = new StringBuilder("shards|").append(date).append('|').append(version).append('|').append(key).append('|').append(target);
        for (Shard s : shards) {
            b.append('|').append(s.index()).append(':').append(s.first()).append("..").append(s.last()).append(':').append(s.rows());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
