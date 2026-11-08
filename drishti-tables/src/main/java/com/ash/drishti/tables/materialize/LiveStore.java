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
package com.ash.drishti.tables.materialize;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.preview.Preview;
import com.ash.drishti.tables.preview.RowEvaluator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The layer's live materialisation (docs/architecture/BI_DESIGN.md 3.4, Q14): a live table's rows in memory by key, maintained from the entities
 * its source pushes. Each entity's document becomes its row through the table's own definition (paths, filter, calculated columns), as a
 * preview reads it; a row whose values did not change keeps its sequence; a deleted entity's row goes; retention keeps at most
 * {@code rows} rows (the oldest updated go first) and none older than {@code age}. Every change takes the next {@code update_seq} and its
 * {@code updated_at}, the built-ins a reader resumes from. One lock per table, never {@code synchronized}.
 */
public final class LiveStore implements Materialization.Live {

    /** One entity's change: its document, or null when the entity was deleted. */
    public record Change(String id, String businessDate, DataNode doc) {}

    private record Held(DataNode row, long seq, Instant at) {}

    private static final class State {
        final ReentrantLock lock = new ReentrantLock();
        final Map<String, Held> byKey = new LinkedHashMap<>();
        final Map<String, String> keyOfEntity = new LinkedHashMap<>();
        long seq;
    }

    private final Clock clock;
    private final int defaultRows;
    private final RowEvaluator eval;
    private final Map<String, State> tables = new ConcurrentHashMap<>();

    public LiveStore(Clock clock, int defaultRows, RowEvaluator eval) {
        this.clock = clock;
        this.defaultRows = defaultRows;
        this.eval = eval;
    }

    /** Applies the entities' changes to the live table {@code t}; returns how many rows changed. */
    public int apply(TableDef t, List<Change> changes) {
        if (!t.materialize().live()) {
            throw new IllegalArgumentException(t.name() + " is not live (materialize: " + t.materialize().mode() + ")");
        }
        State s = tables.computeIfAbsent(t.name(), k -> new State());
        Materialization.LiveBounds bounds = Materialization.LiveBounds.of(t, defaultRows);
        int changed = 0;
        s.lock.lock();
        try {
            Instant now = clock.instant();
            for (Change c : changes) {
                String old = s.keyOfEntity.remove(c.id());
                if (c.doc() == null) {
                    if (old != null && s.byKey.remove(old) != null) {
                        s.seq++;
                        changed++;
                    }
                    continue;
                }
                List<DataNode> rows = Preview.objects(Preview.rows(t, List.of(new Preview.Doc(c.id(), c.businessDate(), c.doc())), 1, eval));
                if (rows.isEmpty()) {                                       // the filter leaves it out now
                    if (old != null && s.byKey.remove(old) != null) {
                        s.seq++;
                        changed++;
                    }
                    continue;
                }
                DataNode row = rows.get(0);
                String key = key(t, row);
                if (old != null && !old.equals(key)) {
                    s.byKey.remove(old);
                }
                s.keyOfEntity.put(c.id(), key);
                Held was = s.byKey.get(key);
                if (was != null && was.row().unwrap().equals(row.unwrap())) {
                    continue;                                               // the same values: no new sequence
                }
                s.seq++;
                s.byKey.put(key, new Held(row, s.seq, now));
                changed++;
            }
            evict(s, bounds, now);
        } finally {
            s.lock.unlock();
        }
        return changed;
    }

    private static void evict(State s, Materialization.LiveBounds b, Instant now) {
        if (b.age() != null) {
            Instant oldest = now.minus(b.age());
            s.byKey.values().removeIf(h -> h.at().isBefore(oldest));
        }
        if (s.byKey.size() > b.rows()) {
            List<Map.Entry<String, Held>> byAge = new ArrayList<>(s.byKey.entrySet());
            byAge.sort(Comparator.comparingLong(e -> e.getValue().seq()));
            for (int i = 0; i < byAge.size() - b.rows(); i++) {
                s.byKey.remove(byAge.get(i).getKey());
            }
        }
        s.keyOfEntity.values().removeIf(k -> !s.byKey.containsKey(k));
    }

    private static String key(TableDef t, DataNode row) {
        StringBuilder sb = new StringBuilder();
        for (String k : t.keys()) {
            sb.append(row.get(k).unwrap()).append('\u0000');
        }
        return sb.toString();
    }

    /** The table's rows now, each with its {@code update_seq} and {@code updated_at}, in the order of their sequence. */
    public List<DataNode> current(TableDef t) {
        State s = tables.get(t.name());
        if (s == null) {
            return List.of();
        }
        s.lock.lock();
        try {
            List<Held> held = new ArrayList<>(s.byKey.values());
            held.sort(Comparator.comparingLong(Held::seq));
            List<DataNode> out = new ArrayList<>(held.size());
            for (Held h : held) {
                Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) h.row()).fields());
                fields.put("update_seq", DataNode.of(h.seq()));
                fields.put("updated_at", DataNode.of(h.at().toString()));
                out.add(new DataNode.Obj(fields));
            }
            return out;
        } finally {
            s.lock.unlock();
        }
    }

    @Override
    public long rows(TableDef t) {
        State s = tables.get(t.name());
        return s == null ? 0 : s.byKey.size();
    }

    @Override
    public long sequence(TableDef t) {
        State s = tables.get(t.name());
        return s == null ? 0 : s.seq;
    }
}
