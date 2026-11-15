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
package com.ash.drishti.live;

import com.ash.drishti.api.live.LivePosition;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The live state of one live connector (docs/architecture/LIVE_CONNECTORS_DESIGN.md sections 4 and 7.6): the newest row per key of each
 * kind it serves, deletes remembered as tombstones, idempotent by key and order. It is shared and unfiltered, like a lake table: nothing
 * of any person is in it, and every reader's rules apply where rows leave the server.
 *
 * <p><b>Memory stays bounded.</b> Each key costs its entry (order, position, times: a few hundred bytes); the documents are held in a cache
 * limited by size ({@code cache-mb}) and, past it, in the connector's disk cache when it has one, else read back from the transport by
 * position ({@link #document}). Rows older than the kind's {@code retention.ttl}, and the oldest past {@code retention.max-rows}, leave
 * ({@link #expire}).
 *
 * <p><b>Durable</b> (a transport that cannot replay, a JMS or RabbitMQ queue: LIVE_CONNECTORS_DESIGN.md sections 7.6 and 10): every row
 * and every delete is written to the connector's disk store, with the store's durability, before the message is acknowledged, and the
 * state is read back from it at start, so a restart loses nothing the queue will not send again. A write that fails is an
 * {@link java.io.UncheckedIOException}: the message is not acknowledged and the broker delivers it again.
 */
public final class LiveState {

    /** What {@link #apply} did (section 4): the counts of a feed. */
    public enum Outcome { APPLIED, DUPLICATE, LATE }

    /**
     * One key's row as the state holds it (its document apart).
     *
     * @param kind      the kind
     * @param key       the key
     * @param order     its order values
     * @param position  where its message was in the transport
     * @param changedAt its event time
     * @param changedBy who changed it, or null
     * @param asOf      its business date, or null
     * @param deleted   a tombstone: the row is gone, remembered until it expires so an older upsert cannot bring it back
     * @param arrived   when it was applied
     * @param seq       the state's own sequence of applies (a generation: newer is larger)
     */
    public record Entry(String kind, String key, List<Object> order, LivePosition position, Instant changedAt, String changedBy, String asOf,
            boolean deleted, Instant arrived, long seq) {}

    private final Map<String, Map<String, Entry>> kinds = new ConcurrentHashMap<>();
    private final Cache<String, byte[]> docs;
    private final com.ash.drishti.diskcache.DiskCache disk;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicReference<Function<Entry, Optional<byte[]>>> reload = new AtomicReference<>(e -> Optional.empty());
    private final boolean durable;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * @param cacheBytes the documents held in memory, by size
     * @param disk       the connector's disk cache, or null
     */
    public LiveState(long cacheBytes, com.ash.drishti.diskcache.DiskCache disk) {
        this(cacheBytes, disk, false);
    }

    /**
     * @param durable true: {@code disk} is the state's durable store (rows and deletes written before a message is acknowledged, read back
     *                at start); it must then be given
     */
    public LiveState(long cacheBytes, com.ash.drishti.diskcache.DiskCache disk, boolean durable) {
        if (durable && disk == null) {
            throw new IllegalArgumentException("a durable live state needs its disk store");
        }
        this.disk = disk;
        this.durable = durable;
        // eviction on the writing thread: the bound holds at once, and no pool thread is borrowed
        this.docs = Caffeine.newBuilder().executor(Runnable::run).maximumWeight(Math.max(1, cacheBytes)).weigher((String k, byte[] v) -> v.length + 64).build();
        if (durable) {
            load();
        }
    }

    /** True when the state is kept on disk across restarts. */
    public boolean durable() {
        return durable;
    }

    /**
     * Reads the durable store back: every row and delete (entries under {@code e/}, documents under {@code d/}). A store an earlier
     * release's queue connector left ({@code <kind>/<id>} to the document) is read as rows of no order, kept from then on in this form.
     */
    private void load() {
        List<String[]> legacy = new ArrayList<>();
        disk.forEach((k, v) -> {
            if (k.startsWith("e/")) {
                try {
                    Entry e = entryOf(v);
                    kinds.computeIfAbsent(e.kind(), x -> new ConcurrentHashMap<>()).put(e.key(), e);
                    seq.accumulateAndGet(e.seq(), Math::max);
                } catch (java.io.IOException | RuntimeException ignored) {
                    // an entry that cannot be read: its row is not served (the next message of its key brings it back)
                }
            } else if (!k.startsWith("d/") && k.contains("/")) {
                legacy.add(k.split("/", 2));
            }
        });
        for (String[] kv : legacy) {
            byte[] doc = disk.get(kv[0] + "/" + kv[1]);
            if (doc == null) {
                continue;
            }
            Entry e = new Entry(kv[0], kv[1], List.of(), null, null, null, null, false, Instant.now(), seq.incrementAndGet());
            try {
                disk.store("d/" + id(kv[0], kv[1]), doc);
                disk.store("e/" + id(kv[0], kv[1]), entryBytes(e));
                disk.remove(kv[0] + "/" + kv[1]);
            } catch (java.io.IOException x) {
                throw new java.io.UncheckedIOException("the live state's store cannot be moved to its new form: " + x.getMessage(), x);
            }
            kinds.computeIfAbsent(e.kind(), x -> new ConcurrentHashMap<>()).put(e.key(), e);
        }
    }

    private static byte[] entryBytes(Entry e) throws java.io.IOException {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("kind", e.kind());
        m.put("key", e.key());
        m.put("order", e.order());
        if (e.position() != null) {
            m.put("stream", e.position().stream());
            m.put("partition", e.position().partition());
            m.put("offset", e.position().offset());
        }
        m.put("changedAt", e.changedAt() == null ? null : e.changedAt().toString());
        m.put("changedBy", e.changedBy());
        m.put("asOf", e.asOf());
        m.put("deleted", e.deleted());
        m.put("arrived", e.arrived().toString());
        m.put("seq", e.seq());
        return JSON.writeValueAsBytes(m);
    }

    private static Entry entryOf(byte[] bytes) throws java.io.IOException {
        com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(bytes);
        List<Object> order = new ArrayList<>();
        n.path("order").forEach(x -> order.add(x.isNull() ? null : x.isNumber() ? x.numberValue() : x.asText()));
        LivePosition p = n.hasNonNull("stream") ? new LivePosition(n.get("stream").asText(), n.path("partition").asInt(), n.path("offset").asLong()) : null;
        return new Entry(n.get("kind").asText(), n.get("key").asText(), order, p, n.hasNonNull("changedAt") ? Instant.parse(n.get("changedAt").asText()) : null,
                n.hasNonNull("changedBy") ? n.get("changedBy").asText() : null, n.hasNonNull("asOf") ? n.get("asOf").asText() : null,
                n.path("deleted").asBoolean(false), Instant.parse(n.get("arrived").asText()), n.path("seq").asLong());
    }

    /** How a document no longer in memory or on disk is read again (the feed reads its message back from the transport). */
    public void reload(Function<Entry, Optional<byte[]>> how) {
        reload.set(how == null ? e -> Optional.empty() : how);
    }

    /**
     * Applies one record (section 4): newer than the row held, it replaces it; equal, it is a duplicate; older, it is late. A record with no
     * order values is ordered by arrival: in one partition by offset, across partitions the later arrival wins. A delete is kept as a
     * tombstone with the order of the row it deleted when it carries none of its own.
     *
     * @param json the row as JSON (null for a delete)
     */
    public Outcome apply(LiveRecord r, byte[] json) {
        Map<String, Entry> rows = kinds.computeIfAbsent(r.kind(), k -> new ConcurrentHashMap<>());
        Outcome[] out = new Outcome[1];
        rows.compute(r.key(), (k, cur) -> {
            if (cur != null) {
                int c;
                if (r.order().isEmpty() || cur.order().isEmpty()) {
                    c = r.position() != null && r.position().samePartition(cur.position())
                            ? Long.compare(r.position().offset(), cur.position().offset()) : 1;
                } else {
                    c = LiveRecord.compare(r.order(), r.position(), cur.order(), cur.position());
                }
                if (c <= 0) {
                    out[0] = c == 0 ? Outcome.DUPLICATE : Outcome.LATE;
                    return cur;
                }
            }
            boolean delete = r.op() == LiveRecord.Op.DELETE;
            List<Object> order = delete && r.order().isEmpty() && cur != null ? cur.order() : r.order();
            Entry next = new Entry(r.kind(), k, order, r.position(), r.changedAt(), r.changedBy(), r.asOf(), delete, Instant.now(), seq.incrementAndGet());
            if (durable) {
                String id = id(r.kind(), k);
                try {                                                  // kept on disk before the message is acknowledged
                    if (json == null) {
                        disk.remove("d/" + id);
                    } else {
                        disk.store("d/" + id, json);
                    }
                    disk.store("e/" + id, entryBytes(next));
                } catch (java.io.IOException x) {
                    throw new java.io.UncheckedIOException("the live state's store could not keep " + id + ": " + x.getMessage(), x);
                }
            }
            out[0] = Outcome.APPLIED;
            return next;
        });
        if (out[0] == Outcome.APPLIED) {
            String id = id(r.kind(), r.key());
            if (json == null) {
                docs.invalidate(id);
                if (disk != null && !durable) {
                    disk.delete("d/" + id);
                }
            } else {
                docs.put(id, json);
                if (disk != null && !durable) {
                    disk.put("d/" + id, json);
                }
            }
        }
        return out[0];
    }

    /** The row of a key, a tombstone included; empty when the state has never seen it (or it expired). */
    public Optional<Entry> entry(String kind, String key) {
        Map<String, Entry> rows = kinds.get(kind);
        return rows == null ? Optional.empty() : Optional.ofNullable(rows.get(key));
    }

    /** The document of a live (not deleted) row: from memory, else the disk cache, else read back; empty when it cannot be had. */
    public Optional<byte[]> document(String kind, String key) {
        Optional<Entry> e = entry(kind, key).filter(x -> !x.deleted());
        if (e.isEmpty()) {
            return Optional.empty();
        }
        String id = id(kind, key);
        byte[] hit = docs.getIfPresent(id);
        if (hit == null && disk != null) {
            hit = disk.get("d/" + id);
        }
        if (hit == null) {
            hit = reload.get().apply(e.get()).orElse(null);
        }
        if (hit != null && entry(kind, key).map(x -> x.seq() == e.get().seq()).orElse(false)) {
            docs.put(id, hit);                       // only while the row is still the one read: a newer apply is never overwritten
        }
        return Optional.ofNullable(hit);
    }

    /** Every row of a kind (tombstones included), in no order. */
    public Stream<Entry> entries(String kind) {
        Map<String, Entry> rows = kinds.get(kind);
        return rows == null ? Stream.empty() : rows.values().stream();
    }

    /** The kinds the state holds rows of. */
    public java.util.Set<String> kinds() {
        return java.util.Set.copyOf(kinds.keySet());
    }

    /** The rows held (tombstones included). */
    public long size() {
        return kinds.values().stream().mapToLong(Map::size).sum();
    }

    /** The bytes of the documents held in memory. */
    public long memoryBytes() {
        return docs.asMap().values().stream().mapToLong(b -> b.length).sum();
    }

    /**
     * Removes the rows of {@code kind} older than {@code ttl} (by when they were applied) and, past {@code maxRows}, the oldest; returns
     * the rows removed that were not tombstones (an unabsorbed row removed is counted by the feed).
     */
    public int expire(String kind, Instant now, Duration ttl, long maxRows) {
        Map<String, Entry> rows = kinds.get(kind);
        if (rows == null) {
            return 0;
        }
        int removed = 0;
        Instant cut = now.minus(ttl);
        for (Entry e : List.copyOf(rows.values())) {
            if (e.arrived().isBefore(cut) && rows.remove(e.key(), e)) {
                removed += forget(e);
            }
        }
        if (rows.size() > maxRows) {
            List<Entry> oldest = new ArrayList<>(rows.values());
            oldest.sort(Comparator.comparingLong(Entry::seq));
            for (int i = 0; i < oldest.size() - maxRows; i++) {
                if (rows.remove(oldest.get(i).key(), oldest.get(i))) {
                    removed += forget(oldest.get(i));
                }
            }
        }
        return removed;
    }

    private int forget(Entry e) {
        String id = id(e.kind(), e.key());
        docs.invalidate(id);
        if (disk != null) {
            disk.delete("d/" + id);
            if (durable) {
                disk.delete("e/" + id);
            }
        }
        return e.deleted() ? 0 : 1;
    }

    /**
     * What a reconcile did (section 6): rows the load absorbed (dropped: the lake's row answers), rows carried (newer than the load),
     * orphans (dropped: changed before the cut-off and not in the load: the source's own extract says they do not exist), deletes carried
     * (the row stays hidden), and the orphans themselves.
     */
    public record Reconcile(String kind, java.time.LocalDate loadDate, LiveRules.Absorb rule, Instant cutoff, int absorbed, int carried, int orphans,
            int deletesCarried, List<Entry> orphanRows, Map<String, Long> positions) {}

    /**
     * Reconciles the rows of {@code kind} that belong to {@code loadDate} or before (rows of a later live date are not the load's) against a
     * load of that date, by the kind's {@code absorb:} rule:
     * <ul>
     *   <li>{@code by-order}: the lake's row as new or newer by {@code live.order} absorbs the live row; older, the live row is carried. A
     *       live delete stays (the row hidden) while the lake's row is older than it, and goes when the lake's is as new or newer.</li>
     *   <li>{@code by-cutoff}: rows changed on or before {@code cutoff} are absorbed, later ones carried.</li>
     *   <li>{@code reset}: every row goes.</li>
     * </ul>
     * In every rule a row not in the load (and not a delete) changed on or before the cut-off is an orphan, dropped and reported; changed
     * after it, carried (booked after the extract). A delete whose key is not in the load is absorbed. The swap is per row; reads are never
     * blocked.
     *
     * @param lake the lake's order values of a key in the load, empty when the load does not hold it
     */
    public Reconcile reconcile(String kind, java.time.LocalDate loadDate, LiveRules.Absorb rule, Instant cutoff,
            Function<String, Optional<List<Object>>> lake) {
        Map<String, Entry> rows = kinds.get(kind);
        int absorbed = 0;
        int carried = 0;
        int orphans = 0;
        int deletesCarried = 0;
        List<Entry> orphanRows = new ArrayList<>();
        Map<String, Long> positions = new java.util.TreeMap<>();
        if (rows == null) {
            return new Reconcile(kind, loadDate, rule, cutoff, 0, 0, 0, 0, List.of(), Map.of());
        }
        for (Entry e : List.copyOf(rows.values())) {
            if (e.asOf() != null && loadDate != null && e.asOf().length() >= 10 && java.time.LocalDate.parse(e.asOf().substring(0, 10)).isAfter(loadDate)) {
                continue;                                              // a row of a later date: not this load's
            }
            if (e.position() != null && e.position().offset() >= 0) {
                positions.merge(e.position().stream() + "/" + e.position().partition(), e.position().offset(), Math::max);
            }
            Optional<List<Object>> l = lake.apply(e.key());
            boolean beforeCutoff = e.changedAt() == null || cutoff == null || !e.changedAt().isAfter(cutoff);
            boolean drop;
            if (l.isEmpty()) {
                if (e.deleted()) {
                    drop = true;                                       // the load does not hold it either: the delete is absorbed
                } else if (rule == LiveRules.Absorb.RESET || beforeCutoff) {
                    orphans++;
                    orphanRows.add(e);
                    if (rows.remove(e.key(), e)) {
                        forget(e);
                    }
                    continue;
                } else {
                    drop = false;
                }
            } else {
                drop = switch (rule) {
                    case RESET -> true;
                    case BY_CUTOFF -> beforeCutoff;
                    case BY_ORDER -> !e.order().isEmpty() && LiveRecord.compare(l.get(), null, e.order(), null) >= 0;
                };
            }
            if (drop) {
                if (rows.remove(e.key(), e)) {
                    forget(e);
                    absorbed++;
                }
            } else if (e.deleted()) {
                deletesCarried++;
            } else {
                carried++;
            }
        }
        return new Reconcile(kind, loadDate, rule, cutoff, absorbed, carried, orphans, deletesCarried, List.copyOf(orphanRows), positions);
    }

    /**
     * Empties the documents held in memory and, unless the state is durable, on disk (Admin's purge); the rows stay and are read back
     * when asked. A durable state's disk is its only copy (a queue will not send it again): it is never cleared by a purge.
     */
    public void purge() {
        docs.invalidateAll();
        if (disk != null && !durable) {
            disk.clear();
        }
    }

    /** The disk cache, or null. */
    public com.ash.drishti.diskcache.DiskCache disk() {
        return disk;
    }

    public void close() {
        if (disk != null) {
            disk.close();
        }
    }

    private static String id(String kind, String key) {
        return kind + "/" + key;
    }
}
