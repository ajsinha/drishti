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
package com.ash.drishti.plugin.delta;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.DatedHitIndex;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.delta.kernel.engine.Engine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Serves entities from Delta Lake tables, one table per kind, partitioned by business date:
 * {@code <root>/<domain>/<kind>/} with {@code business_date=yyyy-MM-dd/} partitions of {@code (id, doc)} rows.
 *
 * <p>A read for a business date takes the entity from the latest partition on or before that date: within
 * {@code lookback-days} for {@code snapshot} tables (a full copy every business date, the default), or without
 * limit for {@code effective} tables (a row only when the entity changes, e.g. reference data). {@code knownAt}
 * reads the table as it was at that instant (Delta time travel), before later corrections.
 *
 * <p>Settings: {@code root} (default {@code ./data/delta}), {@code domain} (a sub-folder, e.g. {@code finance}),
 * {@code kinds} (comma list; default: every table found), {@code mode.<kind>} ({@code snapshot}|{@code effective}),
 * {@code lookback-days} (10), {@code id-column} ({@code id}), {@code doc-column} ({@code doc}), {@code date-column}
 * ({@code business_date}), {@code refresh-seconds} (10: how often a table's latest version is checked), {@code warm-dates}
 * (3: after start, in the background, each table's newest dates have their id maps and a first document read, so the
 * first read of a recent past date is not the slow one; 0 turns it off),
 * {@code cache-mb} (512: whole partitions of small tables kept in memory, by size), {@code source-name} ({@code delta}),
 * {@code engine} ({@code native}: Delta Kernel without Hadoop, the one that works on Windows; {@code hadoop}; or
 * {@code auto}, native on Windows; default from {@code DRISHTI_DELTA_ENGINE}, else {@code native}).
 *
 * <p>Large tables (millions of entities a day) are read without loading a day: a date's ids come from the id column
 * alone (an id map, {@code id-map-mb}, 1024), one entity from the one file and row group that hold it
 * ({@code doc-cache-mb}, 256, keeps recent documents), and searches and aggregates from the columns a pack's
 * {@code layout.<kind>.columns} promotes beside the document ({@code columns-cache-mb}, 1024). Reverse lookups use the
 * promoted columns; without them a day is loaded only when it has at most {@code max-load-rows} (200000) rows. At most
 * {@code max-concurrent-reads} (16) single-entity reads run at once (each decodes one row group of documents), and at most
 * {@code max-queued-reads} (4096) wait for one: more are refused at once, and a read whose caller stopped waiting leaves the queue.
 */
public final class DeltaSourcePlugin implements SourcePlugin {

    /**
     * A partition of one table version. The version's commit time is part of the key: a table removed and written again (lake demo, a
     * rebuild) starts its versions at 0 again, so the number alone would serve the old table's ids, documents and columns.
     */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DeltaSourcePlugin.class);
    /** A table date with at least this many ids says it was loaded at INFO; smaller ones at DEBUG. */
    private static final int ANNOUNCE_IDS = 10_000;

    /** A table date of one connector ({@code owner}): connectors may share the caches (see {@link Shared}), and kinds repeat across them. */
    private record PartKey(String owner, String kind, long version, long at, LocalDate date) {
    }

    /** One partition in memory: documents by id, and a reverse index (referenced id to referring ids). */
    private record Part(Map<String, String> docs, Map<String, Set<String>> refs) {}

    // concurrent: a reindex adds tables that appeared in the lake while reads go on
    private final Map<String, DeltaTable> tables = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> modes = new java.util.concurrent.ConcurrentHashMap<>();
    private Engine engine;
    private String idColumn;
    private String docColumn;
    private String dateColumn;
    /** True when no {@code kinds} are configured: every table in the domain is served, including new ones. */
    private boolean discover;
    private final HitIndex index = new HitIndex();
    private volatile DatedHitIndex dated = new DatedHitIndex(Map.of(), null);   // past days (index.days > 1), set at start
    // Async caches: a load (the Delta log, a Parquet partition) runs on a virtual thread, and callers wanting the same
    // key wait on its future without holding any lock. A synchronous Caffeine load would run inside the map's
    // compute and pin the carrier thread for the whole read. One read still serves every caller of a key.
    private final java.util.concurrent.ExecutorService loaders = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private AsyncCache<String, DeltaTable.Layout> latest;
    private AsyncCache<String, DeltaTable.Layout> travelled;
    private AsyncCache<PartKey, Part> parts;
    /** This connector in the shared caches' keys. */
    private final String owner = "c" + OWNERS.incrementAndGet();
    private static final java.util.concurrent.atomic.AtomicLong OWNERS = new java.util.concurrent.atomic.AtomicLong();

    private PartKey key(String kind, DeltaTable.Layout l, LocalDate date) {
        return new PartKey(owner, kind, l.version(), l.timestamp(), date);
    }

    /**
     * One memory budget for every Delta connector that sets no cache size of its own: their day loads, id maps, column sets and
     * documents share caches sized from the heap (5 %, 20 %, 20 % and 5 % of -Xmx: half the heap in all, however many connectors
     * run), instead of each connector holding up to its own 2.8 GB. A connector that sets cache-mb, id-map-mb, columns-cache-mb or
     * doc-cache-mb keeps a cache of its own of that size.
     */
    private static final class Shared {
        static final long HEAP = Runtime.getRuntime().maxMemory();
        static final java.util.concurrent.ExecutorService LOADERS = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        static final AsyncCache<PartKey, Part> PARTS = Caffeine.newBuilder().executor(LOADERS).maximumWeight(HEAP / 20)
                .weigher((PartKey k, Part v) -> partWeight(v)).buildAsync();
        static final AsyncCache<PartKey, DeltaTable.IdMap> ID_MAPS = Caffeine.newBuilder().executor(LOADERS).maximumWeight(HEAP / 5)
                .weigher((PartKey k, DeltaTable.IdMap v) -> (int) Math.min(Integer.MAX_VALUE, v.bytes()))
                .removalListener((PartKey k, DeltaTable.IdMap v, com.github.benmanes.caffeine.cache.RemovalCause c) -> unmap(v)).buildAsync();
        static final AsyncCache<PartKey, com.ash.drishti.api.ColumnSet> COLUMNS = Caffeine.newBuilder().executor(LOADERS).maximumWeight(HEAP / 5)
                .weigher((PartKey k, com.ash.drishti.api.ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, weight(v))).buildAsync();
        static final com.github.benmanes.caffeine.cache.Cache<String, String> DOCS = Caffeine.newBuilder().maximumWeight(HEAP / 20)
                .weigher((String k, String v) -> 2 * (k.length() + v.length()) + 64).build();
    }

    /**
     * The caches the Delta connectors share, by the memory pool each belongs to (2.9.7, D3): the server's one cache budget sets their
     * maxima ({@code connector.parts}, {@code connector.idmaps}, {@code connector.columns}, {@code connector.documents}).
     */
    public static Map<String, com.github.benmanes.caffeine.cache.Cache<?, ?>> sharedCaches() {
        Map<String, com.github.benmanes.caffeine.cache.Cache<?, ?>> m = new LinkedHashMap<>();
        m.put("connector.parts", Shared.PARTS.synchronous());
        m.put("connector.idmaps", Shared.ID_MAPS.synchronous());
        m.put("connector.columns", Shared.COLUMNS.synchronous());
        m.put("connector.documents", Shared.DOCS);
        return m;
    }

    /** The shared memory budget as it stands (Admin -> Caches): each shared cache's size and cap in MB, and the heap. */
    public static Map<String, Object> sharedBudget() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("heapMaxMB", Shared.HEAP / (1024 * 1024));
        out.put("heapUsedMB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024));
        out.put("idMapsMB", mb(Shared.ID_MAPS.synchronous()) + " of " + Shared.HEAP / 5 / (1024 * 1024));
        out.put("columnsMB", mb(Shared.COLUMNS.synchronous()) + " of " + Shared.HEAP / 5 / (1024 * 1024));
        out.put("documentsMB", mb(Shared.DOCS) + " of " + Shared.HEAP / 20 / (1024 * 1024));
        out.put("dayLoadsMB", mb(Shared.PARTS.synchronous()) + " of " + Shared.HEAP / 20 / (1024 * 1024));
        return out;
    }

    private static long mb(com.github.benmanes.caffeine.cache.Cache<?, ?> c) {
        return c.policy().eviction().map(e -> e.weightedSize().orElse(0L)).orElse(0L) / (1024 * 1024);
    }

    private static int partWeight(Part v) {
        return (int) Math.min(Integer.MAX_VALUE, 64L + v.docs().values().stream().mapToLong(d -> 2L * d.length() + 64).sum() + 48L * v.refs().size());
    }
    private AsyncCache<PartKey, DeltaTable.IdMap> idMaps;
    private AsyncCache<PartKey, com.ash.drishti.api.ColumnSet> columnSets;
    private com.github.benmanes.caffeine.cache.Cache<String, String> docs;
    /** Where id maps are kept on disk (id-map-disk.enabled), or null: they are then built in the heap at every start. */
    private java.nio.file.Path idMapDir;
    /** The id-map folder's cap, TTL and drops ({@code id-map-disk.max-gb} 5, {@code id-map-disk.ttl} off: {@link com.ash.drishti.api.IdMapFolder}). */
    private com.ash.drishti.api.IdMapFolder idMapFolder;
    /** Documents read before, on disk (doc-disk-cache.enabled), or null. */
    private volatile com.ash.drishti.diskcache.DiskCache diskDocs;
    private volatile java.util.concurrent.ScheduledFuture<?> diskDocsRetry;
    /** The periodic reindex: cancelled at close, so a stopped connector reads nothing and logs nothing (QA2-23). */
    private volatile java.util.concurrent.ScheduledFuture<?> reindexTask;
    /** Seconds between attempts to open a document cache found locked (tests shorten it). */
    static long diskRetrySeconds = 30;
    /** Kind to the document paths its pack promotes to columns ({@code layout.<kind>.columns}). */
    private final Map<String, List<String>> promoted = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Kind to the promoted columns the type-ahead also matches ({@code layout.<kind>.type-ahead: NAME,LEI}): their values join the
     * hit's subtitle, so a name finds its id ({@code Ford} finds {@code F}), not only an id prefix.
     */
    private final Map<String, List<String>> typeAhead = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Kind to its vector columns ({@code layout.<kind>.vectors: PNL}): {@code list<double>} columns kept out of the document (which holds
     * a marker {@code {"$vector": "PNL"}} instead), read in bulk by {@link #vectors} and put back into a document {@link #fetch} returns.
     */
    private final Map<String, List<String>> vectorCols = new java.util.concurrent.ConcurrentHashMap<>();
    private int maxLoadRows;
    /**
     * Single-entity reads decode a row group each: bounded, so a burst of them cannot exhaust memory; at most {@code max-queued-reads}
     * wait, and a read whose caller has stopped waiting leaves the queue (QA3A-01).
     */
    private com.ash.drishti.api.ReadSlots docReads;
    /** Single reads of background work (the start-up warm-up): apart from {@link #docReads}, so a page never waits behind them. */
    private com.ash.drishti.api.ReadSlots warmReads = new com.ash.drishti.api.ReadSlots("warm-up reads", 2, 64);
    private SourceContext context;
    private String sourceName;
    private int lookbackDays;
    private LakeStore lake;
    /** What cannot be read right now (a date's files, a table's log, a kind's ids): health and searches say so. */
    private volatile TableProblems problems = new TableProblems("delta");

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(sourceName == null ? "delta" : sourceName, "1.0", tables.keySet(), new SourceCapabilities(false, true, true, true));
    }

    /** The newest business date of the kind's table, from its latest layout (cached; no data read). */
    @Override
    public Optional<LocalDate> latestDate(String kind) {
        if (!tables.containsKey(kind)) {
            return Optional.empty();
        }
        return readableLayout(kind, null).filter(l -> !l.files().isEmpty()).map(l -> l.files().lastKey());
    }

    /** The oldest business date of the kind's table, from its latest layout (cached; no data read). */
    @Override
    public Optional<LocalDate> earliestDate(String kind) {
        if (!tables.containsKey(kind)) {
            return Optional.empty();
        }
        return readableLayout(kind, null).filter(l -> !l.files().isEmpty()).map(l -> l.files().firstKey());
    }

    /** Every business date of the kind's table, from its latest layout (cached; no data read). */
    @Override
    public Optional<java.util.NavigableSet<LocalDate>> heldDates(String kind) {
        if (!tables.containsKey(kind)) {
            return Optional.empty();
        }
        return readableLayout(kind, null).map(l -> java.util.Collections.unmodifiableNavigableSet(new java.util.TreeSet<>(l.files().keySet())));
    }

    /** A table's versions answer {@code knownAt}. */
    @Override
    public Map<LocalDate, Long> inventory(String kind, int maxDates) {
        if (!tables.containsKey(kind)) {
            return Map.of();
        }
        Optional<DeltaTable.Layout> l = readableLayout(kind, null);
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        if (l.isPresent()) {
            for (LocalDate d : l.get().files().descendingKeySet()) {
                if (out.size() >= maxDates) {
                    break;
                }
                out.put(d, (long) idMap(kind, l.get(), d).size());
            }
        }
        return out;
    }

    @Override
    public boolean timeTravel() {
        return true;
    }

    @Override
    public void start(SourceContext ctx) throws IOException {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "delta");
        this.problems = new TableProblems(sourceName);
        com.ash.drishti.api.IdMapFolder.ttl(sourceName, ctx.setting("id-map-disk.ttl", ""));   // a TTL under 24h fails the start, before anything opens
        com.ash.drishti.api.IdMapFolder.idleRelease(sourceName, ctx.setting("id-map-disk.idle-release", "30m"));   // under 5m fails the start too
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        // local disk or object storage (s3a://, abfs://, gs://), read by the native engine or Hadoop's (`engine`): the
        // rest of the connector does not know which
        this.lake = LakeStore.of(ctx.setting("root", com.ash.drishti.api.DataDir.under("delta")), ctx.setting("domain", ""), ctx.settings());
        this.engine = lake.engine();
        this.idColumn = ctx.setting("id-column", "id");
        this.docColumn = ctx.setting("doc-column", "doc");
        this.dateColumn = ctx.setting("date-column", "business_date");
        this.discover = ctx.setting("kinds", "").isBlank();
        for (String kind : kinds(ctx.setting("kinds", ""))) {
            add(kind);
        }
        long refresh = Long.parseLong(ctx.setting("refresh-seconds", "10"));
        this.latest = Caffeine.newBuilder().executor(loaders).expireAfterWrite(Duration.ofSeconds(refresh)).buildAsync();
        this.travelled = Caffeine.newBuilder().executor(loaders).maximumSize(256).buildAsync();
        // bounded by memory, not by count: a partition weighs about its documents' text (two bytes a character)
        Map<String, String> set = ctx.settings();
        this.parts = !set.containsKey("cache-mb") ? Shared.PARTS
                : Caffeine.newBuilder().executor(loaders).maximumWeight(Long.parseLong(set.get("cache-mb")) * 1024 * 1024)
                        .weigher((PartKey k, Part v) -> partWeight(v)).buildAsync();
        this.idMaps = !set.containsKey("id-map-mb") ? Shared.ID_MAPS
                : Caffeine.newBuilder().executor(loaders).maximumWeight(Long.parseLong(set.get("id-map-mb")) * 1024 * 1024)
                        .weigher((PartKey k, DeltaTable.IdMap v) -> (int) Math.min(Integer.MAX_VALUE, v.bytes()))
                        .removalListener((PartKey k, DeltaTable.IdMap v, com.github.benmanes.caffeine.cache.RemovalCause c) -> unmap(v)).buildAsync();
        this.columnSets = !set.containsKey("columns-cache-mb") ? Shared.COLUMNS
                : Caffeine.newBuilder().executor(loaders).maximumWeight(Long.parseLong(set.get("columns-cache-mb")) * 1024 * 1024)
                        .weigher((PartKey k, com.ash.drishti.api.ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, weight(v))).buildAsync();
        idMapFolder = com.ash.drishti.api.IdMapFolder.of(sourceName, ctx);
        if (idMapFolder != null) {
            // each table date's id map written once per table version, then memory-mapped: no heap per id, kept across restarts; the folder
            // capped (never a map in use) and, with id-map-disk.ttl, maps unused that long dropped (never the latest dates nor an open one)
            idMapDir = idMapFolder.dir();
            sweepVersions(idMapDir);                                // what a stopped server left: old versions, half-written files, over the cap
            idMapFolder.trim(null);
            idMapFolder.latestDates(this::latestDates);
            idMapFolder.schedule(ctx.scheduler(), loaders);
        }
        if (Boolean.parseBoolean(ctx.setting("doc-disk-cache.enabled", "true"))) {
            // documents already read, on disk under <data dir>/cache/<connector>/docs (one RocksDB per connector: no shared files or
            // compactions between connectors): keyed by the table version they were read from (never stale), capped by size, a time
            // limit and a daily clearing, compacted hourly, kept across restarts. It is a cache: when it cannot open, reads go on without it
            // A server restarted while the previous process is still stopping finds the cache locked: it retries every 30 s for
            // 10 minutes (reads go on without it meanwhile) rather than run without the cache until its next restart
            String failed = openDiskDocs(ctx);
            if (failed != null) {
                org.slf4j.LoggerFactory.getLogger(DeltaSourcePlugin.class).warn("{}: documents are not cached on disk ({}): reads go on without it{}", sourceName,
                        failed, ctx.scheduler() == null ? "" : "; trying again every 30 s for 10 minutes");
                if (ctx.scheduler() != null) {
                    long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                    diskDocsRetry = ctx.scheduler().scheduleWithFixedDelay(() -> {
                        String again = openDiskDocs(ctx);
                        if (again == null) {
                            org.slf4j.LoggerFactory.getLogger(DeltaSourcePlugin.class).info("{}: documents are cached on disk again", sourceName);
                        } else if (System.nanoTime() < until) {
                            return;
                        } else {
                            org.slf4j.LoggerFactory.getLogger(DeltaSourcePlugin.class).warn("{}: documents are not cached on disk until the next start ({})", sourceName, again);
                        }
                        diskDocsRetry.cancel(false);
                    }, diskRetrySeconds, diskRetrySeconds, TimeUnit.SECONDS);
                }
            }
        }
        this.docs = !set.containsKey("doc-cache-mb") ? Shared.DOCS : Caffeine.newBuilder().maximumWeight(Long.parseLong(set.get("doc-cache-mb")) * 1024 * 1024)
                .weigher((String k, String v) -> 2 * (k.length() + v.length()) + 64).build();
        this.maxLoadRows = Integer.parseInt(ctx.setting("max-load-rows", "200000"));
        this.docReads = new com.ash.drishti.api.ReadSlots("document reads of " + ctx.setting("source-name", "delta"),
                Integer.parseInt(ctx.setting("max-concurrent-reads", "16")), Integer.parseInt(ctx.setting("max-queued-reads", "4096")));
        this.warmReads = new com.ash.drishti.api.ReadSlots("warm-up reads of " + ctx.setting("source-name", "delta"), 2, 64);
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("layout.") && k.endsWith(".columns")) {
                promoted.put(k.substring(7, k.length() - 8), java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            } else if (k.startsWith("layout.") && k.endsWith(".type-ahead")) {
                typeAhead.put(k.substring(7, k.length() - 11), java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            } else if (k.startsWith("layout.") && k.endsWith(".vectors")) {
                vectorCols.put(k.substring(7, k.length() - 8), java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            }
        });
        this.dated = new DatedHitIndex(sourceName, DatedHitIndex.defaultRoot(), ctx.settings(), this::dayHits, (k, d) -> {
            String v = versions.get(k);
            return v == null ? null : "v" + v;
        });
        reindex();
        reindexTask = ctx.scheduler().scheduleWithFixedDelay(this::reindex, refresh * 6, refresh * 6, TimeUnit.SECONDS);
        int warm = Integer.parseInt(ctx.setting("warm-dates", "3"));
        if (warm > 0) {
            loaders.execute(() -> warmRecentDates(warm));          // in the background: start does not wait for it
        }
    }

    /**
     * Makes the first read of a recent past date as quick as a later one: for each table, the id map of its newest
     * {@code dates} partitions and one document of each (which loads the Parquet reader and its classes). The newest
     * partition is already warm from the reindex. A failure is ignored: the read does the same work and reports it.
     */
    void warmRecentDates(int dates) {
        for (String kind : tables.keySet()) {
            try {
                Optional<DeltaTable.Layout> l = layout(kind, null);
                if (l.isEmpty()) {
                    continue;
                }
                int n = 0;
                for (LocalDate d : l.get().files().descendingKeySet()) {
                    if (n++ >= dates) {
                        break;
                    }
                    DeltaTable.IdMap ids = idMap(kind, l.get(), d);
                    if (ids.size() > 0) {
                        doc(EntityRef.of(kind, ids.id(0)), l.get(), d, warmReads);   // background: its own slot, never a page's
                    }
                }
            } catch (RuntimeException e) {
                // the read of that date will say what is wrong
            }
        }
    }

    private void add(String kind) {
        modes.put(kind, context.setting("mode." + kind, "snapshot"));
        tables.putIfAbsent(kind, new DeltaTable(engine, lake.table(kind), idColumn, docColumn, dateColumn));
    }

    /** New tables in the domain (a kind loaded after start) are served from the next reindex, without a restart. */
    private void discoverTables() {
        if (!discover) {
            return;
        }
        try {
            for (String kind : lake.tables()) {
                if (!tables.containsKey(kind)) {
                    add(kind);                                 // shows in /api/v1/sources and Admin → Health
                }
            }
        } catch (IOException | RuntimeException e) {
            // the lake is unreachable for now: health says so, and the next reindex tries again
        }
    }

    private List<String> kinds(String configured) throws IOException {
        if (!configured.isBlank()) {
            return java.util.Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        return lake.tables();
    }

    private Optional<DeltaTable.Layout> layout(String kind, Instant knownAt) {
        DeltaTable t = tables.get(kind);
        if (t == null) {
            return Optional.empty();
        }
        if (knownAt == null) {
            return Optional.ofNullable(join(latest.get(kind, k -> t.layout(null).orElse(null))));
        }
        return Optional.ofNullable(join(travelled.get(kind + "@" + knownAt.toEpochMilli(), k -> t.layout(knownAt).orElse(null))));
    }

    private static long weight(com.ash.drishti.api.ColumnSet c) {
        long w = 64L + 48L * c.size();
        for (String id : c.ids()) {
            w += 2L * id.length();
        }
        w += 8L * c.size() * c.numbers().size() + 8L * c.size() * c.texts().size();
        return w;
    }

    private DeltaTable.IdMap idMap(String kind, DeltaTable.Layout l, LocalDate date) {
        PartKey key = key(kind, l, date);
        DeltaTable.IdMap held = join(loadIdMap(key, kind, l, date));
        if (held instanceof MappedIdMap mm && mm.closed()) {             // its file was pruned or trimmed meanwhile: mapped or read again
            idMaps.synchronous().asMap().remove(key, held);
            held = join(loadIdMap(key, kind, l, date));
        }
        return held;
    }

    /** The index of the file holding {@code id} on {@code date}, or -1; a map released meanwhile (replaced, dropped) is asked again. */
    private int fileOf(String kind, DeltaTable.Layout l, LocalDate date, String id) {
        for (int tries = 0;; tries++) {
            try {
                return idMap(kind, l, date).file(id);
            } catch (com.ash.drishti.api.MappedIdFile.Closed e) {
                if (tries >= 3) {
                    throw e;
                }
            }
        }
    }

    /** The date's ids; a map released meanwhile is asked again. */
    private String[] idsOf(String kind, DeltaTable.Layout l, LocalDate date) {
        for (int tries = 0;; tries++) {
            try {
                return idMap(kind, l, date).ids();
            } catch (com.ash.drishti.api.MappedIdFile.Closed e) {
                if (tries >= 3) {
                    throw e;
                }
            }
        }
    }

    /** An id map leaving the cache releases its mapping (once its readers are done): a deleted file never stays mapped. */
    private static void unmap(DeltaTable.IdMap m) {
        if (m instanceof MappedIdMap mm) {
            mm.close();
        }
    }

    private java.util.concurrent.CompletableFuture<DeltaTable.IdMap> loadIdMap(PartKey key, String kind, DeltaTable.Layout l, LocalDate date) {
        return idMaps.get(key, k -> {
            long started = System.nanoTime();
            java.nio.file.Path file = idMapDir == null ? null : idMapDir.resolve(safeName(kind)).resolve(l.version() + "@" + l.timestamp())
                    .resolve((date == LocalDate.MIN ? "undated" : date.toString()) + ".idmap");
            DeltaTable.IdMap m = file == null ? null : MappedIdMap.open(file);   // written before (by this run or an earlier one)
            String how = "mapped from disk";
            if (m == null) {
                m = tables.get(kind).ids(l, date);
                how = "read";
                if (file != null) {
                    try {
                        m = MappedIdMap.write(m, file);
                        how = "read, mapped to disk";
                        pruneOtherVersions(file.getParent(), idMapFolder);
                        idMapFolder.trim(file);                         // never this map, nor another in use
                    } catch (java.io.IOException | RuntimeException e) {
                        LOG.debug("{}: id map of {} {} kept in the heap: {}", sourceName, kind, date, e.toString());
                    }
                }
            }
            // one line per large table and date, when it is first read (the rest at DEBUG): the Parquet reader's per-record lines are off (application.yaml)
            LOG.atLevel(m.size() >= ANNOUNCE_IDS ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.DEBUG).log("{}: {} {} loaded ({}), {} ids in {} files ({} ms)", sourceName, kind, date == LocalDate.MIN ? "(no dates)" : date,
                    how, String.format("%,d", m.size()), l.files().getOrDefault(date, List.of()).size(), (System.nanoTime() - started) / 1_000_000);
            return m;
        });
    }

    /** A kind's name as a folder name. */
    private static String safeName(String kind) {
        return kind.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * The id map files of older versions of the table go (a new load made them unused), all but the newest version before
     * {@code versionDir}'s: a request started on it finishes (2.9.7). A file still mapped is unmapped first, once its readers are done, and
     * deleted then (before 2.9.7 it was deleted while mapped: its disk stayed held on Linux, the delete failed on Windows).
     */
    static void pruneOtherVersions(java.nio.file.Path versionDir) {
        pruneOtherVersions(versionDir, null);
    }

    /** As {@link #pruneOtherVersions(java.nio.file.Path)}, each map dropped through {@code folder} ("replaced (new version)" on Admin -> Caches). */
    static void pruneOtherVersions(java.nio.file.Path versionDir, com.ash.drishti.api.IdMapFolder folder) {
        List<java.nio.file.Path> others;
        try (var dirs = java.nio.file.Files.list(versionDir.getParent())) {
            others = dirs.filter(d -> !d.equals(versionDir) && java.nio.file.Files.isDirectory(d)).sorted(DeltaSourcePlugin::newerFirst).toList();
        } catch (java.io.IOException e) {
            return;                                                    // nothing to prune
        }
        long mine = versionOf(versionDir);
        boolean keptPrevious = false;
        for (java.nio.file.Path other : others) {
            if (!keptPrevious && versionOf(other) < mine) {
                keptPrevious = true;                                   // the version just before this one stays
                continue;
            }
            deleteVersion(other, folder);
        }
    }

    /** A version folder's table version ({@code <version>@<timestamp>}), -1 when it is not one. */
    static long versionOf(java.nio.file.Path dir) {
        String n = dir.getFileName().toString();
        int at = n.indexOf('@');
        try {
            return Long.parseLong(at < 0 ? n : n.substring(0, at));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int newerFirst(java.nio.file.Path a, java.nio.file.Path b) {
        return Long.compare(versionOf(b), versionOf(a));
    }

    private static void deleteVersion(java.nio.file.Path dir, com.ash.drishti.api.IdMapFolder folder) {
        try (var files = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path f : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (java.nio.file.Files.isDirectory(f)) {
                    try {
                        java.nio.file.Files.deleteIfExists(f);
                    } catch (java.io.IOException e) {
                        // a file inside still waits for its readers: the next prune or sweep removes the folder
                    }
                } else if (folder != null && f.getFileName().toString().endsWith(".idmap")) {
                    folder.drop(f, com.ash.drishti.api.IdMapFolder.Drop.REPLACED);   // unmapped, then deleted (tried again when that fails)
                } else {
                    com.ash.drishti.api.MappedIdFile.release(f, true);
                }
            }
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            // removed at a later load or start
        }
    }

    /**
     * At start: per kind, every version folder but the newest two goes (nothing is mapped yet), half-written files go, and the folder is
     * brought under its cap.
     */
    static void sweepIdMaps(java.nio.file.Path root, long maxBytes) {
        sweepVersions(root);
        com.ash.drishti.api.MappedIdFile.trim(root, maxBytes, null);
    }

    /** At start: per kind, every version folder but the newest two goes (nothing is mapped yet). */
    static void sweepVersions(java.nio.file.Path root) {
        if (root == null || !java.nio.file.Files.isDirectory(root)) {
            return;
        }
        try (var kinds = java.nio.file.Files.list(root)) {
            for (java.nio.file.Path kind : kinds.filter(java.nio.file.Files::isDirectory).toList()) {
                List<java.nio.file.Path> versions;
                try (var vs = java.nio.file.Files.list(kind)) {
                    versions = vs.filter(java.nio.file.Files::isDirectory).sorted(DeltaSourcePlugin::newerFirst).toList();
                }
                for (java.nio.file.Path old : versions.subList(Math.min(2, versions.size()), versions.size())) {
                    deleteVersion(old, null);
                }
            }
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            // nothing to sweep
        }
    }

    /** Each table's latest and previous dates (the id-map TTL never drops them); throws when a table cannot be read now. */
    private java.util.Set<LocalDate> latestDates() {
        java.util.Set<LocalDate> out = new java.util.HashSet<>();
        for (String kind : tables.keySet()) {
            layout(kind, null).ifPresent(l -> {
                java.util.NavigableSet<LocalDate> ds = l.files().navigableKeySet();
                if (!ds.isEmpty()) {
                    out.add(ds.last());
                    LocalDate prev = ds.lower(ds.last());
                    if (prev != null) {
                        out.add(prev);
                    }
                }
            });
        }
        return out;
    }

    /** The promoted paths the table really has as columns (a pack may declare more than a writer wrote). */
    private Map<String, String> promotedColumns(String kind, DeltaTable.Layout l) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String path : promoted.getOrDefault(kind, List.of())) {
            String column = path.replace(".", "__");
            if (l.schema().indexOf(column) >= 0) {
                out.put(path, column);
            }
        }
        return out;
    }

    private Part part(String kind, DeltaTable.Layout l, LocalDate date) {
        return join(parts.get(key(kind, l, date), k -> {
            Map<String, String> docs = tables.get(kind).read(l, date);
            Map<String, Set<String>> refs = new HashMap<>();
            docs.forEach((id, json) -> ReferenceScanner.referencedIds(json).forEach(r -> refs.computeIfAbsent(r, x -> new HashSet<>()).add(id)));
            return new Part(Collections.unmodifiableMap(docs), Collections.unmodifiableMap(refs));
        }));
    }

    /** Waits for a load, rethrowing what it threw (not wrapped). */
    private static <T> T join(java.util.concurrent.CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    /** Partitions to look in for {@code date}, newest first. */
    private List<LocalDate> candidates(String kind, DeltaTable.Layout l, LocalDate date) {
        var head = date == null ? l.files().descendingKeySet() : l.files().headMap(date, true).descendingKeySet();
        List<LocalDate> out = new ArrayList<>();
        boolean effective = "effective".equals(modes.get(kind));
        for (LocalDate d : head) {
            if (!effective && date != null && d.isBefore(date.minusDays(lookbackDays))) {
                break;
            }
            out.add(d);
            if (!effective) {
                break;   // a snapshot table: the newest partition on or before the date holds everything
            }
        }
        return out;
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws IOException {
        return fetch(ref, AsOf.LATEST);
    }

    /** The table's layout, recording a log that cannot be read (health says so) and rethrowing: a failure, not "not held". */
    private Optional<DeltaTable.Layout> readableLayout(String kind, Instant knownAt) {
        try {
            return layout(kind, knownAt);
        } catch (RuntimeException e) {
            throw problems.failed(kind, TableProblems.LOG, -1, e);
        }
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws IOException {
        Optional<DeltaTable.Layout> l = readableLayout(ref.kind(), asOf.knownAt());
        if (l.isEmpty()) {
            return Optional.empty();
        }
        for (LocalDate d : candidates(ref.kind(), l.get(), asOf.businessDate())) {
            String json;
            try {
                json = doc(ref, l.get(), d);
            } catch (RuntimeException e) {
                // the date's files cannot be read (truncated, a codec the engine does not decompress): the read fails
                // naming them, rather than looking as if the table did not hold the entity
                throw problems.failed(ref.kind(), d.toString(), l.get().timestamp(), e);
            }
            problems.ok(ref.kind(), d.toString());           // the date's files were read
            if (json == null) {
                continue;
            }
            if (json.contains("\"$vector\"")) {
                json = withVectors(ref, l.get(), d, json);
            }
            DataNode doc = context.parseJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            return Optional.of(new EntityDocument(ref, doc, new Provenance(sourceName, l.get().version(), Instant.now(), false,
                    d == LocalDate.MIN ? null : d)));
        }
        return Optional.empty();
    }

    /**
     * History queries' fast path (HISTORY_QUERIES.md section 10): the dates the table's snapshot already lists in memory
     * ({@code business_date=} partitions from..to) whose id map holds the entity, so the per-day read
     * ({@link com.ash.drishti.api.HistoryReads#perDay}) reads only those days' row groups and never a date without the entity. Id maps are
     * the ones single reads use (memory-mapped, cached). For an effective table these are the entity's change dates. No document is read.
     */
    @Override
    public Optional<java.util.NavigableSet<LocalDate>> days(EntityRef ref, LocalDate from, LocalDate to) {
        if (!tables.containsKey(ref.kind())) {
            return Optional.of(new java.util.TreeSet<>());
        }
        Optional<DeltaTable.Layout> l = readableLayout(ref.kind(), null);
        if (l.isEmpty()) {
            return Optional.of(new java.util.TreeSet<>());
        }
        java.util.TreeSet<LocalDate> out = new java.util.TreeSet<>();
        for (LocalDate d : l.get().files().subMap(from, true, to, true).keySet()) {
            if (d == LocalDate.MIN) {
                continue;
            }
            try {
                if (fileOf(ref.kind(), l.get(), d, ref.id()) >= 0) {
                    out.add(d);
                }
            } catch (RuntimeException e) {
                throw problems.failed(ref.kind(), d.toString(), l.get().timestamp(), e);
            }
        }
        return Optional.of(out);
    }

    /**
     * The date's documents in one pass over its files (derived pages): a snapshot table only, whose newest partition on or
     * before the date holds every entity; an effective table (an entity's latest row across dates) is read entity by entity.
     */
    @Override
    public boolean documents(String kind, AsOf asOf, java.util.function.Consumer<EntityDocument> sink) {
        if (!tables.containsKey(kind) || "effective".equals(modes.get(kind))) {
            return false;
        }
        Optional<DeltaTable.Layout> l = readableLayout(kind, asOf.knownAt());
        if (l.isEmpty()) {
            return true;                                       // no such table yet: it holds nothing
        }
        List<LocalDate> dates = candidates(kind, l.get(), asOf.businessDate());
        if (dates.isEmpty()) {
            return true;
        }
        LocalDate d = dates.get(0);
        long version = l.get().version();
        try {
            tables.get(kind).forEachDoc(l.get(), d, (id, json) -> {
                DataNode doc;
                try {
                    doc = context.parseJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                sink.accept(new EntityDocument(EntityRef.of(kind, id), doc,
                        new Provenance(sourceName, version, Instant.now(), false, d == LocalDate.MIN ? null : d)));
            });
        } catch (RuntimeException e) {
            throw problems.failed(kind, d.toString(), l.get().timestamp(), e);
        }
        problems.ok(kind, d.toString());
        return true;
    }

    /** {@code 90s}, {@code 30m}, {@code 12h}, {@code 7d}, or an ISO-8601 duration ({@code PT12H}). */
    static java.time.Duration duration(String text) {
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("p")) {
            return java.time.Duration.parse(t.toUpperCase(java.util.Locale.ROOT));
        }
        long n = Long.parseLong(t.substring(0, t.length() - 1));
        return switch (t.charAt(t.length() - 1)) {
            case 's' -> java.time.Duration.ofSeconds(n);
            case 'm' -> java.time.Duration.ofMinutes(n);
            case 'h' -> java.time.Duration.ofHours(n);
            case 'd' -> java.time.Duration.ofDays(n);
            default -> throw new IllegalArgumentException("not a duration: " + text + " (use 90s, 30m, 12h or 7d)");
        };
    }

    /** The entity's document on one date, or null when that date's files do not hold it. */
    private String doc(EntityRef ref, DeltaTable.Layout l, LocalDate d) {
        return doc(ref, l, d, docReads);
    }

    /** The entity's document on one date, read under {@code slots} (pages: {@link #docReads}; background work: {@link #warmReads}). */
    private String doc(EntityRef ref, DeltaTable.Layout l, LocalDate d, com.ash.drishti.api.ReadSlots slots) {
        // the id map says which file holds the entity; only that file's matching row group is read
        int file = fileOf(ref.kind(), l, d, ref.id());
        if (file < 0) {
            return null;
        }
        String key = ref.kind() + "\u001f" + l.version() + "@" + l.timestamp() + "\u001f" + d + "\u001f" + ref.id();
        String heapKey = owner + "\u001f" + key;                // the heap cache may be shared by connectors; the disk one is this connector's
        String json = docs.getIfPresent(heapKey);
        if (json == null && diskDocs != null) {                // second tier: documents read before, on disk (also by an earlier run)
            byte[] b = diskDocs.get(key);
            if (b != null) {
                json = new String(b, StandardCharsets.UTF_8);
                docs.put(heapKey, json);
                return json;
            }
        }
        if (json == null) {
            slots.acquire();                                   // refused when too many wait; never taken for a caller that has gone
            try {
                json = tables.get(ref.kind()).doc(l, d, file, ref.id()).orElse(null);
            } finally {
                slots.release();
            }
            if (json != null) {
                // the disk first: a document in the heap tier is already on disk, so a reader the heap answers never leaves the disk
                // without it (the start-up warm-up and a page reading one document at once; a close dropping the warm-up's late write)
                if (diskDocs != null) {
                    diskDocs.put(key, json.getBytes(StandardCharsets.UTF_8));
                }
                docs.put(heapKey, json);
            }
        }
        return json;
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        List<EntityRef> out = new ArrayList<>();
        for (String k : kind == null ? tables.keySet() : List.of(kind)) {
            layout(k, asOf.knownAt()).ifPresent(l -> {
                Set<String> seen = new HashSet<>();
                Map<String, String> cols = promotedColumns(k, l);
                for (LocalDate d : candidates(k, l, asOf.businessDate())) {
                    if (!cols.isEmpty() && !l.deletionVectors()) {
                        // promoted link columns (book, nettingSet, counterparty.id …): no document is read
                        com.ash.drishti.api.ColumnSet c = columnSet(k, l, d, cols);
                        List<String> found = new ArrayList<>();
                        c.texts().values().forEach(values -> {
                            for (int i = 0; i < values.length; i++) {
                                if (target.id().equals(values[i])) {
                                    found.add(c.ids()[i]);
                                }
                            }
                        });
                        found.stream().filter(seen::add).sorted().forEach(id -> out.add(EntityRef.of(k, id)));
                    } else if (idMap(k, l, d).size() <= maxLoadRows) {
                        part(k, l, d).refs().getOrDefault(target.id(), Set.of()).stream().filter(seen::add).sorted()
                                .forEach(id -> out.add(EntityRef.of(k, id)));
                    }
                }
            });
        }
        return out;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
        if (dated.enabled()) {
            var hits = dated.search(kind, text, limit, asOf == null ? null : asOf.businessDate());
            if (hits.isPresent()) {
                return hits.get();
            }
        }
        return index.search(kind, text, limit);
    }

    /** A day's ids from the id column alone, for the multi-day type-ahead. */
    private List<EntityHit> dayHits(String kind, LocalDate day) {
        Optional<DeltaTable.Layout> l = layout(kind, null);
        List<EntityHit> out = new ArrayList<>();
        if (l.isPresent() && l.get().files().containsKey(day)) {
            out.addAll(idHits(kind, l.get(), day));
        }
        return out;
    }

    /**
     * A day's type-ahead hits: its ids from the id column alone, each with the values of the kind's {@code layout.<kind>.type-ahead}
     * columns in its subtitle (read from the day's promoted columns), so a name or another identifier finds the entity too.
     */
    private List<EntityHit> idHits(String kind, DeltaTable.Layout l, LocalDate day) {
        String subtitle = kind + " · " + sourceName;
        Map<String, String> words = typeAheadWords(kind, l, day);
        List<EntityHit> out = new ArrayList<>();
        for (String id : idsOf(kind, l, day)) {
            String w = words.get(id);
            out.add(new EntityHit(EntityRef.of(kind, id), id, w == null ? subtitle : w + " · " + subtitle));
        }
        return out;
    }

    /** Id to the joined values of the kind's type-ahead columns on one day; empty when it declares none or they cannot be read. */
    private Map<String, String> typeAheadWords(String kind, DeltaTable.Layout l, LocalDate day) {
        List<String> wanted = typeAhead.getOrDefault(kind, List.of());
        Map<String, String> cols = promotedColumns(kind, l);
        if (wanted.stream().noneMatch(cols::containsKey) || l.deletionVectors()) {
            return Map.of();
        }
        try {
            com.ash.drishti.api.ColumnSet c = columnSet(kind, l, day, cols);
            Map<String, String> out = new HashMap<>();
            for (int i = 0; i < c.size(); i++) {
                StringBuilder b = new StringBuilder();
                for (String path : wanted) {
                    Object v = c.has(path) ? c.value(path, i) : null;
                    if (v != null && !String.valueOf(v).isBlank()) {
                        b.append(b.isEmpty() ? "" : " · ").append(v);
                    }
                }
                if (!b.isEmpty()) {
                    out.put(c.ids()[i], b.toString());
                }
            }
            return out;
        } catch (RuntimeException e) {
            return Map.of();                                    // ids alone: the type-ahead still finds them by id
        }
    }

    /** The newest commit time among the domain's tables (data loaded, appended or restated). */
    private volatile java.time.Instant lastUpdate;
    private final Map<String, String> versions = new java.util.concurrent.ConcurrentHashMap<>();

    /** The table's version and its commit time ({@code 7@1760700000000}): a rewritten table has a new one even when its version number repeats. */
    private static String stamp(DeltaTable.Layout l) {
        return l.version() + "@" + l.timestamp();
    }

    @Override
    public Optional<String> dataVersion(String kind, LocalDate date) {
        if (date == null) {
            return dataVersion(kind);
        }
        try {
            return layout(kind, null).map(l -> DeltaTable.filesStamp(l, date));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Per kind: the snapshot version its counts are of, and the counts (one log read per new version). */
    private record Counted(long version, java.util.NavigableMap<LocalDate, DeltaTable.DayCount> days, double bytesPerRow) {}

    private final Map<String, Counted> counted = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The date's rows from the add actions of the current snapshot (docs/architecture/BI_RUNS.md section 4.1): the sum of {@code numRecords} in
     * their statistics; a file without statistics counts its bytes ÷ the bytes per row of the files that have both. No data file is read. A date
     * without files holds 0 rows. Empty when the log cannot be read now, or no file of the table has a record count to learn from.
     */
    @Override
    public Optional<com.ash.drishti.api.RowEstimate> rowEstimate(String kind, LocalDate date) {
        DeltaTable t = tables.get(kind);
        if (t == null || date == null) {
            return Optional.empty();
        }
        try {
            Optional<DeltaTable.Layout> l = layout(kind, null);
            if (l.isEmpty()) {
                return Optional.empty();
            }
            long version = l.get().version();
            Counted c = counted.get(kind);
            if (c == null || c.version() != version) {
                java.util.NavigableMap<LocalDate, DeltaTable.DayCount> days = t.counts(version);
                long records = 0;
                long bytes = 0;
                for (DeltaTable.DayCount d : days.values()) {
                    records += d.records();
                    bytes += d.bytes();
                }
                c = new Counted(version, days, records == 0 ? 0 : (double) bytes / records);
                counted.put(kind, c);
            }
            DeltaTable.DayCount d = c.days().get(date);
            if (d == null) {
                return Optional.of(new com.ash.drishti.api.RowEstimate(0, com.ash.drishti.api.RowEstimate.DELTA_STATS));
            }
            long rows = d.records();
            if (d.filesUncounted() > 0) {
                if (c.bytesPerRow() <= 0) {
                    return Optional.empty();
                }
                rows += Math.round(d.bytesUncounted() / c.bytesPerRow());
            }
            return Optional.of(new com.ash.drishti.api.RowEstimate(rows, com.ash.drishti.api.RowEstimate.DELTA_STATS));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> dataVersion(String kind) {
        try {
            return layout(kind, null).map(DeltaSourcePlugin::stamp);
        } catch (RuntimeException e) {
            return Optional.empty();                           // the log cannot be read now: the caller keeps what it has
        }
    }

    /** The table's id from its Delta log, else its location: the same on every server that reads this table (Drishti*net's replicas). */
    @Override
    public Optional<String> tableIdentity(String kind) {
        DeltaTable t = tables.get(kind);
        return t == null ? Optional.empty() : Optional.of(t.identity());
    }

    /** No table for the kind, or a table not created yet: nothing of it is here (Drishti*net leaves this source out of the digest). */
    @Override
    public boolean holdsNone(String kind) {
        if (tables.get(kind) == null) {
            return true;
        }
        try {
            return layout(kind, null).isEmpty();
        } catch (RuntimeException e) {
            return false;                                      // the log cannot be read now: it may hold some
        }
    }

    @Override
    public java.time.Instant lastUpdate() {
        return lastUpdate;
    }

    /**
     * Rebuilds the search index from each table's newest partition (identifiers are stable across dates). A table whose
     * log or newest ids cannot be read keeps the ids it listed before (type-ahead still finds them), says so to searches
     * ({@link #listingProblem}) and to health, and is tried again at the next reindex.
     */
    void reindex() {
        if (loaders.isShutdown()) {
            return;                                    // closed: a run already due when the connector stopped reads nothing (QA2-23)
        }
        discoverTables();
        for (String kind : tables.keySet()) {
            String where = TableProblems.LOG;
            long version = -1;
            try {
                Optional<DeltaTable.Layout> layout = layout(kind, null);
                problems.ok(kind, TableProblems.LOG);
                if (layout.isEmpty()) {
                    listed.remove(kind);
                    problems.listingOk(kind);
                    continue;
                }
                version = layout.get().timestamp();             // the commit, by its time: a table written again restarts its version numbers
                problems.version(kind, version);
                if (!layout.get().files().isEmpty()) {
                    where = layout.get().files().lastKey().toString();
                }
                listed.put(kind, new Listed(hits(kind, layout.get()), Instant.now()));
                problems.ok(kind, where);
                problems.listingOk(kind);
            } catch (RuntimeException e) {
                if (loaders.isShutdown()) {
                    return;                            // closed while this run was reading: nothing to report (QA2-23)
                }
                // a table being rewritten, a broken log, an unreadable newest day: the previous ids stay listed
                RuntimeException why = problems.failed(kind, where, version, e);
                Listed kept = listed.get(kind);
                problems.listingFailed(kind, why instanceof com.ash.drishti.api.UnreadableData ? why.getMessage()
                        : TableProblems.LOG.equals(where) ? "the table's log cannot be read" : "its " + where + " files cannot be read",
                        kept == null ? null : kept.at());
            }
        }
        listed.keySet().retainAll(tables.keySet());
        List<EntityHit> hits = new ArrayList<>();
        listed.values().forEach(l -> hits.addAll(l.hits()));
        index.replaceAll(hits);
    }

    /** True while the periodic reindex is scheduled (from start to close). */
    boolean reindexScheduled() {
        java.util.concurrent.ScheduledFuture<?> f = reindexTask;
        return f != null && !f.isCancelled() && !f.isDone();
    }

    /** The ids a kind listed at the last reindex that could read them, and when. */
    private record Listed(List<EntityHit> hits, Instant at) {}

    private final Map<String, Listed> listed = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Optional<String> listingProblem(String kind) {
        return problems.listing(kind);
    }

    /** The kind's ids on its newest date, for type-ahead; warms that date's columns for the first search. */
    private List<EntityHit> hits(String kind, DeltaTable.Layout l) {
        List<EntityHit> hits = new ArrayList<>();
        String was = versions.put(kind, stamp(l));
        if (was == null || !was.equals(stamp(l))) {
            Instant committed = Instant.ofEpochMilli(l.timestamp());   // the version's commit time
            Instant before = lastUpdate;
            if (before == null || committed.isAfter(before)) {
                lastUpdate = committed;
            }
        }
        if (!l.files().isEmpty()) {                             // ids from the id column alone: no document is read
            hits.addAll(idHits(kind, l, l.files().lastKey()));
            if (dated.enabled()) {
                dated.putDay(kind, l.files().lastKey(), hits);
                dated.plan(kind, l.files().navigableKeySet());
                if (was != null && !was.equals(stamp(l))) {
                    dated.restatedAll(kind);                    // a commit: a past day may have been restated
                }
            }
            Map<String, String> cols = promotedColumns(kind, l);
            if (!cols.isEmpty() && !l.deletionVectors()) {       // the newest day's columns, ready before the first search
                loaders.execute(() -> {
                    try {
                        columnSet(kind, l, l.files().lastKey(), cols);
                    } catch (RuntimeException e) {
                        // the first search loads them instead
                    }
                });
            }
        }
        return hits;
    }

    @Override
    public Optional<com.ash.drishti.api.ArrowScan> scan(com.ash.drishti.api.ScanRequest request) {
        return com.ash.drishti.api.ColumnScans.over(this, request);
    }

    @Override
    public Set<String> columnar(String kind) {
        if (!promoted.containsKey(kind) || "effective".equals(modes.get(kind))) {
            return Set.of();                                   // effective tables need each entity's latest row: documents
        }
        try {
            return layout(kind, null).filter(l -> !l.deletionVectors()).map(l -> promotedColumns(kind, l).keySet()).orElse(Set.of());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    @Override
    public Set<String> vectorColumns(String kind) {
        List<String> v = vectorCols.get(kind);
        if (v == null || "effective".equals(modes.get(kind))) {
            return Set.of();
        }
        try {
            return layout(kind, null).map(l -> {
                Set<String> have = new java.util.LinkedHashSet<>();
                v.stream().filter(c -> l.schema().indexOf(c) >= 0).forEach(have::add);
                return have;
            }).orElse(Set.of());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    @Override
    public boolean vectors(String kind, String column, List<String> texts, AsOf asOf, com.ash.drishti.api.VectorSink sink) {
        return vectors(kind, column, texts, asOf, null, sink);
    }

    /**
     * The date's vectors for the keys of {@code range} (a VaR shard, VAR_SHARDING.md): a file whose statistics (the first key column's
     * {@code minValues} and {@code maxValues}, its {@code nullCount}) show none of them is not read; the reader checks every row.
     */
    @Override
    public boolean vectors(String kind, String column, List<String> texts, AsOf asOf, com.ash.drishti.api.KeyRange range,
            com.ash.drishti.api.VectorSink sink) {
        if (!vectorColumns(kind).contains(column)) {
            return false;
        }
        Optional<DeltaTable.Layout> l = readableLayout(kind, asOf.knownAt());
        if (l.isEmpty()) {
            return true;
        }
        Map<String, String> cols = promotedColumns(kind, l.get());
        List<String> physical = new ArrayList<>();
        for (String t : texts) {
            String c = cols.get(t);
            if (c == null) {
                return false;                                   // a text column the table does not keep: not answerable as columns
            }
            physical.add(c);
        }
        List<LocalDate> dates = candidates(kind, l.get(), asOf.businessDate());
        if (dates.isEmpty()) {
            return true;
        }
        String keyColumn = range == null || range.columns().isEmpty() ? null : cols.get(range.columns().get(0));
        java.util.function.Predicate<io.delta.kernel.data.Row> keep = keyColumn == null ? f -> true : f -> {
            String[] b = DeltaTable.textBounds(f, keyColumn);
            return range.mayHold(b[0], b[1], b[2] == null || !"0".equals(b[2]));
        };
        try {
            tables.get(kind).vectors(l.get(), dates.get(0), column, physical, keep, sink);
        } catch (RuntimeException e) {
            throw problems.failed(kind, dates.get(0).toString(), l.get().timestamp(), e);
        }
        problems.ok(kind, dates.get(0).toString());
        return true;
    }

    /** A document whose vector columns were kept out of it ({@code {"$vector": "PNL"}} markers) with the vectors put back. */
    private String withVectors(EntityRef ref, DeltaTable.Layout l, LocalDate d, String json) {
        List<String> cols = vectorCols.getOrDefault(ref.kind(), List.of()).stream().filter(c -> l.schema().indexOf(c) >= 0).toList();
        if (cols.isEmpty()) {
            return json;
        }
        Map<String, double[]> vecs = tables.get(ref.kind()).vectorsOf(l, d, fileOf(ref.kind(), l, d, ref.id()), ref.id(), cols);
        if (vecs.isEmpty()) {
            return json;
        }
        String out = json;
        for (Map.Entry<String, double[]> e : vecs.entrySet()) {
            StringBuilder a = new StringBuilder(e.getValue().length * 8).append('[');
            for (int i = 0; i < e.getValue().length; i++) {
                double x = e.getValue()[i];
                if (i > 0) {
                    a.append(',');
                }
                if (Double.isNaN(x) || Double.isInfinite(x)) {
                    a.append("null");
                } else if (x == Math.rint(x) && Math.abs(x) < 1e15) {
                    a.append((long) x);                         // whole currency units stay whole, as the batch wrote them
                } else {
                    a.append(x);
                }
            }
            // the marker as the lake tools write it (compact JSON), with or without a space after the colon
            out = out.replace("{\"$vector\":\"" + e.getKey() + "\"}", a.append(']').toString())
                    .replace("{\"$vector\": \"" + e.getKey() + "\"}", a.toString());
        }
        return out;
    }

    @Override
    public Optional<com.ash.drishti.api.ColumnSet> columns(String kind, java.util.Collection<String> paths, AsOf asOf) {
        Optional<DeltaTable.Layout> l = readableLayout(kind, asOf.knownAt());
        if (l.isEmpty() || l.get().deletionVectors()) {
            return Optional.empty();
        }
        Map<String, String> cols = promotedColumns(kind, l.get());
        if (!cols.keySet().containsAll(paths)) {
            return Optional.empty();
        }
        List<LocalDate> dates = candidates(kind, l.get(), asOf.businessDate());
        if (dates.isEmpty()) {
            return Optional.empty();                           // a date this table does not hold: another source may
        }
        com.ash.drishti.api.ColumnSet all;
        try {
            all = columnSet(kind, l.get(), dates.get(0), cols);
        } catch (RuntimeException e) {
            throw problems.failed(kind, dates.get(0).toString(), l.get().timestamp(), e);
        }
        problems.ok(kind, dates.get(0).toString());
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        for (String p : paths) {
            if (all.numbers().containsKey(p)) {
                nums.put(p, all.numbers().get(p));
            } else {
                texts.put(p, all.texts().get(p));
            }
        }
        return Optional.of(new com.ash.drishti.api.ColumnSet(all.ids(), nums, texts, all.businessDate()));
    }

    /** Opens the RocksDB document cache; null when it opened, else why it did not (it is a cache: reads go on without it). */
    private String openDiskDocs(SourceContext ctx) {
        java.nio.file.Path dir = java.nio.file.Path.of(ctx.setting("doc-disk-cache.dir", com.ash.drishti.api.DataDir.under("cache/" + sourceName + "/docs")));
        try {
            this.diskDocs = com.ash.drishti.diskcache.DiskCache.bounded(dir, (long) (Double.parseDouble(ctx.setting("doc-disk-cache.max-gb", "1")) * 1024 * 1024 * 1024),
                duration(ctx.setting("doc-disk-cache.ttl", "12h")), true,
                ctx.scheduler() == null ? null : java.time.LocalTime.parse(ctx.setting("doc-disk-cache.reset-at", "02:00")), java.time.ZoneId.of(ctx.setting("doc-disk-cache.zone", "America/New_York")),
                ctx.scheduler(), java.time.Clock.systemUTC());
            return null;
        } catch (IOException | RuntimeException | LinkageError e) {
            this.diskDocs = null;
            return e.toString();
        }
    }

    /** Every promoted column of one date, read once and kept (by memory), shared by searches and aggregates. */
    private com.ash.drishti.api.ColumnSet columnSet(String kind, DeltaTable.Layout l, LocalDate date, Map<String, String> cols) {
        return join(columnSets.get(key(kind, l, date), k -> {
            Map<String, Boolean> numeric = new LinkedHashMap<>();
            cols.forEach((path, column) -> numeric.put(path,
                    l.schema().get(column).getDataType() instanceof io.delta.kernel.types.DoubleType && !com.ash.drishti.api.IdText.isIdField(path)));
            return tables.get(kind).columns(l, date, cols, numeric);
        }));
    }

    @Override
    public java.util.Optional<com.ash.drishti.api.DatedHitIndex> typeAheadIndex() {
        return dated.enabled() ? java.util.Optional.of(dated) : java.util.Optional.empty();
    }

    @Override
    public Map<String, Object> cacheStats() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(Map.of("engine", lake.engineName(), "partitions", own(parts), "tables",
                latest.synchronous().estimatedSize(), "timeTravel", travelled.synchronous().estimatedSize(), "idMaps",
                own(idMaps), "columnSets", own(columnSets), "documents", docs.asMap().keySet().stream().filter(k -> k.startsWith(owner + "\u001f")).count()));
        if (dated.enabled()) {
            m.putAll(dated.health());
        }
        if (diskDocs != null) {
            m.put("docsOnDiskMB", diskDocs.sizeOnDisk() / (1024 * 1024));
            m.put("docsOnDiskHits", diskDocs.hits());
            m.put("docsOnDiskMisses", diskDocs.misses());
        }
        if (idMapFolder != null) {
            m.putAll(idMapFolder.stats());
        }
        return m;
    }

    /** This connector's entries in a cache that may be shared. */
    private long own(AsyncCache<PartKey, ?> c) {
        return c.synchronous().asMap().keySet().stream().filter(k -> k.owner().equals(owner)).count();
    }

    @Override
    public void purgeCaches() {
        if (idMapDir != null) {                                  // rebuilt from the lake at the next read, like everything else here
            try (var files = java.nio.file.Files.walk(idMapDir)) {
                for (java.nio.file.Path f : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if (!java.nio.file.Files.isDirectory(f)) {
                        com.ash.drishti.api.MappedIdFile.release(f, true);   // unmapped once its readers are done, then deleted
                        continue;
                    }
                    try {
                        java.nio.file.Files.deleteIfExists(f);
                    } catch (java.io.IOException e) {
                        // a file inside still waits for its readers: removed at the next sweep
                    }
                }
            } catch (java.io.IOException e) {
                // no folder yet
            }
        }
        if (diskDocs != null) {
            diskDocs.clear();
        }
        com.ash.drishti.deltalake.NativeEngine.purgeFooterCache();
        latest.synchronous().invalidateAll();
        travelled.synchronous().invalidateAll();
        // this connector's entries only: the caches may be shared with the other Delta connectors
        parts.synchronous().asMap().keySet().removeIf(k -> k.owner().equals(owner));
        idMaps.synchronous().asMap().keySet().removeIf(k -> k.owner().equals(owner));
        columnSets.synchronous().asMap().keySet().removeIf(k -> k.owner().equals(owner));
        docs.asMap().keySet().removeIf(k -> k.startsWith(owner + "\u001f"));
    }

    /** One date's day parts, id maps, column sets and documents of this connector go, in memory and its id-map files on disk (2.9.7). */
    @Override
    public boolean purgeDate(LocalDate date) {
        parts.synchronous().asMap().keySet().removeIf(k -> k.owner().equals(owner) && date.equals(k.date()));
        idMaps.synchronous().asMap().keySet().removeIf(k -> k.owner().equals(owner) && date.equals(k.date()));   // unmapped on removal
        columnSets.synchronous().asMap().keySet().removeIf(k -> k.owner().equals(owner) && date.equals(k.date()));
        docs.asMap().keySet().removeIf(k -> k.startsWith(owner + "\u001f") && k.contains(date.toString()));
        if (idMapDir != null) {
            try (var files = java.nio.file.Files.walk(idMapDir)) {
                for (java.nio.file.Path f : files.filter(p -> p.getFileName().toString().equals(date + ".idmap")).toList()) {
                    com.ash.drishti.api.MappedIdFile.release(f, true);
                }
            } catch (java.io.IOException | java.io.UncheckedIOException e) {
                // no folder yet
            }
        }
        return true;
    }

    /** A data-load signal merged into the next purge: the tables' newest versions are read again at the next read (layouts only). */
    @Override
    public void refreshVersions() {
        latest.synchronous().invalidateAll();
    }

    @Override
    public void close() {
        if (reindexTask != null) {
            reindexTask.cancel(false);
        }
        loaders.shutdownNow();               // loads in flight are abandoned; their callers get an error, not a hang
        try {
            // a background read still inside the disk cache keeps its store open (and locked) after the close below, until it leaves:
            // a moment for it to leave, so a restart in the same folder finds the cache free and holding what was read
            loaders.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (diskDocsRetry != null) {
            diskDocsRetry.cancel(false);
        }
        if (idMapFolder != null) {
            idMapFolder.close();
        }
        if (diskDocs != null) {
            diskDocs.close();
        }
        if (lake != null) {
            lake.close();                    // the native engine's S3 connections
        }
    }

    @Override
    public String health() {
        String engineName = "engine: " + lake.engineName();
        if (!lake.reachable()) {
            return "DOWN: cannot reach " + lake.describe() + " (" + engineName + ")";
        }
        String tlsNote = (lake.tls() == null ? "" : lake.tls().expiryNote(java.time.Instant.now()))
                + (lake.proxy().active() ? (lake.tls() == null ? "" : "; ") + lake.proxy().note() : "");
        if (tables.isEmpty()) {
            return "DOWN: no Delta tables under " + lake.describe() + " (" + engineName + ")";
        }
        // a pack declared a layout the table does not have: it works, but searches over it read documents
        List<String> notLaidOut = new ArrayList<>();
        promoted.forEach((kind, paths) -> {
            try {
                layout(kind, null).ifPresent(l -> {
                    int have = promotedColumns(kind, l).size();
                    if (have < paths.size()) {
                        notLaidOut.add(kind + " (" + have + " of " + paths.size() + " columns)");
                    }
                });
            } catch (RuntimeException e) {
                // reported by reads
            }
        });
        String laidOut = notLaidOut.isEmpty() ? "" : "; not laid out as the pack declares: " + String.join(", ", notLaidOut) + "; searches read documents";
        if (!problems.isEmpty()) {
            // it serves, but reads of these tables and dates failed the last time they were tried: say which, and why
            return "DEGRADED: cannot read " + problems.summary(5) + " (" + engineName + laidOut + ")";
        }
        String indexNote = dated.enabled() ? "; " + dated.summary() : "";
        return "UP (" + engineName + laidOut + indexNote + (tlsNote.isEmpty() ? "" : "; " + tlsNote) + ")";
    }
}
