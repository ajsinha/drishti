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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A kind's durable records as a live table's snapshot (LIVE_TABLES 1.1, i8 step 2), through Drishti's bindings: of tier {@code columns}
 * when a lake keeps the kind as columns (an index of keys and sort values is built from one projection, pages fetch their rows by id),
 * else {@code whole} (its documents read once). The filter the engine pushes down is not used: the engine tests every row read again.
 */
public final class KindSnapshot implements SnapshotSource {

    private final String kind;
    private final KindBindings bindings;
    private final List<String> key;

    public KindSnapshot(String kind, KindBindings bindings, List<String> key) {
        this.kind = kind;
        this.bindings = bindings;
        this.key = List.copyOf(key);
    }

    private Optional<KindBindings.Store> store() {
        return bindings.store(kind);
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

    @Override
    public void read(Object filter, Consumer<Map<String, Object>> sink) throws Exception {
        bindings.documents(kind, d -> {
            if (!d.deleted()) {
                sink.accept(KindRows.row(d, key));
            }
        });
    }

    @Override
    public void scan(List<String> columns, Object filter, Consumer<Object[]> sink) throws Exception {
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
        s.project(kept, (id, got) -> {
            Object[] values = new Object[at.length];
            for (int i = 0; i < at.length; i++) {
                values[i] = at[i] >= 0 ? got[at[i]] : null;
            }
            if (k >= 0 && values[k] == null) {
                values[k] = id;                               // the key is the record's id when no column holds it
            }
            sink.accept(values);
        });
    }

    @Override
    public boolean column(String name) {
        return store().map(s -> s.column(name)).orElse(true) || key.contains(name);
    }

    @Override
    public List<Map<String, Object>> fetch(Collection<List<Object>> keys) throws Exception {
        List<String> ids = keys.stream().map(k -> String.valueOf(k.get(0))).toList();
        Optional<KindBindings.Store> s = store();
        Map<String, com.ash.drishti.api.DataNode> got = s.isPresent() ? s.get().fetch(ids) : bindings.fetch(kind, ids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            com.ash.drishti.api.DataNode d = got.get(id);
            if (d != null) {
                out.add(KindRows.row(id, d, key, null));
            }
        }
        return out;
    }
}
