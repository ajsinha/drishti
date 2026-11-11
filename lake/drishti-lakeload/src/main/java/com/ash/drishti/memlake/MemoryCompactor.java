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
package com.ash.drishti.memlake;

import com.ash.drishti.deltalake.write.LakeManifest;
import com.ash.drishti.deltalake.write.LakeWriter;
import com.ash.drishti.deltalake.write.TableUpkeep;
import com.ash.drishti.memlake.store.MemoryStorage;
import com.ash.drishti.memlake.store.MemoryStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.engine.Engine;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automated compaction (docs/architecture/DATA_ARCHITECTURE.md section 13a): the memory optimizes itself. Every minute and after every commit, each store's
 * tables are scored by the memory a rewrite would save; the best one is rewritten (one at a time per store, as one atomic commit; readers
 * on the old version finish on it), and only when the store has room for its new files beside the old ({@code headroom-mb} and the table's
 * size free under the cap), so compaction never pushes a store into eviction. What triggers a rewrite: many small files, rows replaced past
 * {@code dead-rows} (merge), appends out of key order past {@code resort-after}. Every rewrite records the bytes before and after.
 */
final class MemoryCompactor {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryCompactor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A table worth rewriting: why, and the bytes the rewrite is expected to save. */
    record Candidate(MemoryTable table, String why, long saving, long size) {}

    private final MemoryLake lake;
    private final MemoryLakeSettings.Compaction cfg;
    private final BooleanSupplier quiet;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong rewrites = new AtomicLong();
    private final AtomicLong savedTotal = new AtomicLong();
    private final AtomicLong waitedForRoom = new AtomicLong();
    private volatile Instant lastRun;

    MemoryCompactor(MemoryLake lake, MemoryLakeSettings.Compaction cfg, BooleanSupplier quiet) {
        this.lake = lake;
        this.cfg = cfg;
        this.quiet = quiet == null ? () -> true : quiet;
    }

    /** After a commit: a look at that table's store, soon (on the clock, not on the job's thread). */
    void afterCommit(MemoryTable t) {
        if (cfg.enabled()) {
            Thread.ofVirtual().name("memory-lake-compaction").start(this::tick);
        }
    }

