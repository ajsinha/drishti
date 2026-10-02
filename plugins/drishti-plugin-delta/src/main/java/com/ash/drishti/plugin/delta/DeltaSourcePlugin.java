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
 * ({@code business_date}), {@code refresh-seconds} (10: how often a table's latest version is checked),
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

    private record PartKey(String kind, long version, LocalDate date) {}

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
    // Async caches: a load (the Delta log, a Parquet partition) runs on a virtual thread, and callers wanting the same
    // key wait on its future without holding any lock. A synchronous Caffeine load would run inside the map's
    // compute and pin the carrier thread for the whole read. One read still serves every caller of a key.
    private final java.util.concurrent.ExecutorService loaders = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private AsyncCache<String, DeltaTable.Layout> latest;
    private AsyncCache<String, DeltaTable.Layout> travelled;
    private AsyncCache<PartKey, Part> parts;
    private AsyncCache<PartKey, DeltaTable.IdMap> idMaps;
    private AsyncCache<PartKey, com.ash.drishti.api.ColumnSet> columnSets;
    private com.github.benmanes.caffeine.cache.Cache<String, String> docs;
    /** Kind to the document paths its pack promotes to columns ({@code layout.<kind>.columns}). */
    private final Map<String, List<String>> promoted = new java.util.concurrent.ConcurrentHashMap<>();
    private int maxLoadRows;
    /** Single-entity reads decode a row group each: bounded, so a burst of them cannot exhaust memory. */
    private java.util.concurrent.Semaphore docReads;
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

    /** A table's versions answer {@code knownAt}. */
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
        this.lake = LakeStore.of(ctx.setting("root", "./data/delta"), ctx.setting("domain", ""), ctx.settings());
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
        this.parts = Caffeine.newBuilder().executor(loaders).maximumWeight(Long.parseLong(ctx.setting("cache-mb", "512")) * 1024 * 1024)
                .weigher((PartKey k, Part v) -> (int) Math.min(Integer.MAX_VALUE,
                        64L + v.docs().values().stream().mapToLong(d -> 2L * d.length() + 64).sum() + 48L * v.refs().size()))
                .buildAsync();
        this.idMaps = Caffeine.newBuilder().executor(loaders).maximumWeight(Long.parseLong(ctx.setting("id-map-mb", "1024")) * 1024 * 1024)
                .weigher((PartKey k, DeltaTable.IdMap v) -> (int) Math.min(Integer.MAX_VALUE, v.bytes())).buildAsync();
        this.columnSets = Caffeine.newBuilder().executor(loaders).maximumWeight(Long.parseLong(ctx.setting("columns-cache-mb", "1024")) * 1024 * 1024)
                .weigher((PartKey k, com.ash.drishti.api.ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, weight(v))).buildAsync();
        this.docs = Caffeine.newBuilder().maximumWeight(Long.parseLong(ctx.setting("doc-cache-mb", "256")) * 1024 * 1024)
                .weigher((String k, String v) -> 2 * (k.length() + v.length()) + 64).build();
        this.maxLoadRows = Integer.parseInt(ctx.setting("max-load-rows", "200000"));
        this.docReads = new java.util.concurrent.Semaphore(Integer.parseInt(ctx.setting("max-concurrent-reads", "16")));
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("layout.") && k.endsWith(".columns")) {
                promoted.put(k.substring(7, k.length() - 8), java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            }
        });
        reindex();
        ctx.scheduler().scheduleWithFixedDelay(this::reindex, refresh * 6, refresh * 6, TimeUnit.SECONDS);
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
        return join(idMaps.get(new PartKey(kind, l.version(), date), k -> tables.get(kind).ids(l, date)));
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
        return join(parts.get(new PartKey(kind, l.version(), date), k -> {
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
                throw problems.failed(ref.kind(), d.toString(), l.get().version(), e);
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

    /** The entity's document on one date, or null when that date's files do not hold it. */
    private String doc(EntityRef ref, DeltaTable.Layout l, LocalDate d) {
        // the id map says which file holds the entity; only that file's matching row group is read
        int file = idMap(ref.kind(), l, d).file(ref.id());
        if (file < 0) {
            return null;
        }
        String key = ref.kind() + "\u001f" + l.version() + "\u001f" + d + "\u001f" + ref.id();
        String json = docs.getIfPresent(key);
        if (json == null) {
            docReads.acquireUninterruptibly();
            try {
                json = tables.get(ref.kind()).doc(l, d, file, ref.id()).orElse(null);
            } finally {
                docReads.release();
            }
            if (json != null) {
                docs.put(key, json);
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
                    } else if (idMap(k, l, d).ids().length <= maxLoadRows) {
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

    /** The newest commit time among the domain's tables (data loaded, appended or restated). */
    private volatile java.time.Instant lastUpdate;
    private final Map<String, Long> versions = new java.util.concurrent.ConcurrentHashMap<>();

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
                version = layout.get().version();
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
        Long was = versions.put(kind, l.version());
        if (was == null || was != l.version()) {
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
            throw problems.failed(kind, dates.get(0).toString(), l.get().version(), e);
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

    /** Every promoted column of one date, read once and kept (by memory), shared by searches and aggregates. */
    private com.ash.drishti.api.ColumnSet columnSet(String kind, DeltaTable.Layout l, LocalDate date, Map<String, String> cols) {
        return join(columnSets.get(new PartKey(kind, l.version(), date), k -> {
            Map<String, Boolean> numeric = new LinkedHashMap<>();
            cols.forEach((path, column) -> numeric.put(path,
                    l.schema().get(column).getDataType() instanceof io.delta.kernel.types.DoubleType));
            return tables.get(kind).columns(l, date, cols, numeric);
        }));
    }

    @Override
    public Map<String, Object> cacheStats() {
        return Map.of("engine", lake.engineName(), "partitions", parts.synchronous().estimatedSize(), "tables",
                latest.synchronous().estimatedSize(), "timeTravel", travelled.synchronous().estimatedSize(), "idMaps",
                idMaps.synchronous().estimatedSize(), "columnSets", columnSets.synchronous().estimatedSize(), "documents", docs.estimatedSize());
    }

    @Override
    public void purgeCaches() {
        parts.synchronous().invalidateAll();
        latest.synchronous().invalidateAll();
        travelled.synchronous().invalidateAll();
        idMaps.synchronous().invalidateAll();
        columnSets.synchronous().invalidateAll();
        docs.invalidateAll();
    }

    @Override
    public void close() {
        loaders.shutdownNow();               // loads in flight are abandoned; their callers get an error, not a hang
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
        return "UP (" + engineName + laidOut + ")";
    }
}
