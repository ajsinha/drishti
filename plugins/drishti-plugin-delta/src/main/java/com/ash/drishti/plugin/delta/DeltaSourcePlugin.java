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
 * {@code max-concurrent-reads} (16) single-entity reads run at once (each decodes one row group of documents).
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
                .weigher((PartKey k, DeltaTable.IdMap v) -> (int) Math.min(Integer.MAX_VALUE, v.bytes())).buildAsync();
        static final AsyncCache<PartKey, com.ash.drishti.api.ColumnSet> COLUMNS = Caffeine.newBuilder().executor(LOADERS).maximumWeight(HEAP / 5)
                .weigher((PartKey k, com.ash.drishti.api.ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, weight(v))).buildAsync();
        static final com.github.benmanes.caffeine.cache.Cache<String, String> DOCS = Caffeine.newBuilder().maximumWeight(HEAP / 20)
                .weigher((String k, String v) -> 2 * (k.length() + v.length()) + 64).build();
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
    /** Documents read before, on disk (doc-disk-cache.enabled), or null. */
    private volatile com.ash.drishti.diskcache.DiskCache diskDocs;
    private volatile java.util.concurrent.ScheduledFuture<?> diskDocsRetry;
    /** Seconds between attempts to open a document cache found locked (tests shorten it). */
    static long diskRetrySeconds = 30;
    /** Kind to the document paths its pack promotes to columns ({@code layout.<kind>.columns}). */
    private final Map<String, List<String>> promoted = new java.util.concurrent.ConcurrentHashMap<>();
    private int maxLoadRows;
    /** Single-entity reads decode a row group each: bounded, so a burst of them cannot exhaust memory. */
    private java.util.concurrent.Semaphore docReads;
    /** Single reads of background work (the start-up warm-up): apart from {@link #docReads}, so a page never waits behind them. */
    private final java.util.concurrent.Semaphore warmReads = new java.util.concurrent.Semaphore(2);
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
                        .weigher((PartKey k, DeltaTable.IdMap v) -> (int) Math.min(Integer.MAX_VALUE, v.bytes())).buildAsync();
        this.columnSets = !set.containsKey("columns-cache-mb") ? Shared.COLUMNS
                : Caffeine.newBuilder().executor(loaders).maximumWeight(Long.parseLong(set.get("columns-cache-mb")) * 1024 * 1024)
                        .weigher((PartKey k, com.ash.drishti.api.ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, weight(v))).buildAsync();
        if (Boolean.parseBoolean(ctx.setting("id-map-disk.enabled", "true"))) {
            // each table date's id map written once per table version, then memory-mapped: no heap per id, kept across restarts
            idMapDir = java.nio.file.Path.of(ctx.setting("id-map-disk.dir", com.ash.drishti.api.DataDir.under("cache/" + sourceName + "/idmaps")));
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
        this.docReads = new java.util.concurrent.Semaphore(Integer.parseInt(ctx.setting("max-concurrent-reads", "16")));
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("layout.") && k.endsWith(".columns")) {
                promoted.put(k.substring(7, k.length() - 8), java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            }
        });
        this.dated = new DatedHitIndex(sourceName, DatedHitIndex.defaultRoot(), ctx.settings(), this::dayHits, (k, d) -> {
            String v = versions.get(k);
            return v == null ? null : "v" + v;
        });
        reindex();
        ctx.scheduler().scheduleWithFixedDelay(this::reindex, refresh * 6, refresh * 6, TimeUnit.SECONDS);
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
        return join(idMaps.get(key(kind, l, date), k -> {
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
                        pruneOtherVersions(file.getParent());
                    } catch (java.io.IOException | RuntimeException e) {
                        LOG.debug("{}: id map of {} {} kept in the heap: {}", sourceName, kind, date, e.toString());
                    }
                }
            }
            // one line per large table and date, when it is first read (the rest at DEBUG): the Parquet reader's per-record lines are off (application.yaml)
            LOG.atLevel(m.size() >= ANNOUNCE_IDS ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.DEBUG).log("{}: {} {} loaded ({}), {} ids in {} files ({} ms)", sourceName, kind, date == LocalDate.MIN ? "(no dates)" : date,
                    how, String.format("%,d", m.size()), l.files().getOrDefault(date, List.of()).size(), (System.nanoTime() - started) / 1_000_000);
            return m;
        }));
    }

    /** A kind's name as a folder name. */
    private static String safeName(String kind) {
        return kind.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** The id map files of other versions of the table go (a new load made them unused); one still mapped stays until the next try. */
    private static void pruneOtherVersions(java.nio.file.Path versionDir) {
        try (var dirs = java.nio.file.Files.list(versionDir.getParent())) {
            for (java.nio.file.Path other : dirs.filter(d -> !d.equals(versionDir)).toList()) {
                try (var files = java.nio.file.Files.walk(other)) {
                    for (java.nio.file.Path f : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        java.nio.file.Files.deleteIfExists(f);
                    }
                } catch (java.io.IOException e) {
                    // mapped on Windows, or being read: removed at a later load
                }
            }
        } catch (java.io.IOException e) {
            // nothing to prune
        }
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
            DataNode doc = context.parseJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            return Optional.of(new EntityDocument(ref, doc, new Provenance(sourceName, l.get().version(), Instant.now(), false,
                    d == LocalDate.MIN ? null : d)));
        }
        return Optional.empty();
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
    private String doc(EntityRef ref, DeltaTable.Layout l, LocalDate d, java.util.concurrent.Semaphore slots) {
        // the id map says which file holds the entity; only that file's matching row group is read
        int file = idMap(ref.kind(), l, d).file(ref.id());
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
            slots.acquireUninterruptibly();
            try {
                json = tables.get(ref.kind()).doc(l, d, file, ref.id()).orElse(null);
            } finally {
                slots.release();
            }
            if (json != null) {
                docs.put(heapKey, json);
                if (diskDocs != null) {
                    diskDocs.put(key, json.getBytes(StandardCharsets.UTF_8));
                }
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
            String subtitle = kind + " · " + sourceName;
            for (String id : idMap(kind, l.get(), day).ids()) {
                out.add(new EntityHit(EntityRef.of(kind, id), id, subtitle));
            }
        }
        return out;
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
            String subtitle = kind + " · " + sourceName;
            for (String id : idMap(kind, l, l.files().lastKey()).ids()) {
                hits.add(new EntityHit(EntityRef.of(kind, id), id, subtitle));
            }
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
                    try {
                        java.nio.file.Files.deleteIfExists(f);
                    } catch (java.io.IOException e) {
                        // still mapped (Windows): replaced when its table date is read again
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

    @Override
    public void close() {
        loaders.shutdownNow();               // loads in flight are abandoned; their callers get an error, not a hang
        if (diskDocsRetry != null) {
            diskDocsRetry.cancel(false);
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