    /** One look: in each store, the best candidate rewritten. */
    void tick() {
        if (!cfg.enabled() || (cfg.idleOnly() && !quiet.getAsBoolean()) || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            lastRun = Instant.now();
            for (String store : lake.stores().keySet()) {
                if (lake.owns(store)) {
                    compactBest(store);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("memory lake: compaction stopped: {}", MemoryLake.valuesFree(e));
        } finally {
            running.set(false);
        }
    }

    /** The candidates of a store, best saving first. */
    List<Candidate> candidates(String storeName) {
        MemoryStorage store = MemoryStores.store(storeName);
        long small = (long) cfg.smallFileMb() << 20;
        List<Candidate> out = new ArrayList<>();
        for (MemoryTable t : lake.tables()) {
            if (!t.spec.store().equals(storeName) || t.state() != MemoryTable.State.READY) {
                continue;
            }
            TableUpkeep.Shape shape;
            try {
                shape = TableUpkeep.shape(LakeWriter.engineFor(t.spec.path(), Map.of()), t.spec.path(), small);
            } catch (Exception e) {
                continue;
            }
            long dates = Math.max(1, datesOf(t));
            if (shape.smallFiles() > dates) {
                // one footer and one dictionary per column per date instead of one per small file
                out.add(new Candidate(t, shape.smallFiles() + " small files", shape.bytes() * (shape.smallFiles() - dates) / Math.max(1, shape.files()) / 3,
                        shape.bytes()));
            } else if (t.spec.mode().equals("merge") && t.rows > 0 && t.rowsReplaced.get() >= cfg.deadRows() * t.rows) {
                out.add(new Candidate(t, t.rowsReplaced.get() + " rows replaced since the last rewrite", shape.bytes() / 5, shape.bytes()));
            } else if (t.spec.mode().equals("append") && t.appendsSinceSort.get() >= cfg.resortAfter()) {
                out.add(new Candidate(t, t.appendsSinceSort.get() + " appends out of key order", shape.bytes() / 4, shape.bytes()));
            }
        }
        out.sort(Comparator.comparingLong(Candidate::saving).reversed());
        return out;
    }

    private static long datesOf(MemoryTable t) {
        try {
            return TableUpkeep.rows(LakeWriter.engineFor(t.spec.path(), Map.of()), t.spec.path(), pv -> true).stream()
                    .map(s -> s.partitionValues().get("business_date")).distinct().count();
        } catch (Exception e) {
            return 1;
        }
    }

    private void compactBest(String storeName) {
        MemoryStorage store = MemoryStores.store(storeName);
        long cap = store.pool().capBytes();
        long headroom = Math.min((long) cfg.headroomMb() << 20, cap / 4);
        for (Candidate c : candidates(storeName)) {
            long free = cap - store.pool().inUseBytes();
            if (free < headroom + c.size()) {
                waitedForRoom.incrementAndGet();              // no room for the new files beside the old: the next candidate, or wait
                continue;
            }
            if (rewrite(c.table(), c.why())) {
                return;                                       // one rewrite a store a look
            }
        }
    }

    /** Rewrites the whole table sorted by {@code sort-by}, as one commit; old versions expired after it. True when it ran. */
    boolean rewrite(MemoryTable t, String why) {
        if (!t.lock.tryLock()) {
            return false;
        }
        long start = System.nanoTime();
        try {
            String path = t.spec.path();
            MemoryStorage store = MemoryStores.store(t.spec.store());
            Engine engine = LakeWriter.engineFor(path, Map.of("codec", t.spec.codec()));
            List<TableUpkeep.Staged> rows = TableUpkeep.rows(engine, path, pv -> true);
            if (rows == null) {
                return false;                                 // a column a staged line cannot carry: left as it is
            }
            long before = store.bytesUnder(path);
            List<String> partitionColumns = TableUpkeep.partitionColumns(engine, path);
            Map<Map<String, String>, List<String[]>> groups = new LinkedHashMap<>();
            for (TableUpkeep.Staged s : rows) {
                groups.computeIfAbsent(new TreeMap<>(s.partitionValues()), k -> new ArrayList<>()).add(new String[] {sortKey(t, s.line()), s.line()});
            }
            List<LakeManifest.Part> parts = new ArrayList<>();
            for (Map.Entry<Map<String, String>, List<String[]>> g : groups.entrySet()) {
                List<String[]> lines = g.getValue();
                lines.sort(Comparator.comparing(a -> a[0]));
                StringBuilder b = new StringBuilder();
                lines.forEach(a -> b.append(a[1]).append('\n'));
                String text = b.toString();
                parts.add(LakeManifest.Part.inMemory(() -> new StringReader(text), lines.size(), g.getKey()));
            }
            if (parts.isEmpty()) {
                return false;
            }
            List<LakeManifest.Column> columns = TableUpkeep.columns(engine, path);
            List<String> promoted = columns.stream().map(LakeManifest.Column::name)
                    .filter(n -> !n.equals("id") && !n.equals("doc") && !partitionColumns.contains(n)).toList();
            List<String> stats = new ArrayList<>(List.of("id"));
            stats.addAll(partitionColumns.stream().filter(c -> !c.equals("id")).toList());
            stats.addAll(promoted);
            LakeManifest m = new LakeManifest(path, partitionColumns, columns, promoted, "replace", 5_000, stats.stream().distinct().toList(), parts);
            LakeWriter.Result r = LakeWriter.forTable(path, Map.of("codec", t.spec.codec())).write(m, n -> { });
            MemoryUpkeep.expire(engine, store, path, 1);       // the old files go now; a reader on them keeps its chunks until it ends
            long after = store.bytesUnder(path);
            long saved = before - after;
            t.version = r.version();
            t.appendsSinceSort.set(0);
            t.rowsReplaced.set(0);
            t.compactions.incrementAndGet();
            t.saved(saved);
            rewrites.incrementAndGet();
            savedTotal.addAndGet(Math.max(0, saved));
            t.record(new MemoryTable.Run(Instant.now(), "compaction", "ok", r.rows(), after, r.version(), (System.nanoTime() - start) / 1_000_000,
                    "rewritten (" + why + "): " + (before >> 10) + " KB before, " + (after >> 10) + " KB after"));
            LOG.info("memory lake: {} compacted ({}): {} KB to {} KB", t.name(), why, before >> 10, after >> 10);
            lake.changed();
            return true;
        } catch (Exception e) {
            t.record(new MemoryTable.Run(Instant.now(), "compaction", "failed", 0, 0, t.version, (System.nanoTime() - start) / 1_000_000,
                    MemoryLake.valuesFree(e)));
            return false;
        } finally {
            t.lock.unlock();
        }
    }

    private static String sortKey(MemoryTable t, String line) {
        if (t.spec.sortBy().isEmpty()) {
            return "";
        }
        try {
            JsonNode doc = JSON.readTree(JSON.readTree(line).path("doc").asText("{}"));
            StringBuilder b = new StringBuilder();
            for (String k : t.spec.sortBy()) {
                JsonNode v = doc.get(k);
                b.append(v == null || v.isNull() ? "" : v.isTextual() ? v.asText() : v.toString()).append('\u001f');
            }
            return b.toString();
        } catch (java.io.IOException e) {
            return "";
        }
    }

    Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.enabled());
        m.put("idleOnly", cfg.idleOnly());
        m.put("rewrites", rewrites.get());
        m.put("savedTotalBytes", savedTotal.get());
        m.put("waitedForRoom", waitedForRoom.get());
        m.put("lastRun", lastRun == null ? null : lastRun.toString());
        return m;
    }
}
