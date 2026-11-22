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

import com.ash.engine.Answer;
import com.ash.engine.Snapshot;
import com.ash.engine.Table;
import com.ash.engine.Tables;
import com.ash.engine.host.EngineFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where the host sends an answer's tables besides the caller (ENGINE_INTEGRATION_DESIGN 5.3; EN-12: the engine has no sink). {@code live}: the
 * output's rows kept by key, each new answer compared with the last and the differences given a version (a live table: grids update in place
 * by key and version). {@code store}: the rows written to a lake table through the {@link OutputStore} port.
 */
public final class Outputs {

    /** Writes a stored output's rows (the server: Drishti Lake's loader). */
    @FunctionalInterface
    public interface OutputStore {
        /** Writes {@code table} under {@code store} for {@code snapshot}; returns the version the write made. */
        long store(String engine, String output, EngineFile.Store store, Snapshot snapshot, Table table, Answer answer) throws Exception;
    }

    /** One change of a live output: the version, the key, and the row (null when the key left). */
    public record Change(long version, List<String> key, Map<String, Object> row) {}

    /** A live output's rows by key, with the changes since a version. */
    static final class Live {
        private final Map<List<String>, Map<String, Object>> rows = new LinkedHashMap<>();
        private final List<Change> changes = new ArrayList<>();
        private final AtomicLong version = new AtomicLong();
        private static final int KEEP = 10_000;

        synchronized long apply(Table t) {
            Map<List<String>, Map<String, Object>> next = new LinkedHashMap<>();
            List<Object[]> all = Tables.rows(t);
            List<String> key = t.schema().key();
            for (Object[] r : all) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < r.length; c++) {
                    Object v = r[c];
                    row.put(t.schema().columns().get(c).name(), v instanceof double[] d ? Arrays.stream(d).boxed().toList() : v);
                }
                List<String> k = key.isEmpty() ? List.of(Integer.toString(next.size())) : key.stream().map(x -> String.valueOf(row.get(x))).toList();
                next.put(k, row);
            }
            long v = version.get();
            for (Map.Entry<List<String>, Map<String, Object>> e : next.entrySet()) {
                if (!e.getValue().equals(rows.get(e.getKey()))) {
                    changes.add(new Change(++v, e.getKey(), e.getValue()));
                }
            }
            for (List<String> k : rows.keySet()) {
                if (!next.containsKey(k)) {
                    changes.add(new Change(++v, k, null));
                }
            }
            version.set(v);
            rows.clear();
            rows.putAll(next);
            if (changes.size() > KEEP) {
                changes.subList(0, changes.size() - KEEP).clear();
            }
            return v;
        }

        /** The rows now and the version, or the changes after {@code since} when they are all still kept. */
        synchronized Map<String, Object> since(long since) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", version.get());
            boolean kept = since >= 0 && (changes.isEmpty() || changes.get(0).version() <= since + 1);
            if (kept) {
                m.put("changes", changes.stream().filter(c -> c.version() > since).toList());
            } else {
                m.put("rows", List.copyOf(rows.values()));
            }
            return m;
        }
    }

    private final Map<String, Live> live = new ConcurrentHashMap<>();
    private final OutputStore store;

    public Outputs(OutputStore store) {
        this.store = store;
    }

    /** Sends each table of {@code answer} where the engine file says (live, store); returns the notes of what was done. */
    List<String> publish(String engine, EngineFile file, Snapshot snapshot, Answer answer) {
        List<String> notes = new ArrayList<>();
        for (Table t : answer.tables()) {
            String output = t.schema().name();
            EngineFile.OutputUse use = file.outputs().get(output);
            if (use == null || !use.enabled()) {
                continue;
            }
            if (use.live()) {
                long v = live.computeIfAbsent(engine + "/" + output, k -> new Live()).apply(t);
                notes.add(output + ": live version " + v);
            }
            if (use.store() != null && store != null) {
                try {
                    long v = store.store(engine, output, use.store(), snapshot, t, answer);
                    notes.add(output + ": stored in " + use.store().table() + " (version " + v + ")");
                } catch (Exception e) {
                    notes.add(output + ": not stored: " + e.getMessage());
                }
            }
        }
        return notes;
    }

    /** A live output's rows or changes; empty when it has never been answered. */
    public java.util.Optional<Map<String, Object>> live(String engine, String output, long since) {
        Live l = live.get(engine + "/" + output);
        return l == null ? java.util.Optional.empty() : java.util.Optional.of(l.since(since));
    }

    /** Forgets an engine's live outputs (its instance shut down). */
    void forget(String engine) {
        live.keySet().removeIf(k -> k.startsWith(engine + "/"));
    }
}
