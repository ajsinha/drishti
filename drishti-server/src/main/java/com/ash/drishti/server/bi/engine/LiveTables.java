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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live tables of the server (RUPAKA.md section 12): one in-memory table per live table of each dataset, started on first use and closed when nobody has
 * used it for {@code drishti.bi.live.idle-close}. Starting reads the kind once and subscribes to its entities; closing gives the subscriptions back. A dataset
 * edited so that a live table's definition changed gets a new table (the old one is closed). Nothing here is per reader: the tables hold the source's rows, and
 * every query applies the reader's row rules and masks.
 */
public final class LiveTables implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LiveTables.class);

    private record Entry(String signature, LiveTable table, LiveFeed feed, java.util.concurrent.atomic.AtomicReference<Instant> used) {}

    private final AnalyticEngine engine;
    private final SourceGateway sources;
    private final BiLiveProperties props;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AtomicLong names = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> Thread.ofPlatform().daemon().name("drishti-bi-live").unstarted(r));
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();

    public LiveTables(AnalyticEngine engine, SourceGateway sources, BiLiveProperties props, Clock clock) {
        this.engine = engine;
        this.sources = sources;
        this.props = props;
        this.clock = clock;
        long every = Math.max(1_000, Math.min(30_000, props.idleClose().toMillis() / 2));
        scheduler.scheduleWithFixedDelay(this::closeIdle, every, every, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public BiLiveProperties properties() {
        return props;
    }

    /**
     * The live table for a dataset's table, started when it is not running (or when its definition changed). Blocks for the first load of the kind.
     *
     * @throws DrishtiException DRS-9050 for a table that cannot be live, DRS-9026 when no running connector serves its kind
     */
    public LiveTable ensure(String dsKey, Dataset ds, Dataset.Table t) {
        String key = dsKey + "|" + t.name();
        String signature = t.toString() + ds.rollups().stream().filter(r -> r.table().equals(t.name())).toList() + props.incrementalAggregates();
        Entry e = entries.get(key);
        if (e != null && e.signature().equals(signature)) {
            e.used().set(clock.instant());
            return e.table();
        }
        LiveTable.check(t);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            e = entries.get(key);
            if (e != null && e.signature().equals(signature)) {
                e.used().set(clock.instant());
                return e.table();
            }
            if (e != null) {
                closeEntry(key, e);
            }
            if (sources.plugin(t.connector(), t.kind()).isEmpty()) {
                throw new DrishtiException(ErrorCode.QUERY_SOURCE,
                        "no running connector serves kind '" + t.kind() + "'" + (t.connector().isBlank() ? "" : " under the name '" + t.connector() + "'") + ": check Admin, Connectors");
            }
            String sqlName = "live_" + names.incrementAndGet();
            List<LiveAggregate> kept = new ArrayList<>();
            if (props.incrementalAggregates()) {
                for (Dataset.Rollup r : ds.rollups()) {
                    LiveAggregate a = r.table().equals(t.name()) ? LiveAggregate.of(ds, r, t.physical(), sqlName, sqlName + "_agg" + kept.size()) : null;
                    if (a != null) {
                        kept.add(a);
                    }
                }
            }
            LiveTable table = new LiveTable(sqlName, t, engine, props, clock, kept);
            LiveFeed feed = new LiveFeed(table, t, sources, props, scheduler, io);
            Entry made = new Entry(signature, table, feed, new java.util.concurrent.atomic.AtomicReference<>(clock.instant()));
            feed.start();
            entries.put(key, made);
            LOG.info("live table {}.{} started over {} ({} rows, {} entities pushed)", dsKey, t.name(), t.kind(), table.status().rows(), table.status().subscribed());
            return table;
        } finally {
            lock.unlock();
        }
    }

    public Optional<LiveTable> find(String dsKey, String table) {
        Entry e = entries.get(dsKey + "|" + table);
        return e == null ? Optional.empty() : Optional.of(e.table());
    }

    /** Sum of the generations of the dataset's running live tables: grows when any of them changes (0 when none runs). */
    public long generation(String dsKey) {
        long g = 0;
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getKey().startsWith(dsKey + "|")) {
                g += e.getValue().table().generation();
            }
        }
        return g;
    }

    /** The dataset's live tables, running now. */
    public List<LiveTable> tables(String dsKey) {
        List<LiveTable> out = new ArrayList<>();
        entries.forEach((k, e) -> {
            if (k.startsWith(dsKey + "|")) {
                out.add(e.table());
            }
        });
        return out;
    }

    /** Marks the dataset's tables as used now (an open stream counts as use). */
    public void touch(String dsKey) {
        Instant now = clock.instant();
        entries.forEach((k, e) -> {
            if (k.startsWith(dsKey + "|")) {
                e.used().set(now);
            }
        });
    }

    /**
     * What a browser needs to show a live visual's state: the generation (grows when anything changed), the newest update, the worst state of the tables, and per
     * table the counters (including {@code seq} and {@code epoch} for a client that keeps rows).
     */
    public Map<String, Object> tick(String dsKey) {
        List<LiveTable> list = tables(dsKey);
        Map<String, Object> m = new LinkedHashMap<>();
        Instant newest = null;
        LiveTable.State worst = LiveTable.State.LIVE;
        List<Map<String, Object>> tables = new ArrayList<>();
        for (LiveTable t : list) {
            LiveTable.Status s = t.status();
            Map<String, Object> one = s.toMap();
            one.put("aggregates", t.aggregateStats());
            tables.add(one);
            if (s.lastUpdate() != null && (newest == null || s.lastUpdate().isAfter(newest))) {
                newest = s.lastUpdate();
            }
            if (rank(t.state()) > rank(worst)) {
                worst = t.state();
            }
        }
        m.put("generation", generation(dsKey));
        m.put("lastUpdate", newest == null ? null : newest.toString());
        m.put("state", list.isEmpty() ? "none" : worst.name().toLowerCase(java.util.Locale.ROOT));
        m.put("at", clock.instant().toString());
        m.put("tables", tables);
        return m;
    }

    private static int rank(LiveTable.State s) {
        return switch (s) {
            case LIVE -> 0;
            case STARTING -> 1;
            case STALE -> 2;
            case FAILED -> 3;
            case CLOSED -> 4;
        };
    }

    /** Closes the live tables of a dataset (it was deleted, or its model changed so much that the tables should start again). */
    public void drop(String dsKey) {
        entries.forEach((k, e) -> {
            if (k.startsWith(dsKey + "|")) {
                closeEntry(k, e);
            }
        });
    }

    /** Counters for the engine's page. */
    public Map<String, Object> stats() {
        long rows = 0;
        Map<String, Object> m = new LinkedHashMap<>();
        for (Entry e : entries.values()) {
            rows += e.table().status().rows();
        }
        m.put("tables", entries.size());
        m.put("rows", rows);
        m.put("enabled", props.enabled());
        return m;
    }

    private void closeEntry(String key, Entry e) {
        entries.remove(key, e);
        e.feed().close();
        e.table().close();
        LOG.info("live table {} closed", key);
    }

    private void closeIdle() {
        Instant limit = clock.instant().minus(props.idleClose());
        entries.forEach((k, e) -> {
            if (e.used().get().isBefore(limit)) {
                closeEntry(k, e);
            }
        });
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        entries.forEach(this::closeEntry);
        io.shutdownNow();
    }
}
