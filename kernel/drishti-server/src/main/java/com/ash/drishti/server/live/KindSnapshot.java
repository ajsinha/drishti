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
package com.ash.drishti.server.live;

import com.ash.rachana.live.spi.SnapshotSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A kind's durable records as a live table's snapshot (LIVE_TABLES 1.1, i8 steps 2 and 4), through Drishti's bindings: of tier
 * {@code columns} when a lake keeps the kind as columns (an index of keys and sort values is built from one projection, pages fetch their
 * rows by id), else {@code whole} (its documents read once). At a business date other than the live one the bindings read that day (the
 * lake's time travel); the table is then static. With {@link Peers} (Drishti*net: the members that hold other records of the kind) the
 * snapshot is whole and spans the members: this node's rows, then each member's, merged by key, the higher row version winning a key two
 * members hold (this node's when neither says). The filter the engine pushes down is not used: the engine tests every row read again.
 * A titled snapshot (a FORM's result) gives each row read whole its search title in {@link #TITLE} (the sources' listing), as the search
 * did. A failed read is kept in the source's words ({@link ReadFailures}) for the 503 the reader is told; a good one clears it.
 */
public final class KindSnapshot implements SnapshotSource {

    /** The other members' rows of the kind, as one reader's audience sees them there (their masks applied); never this node's. */
    @FunctionalInterface
    public interface Peers {
        List<Map<String, Object>> rows() throws Exception;
    }

    private final String kind;
    private final KindBindings bindings;
    private final List<String> key;
    private final com.ash.drishti.api.AsOf at;
    private final boolean whole;
    private final Peers peers;
    private final String version;
    private final boolean titled;
    private volatile Map<String, Map<String, Object>> peerRows = Map.of();
    private volatile Map<String, String> titles = Map.of();

    /** The column a FORM result's row keeps its search title in (shown as Title when it says more than the id). */
    public static final String TITLE = "_title";

    public KindSnapshot(String kind, KindBindings bindings, List<String> key) {
        this(kind, bindings, key, com.ash.drishti.api.AsOf.LATEST, false, null, null);
    }

    /**
     * @param at the business date read (live: the current one)
     * @param whole read whole even when a lake keeps columns (a condition reads a field that is no column)
     * @param peers the other members' rows, or null
     * @param version the row version column that decides between two members' rows of one key, or null
     */
    public KindSnapshot(String kind, KindBindings bindings, List<String> key, com.ash.drishti.api.AsOf at, boolean whole, Peers peers,
            String version) {
        this(kind, bindings, key, at, whole, peers, version, false);
    }

    /** As above; {@code titled}: each row read whole carries its search title in {@link #TITLE}. */
    public KindSnapshot(String kind, KindBindings bindings, List<String> key, com.ash.drishti.api.AsOf at, boolean whole, Peers peers,
            String version, boolean titled) {
        this.titled = titled;
        this.kind = kind;
        this.bindings = bindings;
        this.key = List.copyOf(key);
        this.at = at == null ? com.ash.drishti.api.AsOf.LATEST : at;
        this.whole = whole || peers != null;
        this.peers = peers;
        this.version = version;
    }

    private Optional<KindBindings.Store> store() {
        return whole ? Optional.empty() : bindings.store(kind, at);
    }

    @Override
    public Tier tier() {
        return store().isPresent() ? Tier.COLUMNS : Tier.WHOLE;
    }

    @Override
    public String version() {
        String v = store().map(KindBindings.Store::version).orElse(null);
        return v == null || v.isEmpty() ? null : v;
    }

    /** Runs a read of the source, keeping its failure in the source's words, or clearing the kind's last one. */
    private <T> T reading(java.util.concurrent.Callable<T> read) throws Exception {
        ReadFailures f = bindings.failures();
        try {
            T got = read.call();
            if (f != null) {
                f.failed(kind, null);
            }
            return got;
        } catch (Exception | Error e) {
            if (f != null && !(e instanceof com.ash.rachana.data.ProtocolError)) {
                f.failed(kind, e);
            }
            throw e;
        }
    }

    private Map<String, Object> titledRow(Map<String, Object> row, String id) {
        String t = titles.get(id);
        if (t != null && !row.containsKey(TITLE)) {
            row.put(TITLE, t);
        }
        return row;
    }

    @Override
    public void read(Object filter, Consumer<Map<String, Object>> sink) throws Exception {
        reading(() -> {
            readSource(sink);
            return null;
        });
    }

    private void readSource(Consumer<Map<String, Object>> sink) throws Exception {
        if (titled) {
            titles = bindings.titles(kind, at);
        }
        if (peers == null) {
            bindings.documents(kind, at, d -> {
                if (!d.deleted()) {
                    sink.accept(titledRow(KindRows.row(d, key), d.ref().id()));
                }
            });
            return;
        }
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        bindings.documents(kind, at, d -> {
            if (!d.deleted()) {
                merged.put(d.ref().id(), titledRow(KindRows.row(d, key), d.ref().id()));
            }
        });
        Map<String, Map<String, Object>> theirs = new LinkedHashMap<>();
        for (Map<String, Object> r : peers.rows()) {
            String id = String.valueOf(r.get(key.get(0)));
            theirs.putIfAbsent(id, r);
            merged.merge(id, r, this::newer);
        }
        peerRows = theirs;
        merged.values().forEach(sink);
    }

    /** Of two members' rows of one key, the one with the higher row version (the first when neither says). */
    Map<String, Object> newer(Map<String, Object> first, Map<String, Object> second) {
        if (version == null || first.get(version) == null || second.get(version) == null) {
            return first;
        }
        return KindRows.compare(second.get(version), first.get(version)) > 0 ? second : first;
    }

    @Override
    public void scan(List<String> columns, Object filter, Consumer<Object[]> sink) throws Exception {
        reading(() -> {
            scanSource(columns, sink);
            return null;
        });
    }

    private void scanSource(List<String> columns, Consumer<Object[]> sink) throws Exception {
        KindBindings.Store s = store().orElseThrow(() -> new IllegalStateException(kind + " is no longer kept as columns"));
        // the store is asked only for what it keeps as columns (never a document read per record); the rest arrive null
        List<String> kept = new ArrayList<>();
        int[] at = new int[columns.size()];
        for (int i = 0; i < at.length; i++) {
            at[i] = s.column(columns.get(i)) ? kept.size() : -1;
            if (at[i] >= 0) {
                kept.add(columns.get(i));
            }
        }
        int k = key.size() == 1 ? columns.indexOf(key.get(0)) : -1;
        boolean read = s.project(kept, (id, got) -> {
            Object[] values = new Object[at.length];
            for (int i = 0; i < at.length; i++) {
                values[i] = at[i] >= 0 ? got[at[i]] : null;
            }
            if (k >= 0 && values[k] == null) {
                values[k] = id;                               // the key is the record's id when no column holds it
            }
            sink.accept(values);
        });
        if (!read) {
            // the lake cannot answer the day by its columns (a day carried from an earlier one, a source of documents only): its documents,
            // projected, so a day is never empty only because it is not kept as columns
            bindings.documents(kind, this.at, d -> {
                if (!d.deleted()) {
                    Map<String, Object> row = KindRows.row(d, key);
                    Object[] values = new Object[columns.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = row.get(columns.get(i));
                    }
                    sink.accept(values);
                }
            });
        }
    }

    @Override
    public boolean column(String name) {
        return store().map(s -> s.column(name)).orElse(true) || key.contains(name);
    }

    @Override
    public List<Map<String, Object>> fetch(Collection<List<Object>> keys) throws Exception {
        return reading(() -> fetchSource(keys));
    }

    private List<Map<String, Object>> fetchSource(Collection<List<Object>> keys) throws Exception {
        List<String> ids = keys.stream().map(k -> String.valueOf(k.get(0))).toList();
        Optional<KindBindings.Store> s = store();
        Map<String, com.ash.drishti.api.DataNode> got = s.isPresent() ? s.get().fetch(ids) : bindings.fetch(kind, ids, at);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            com.ash.drishti.api.DataNode d = got.get(id);
            Map<String, Object> theirs = peerRows.get(id);
            if (d != null) {
                Map<String, Object> mine = titledRow(KindRows.row(id, d, key, null), id);
                out.add(theirs == null ? mine : newer(mine, theirs));
            } else if (theirs != null) {
                out.add(theirs);
            }
        }
        return out;
    }
}
