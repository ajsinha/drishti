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
package com.ash.drishti.plugin.iceberg;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
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
import com.ash.drishti.plugin.iceberg.IcebergTable.Day;
import com.ash.drishti.plugin.iceberg.IcebergTable.IdMap;
import com.ash.drishti.plugin.iceberg.IcebergTable.Layout;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serves entities from Apache Iceberg tables, one table per kind ({@link IcebergLayout}): path-based tables at
 * {@code <root>/<domain>/<kind>} or tables in a REST catalog's namespace ({@link IcebergLake}), partitioned by
 * {@code business_date}, rows {@code (id, doc)} with the pack's promoted fields beside them.
 *
 * <p>A read for a business date takes the entity from the newest date on or before it: within {@code lookback-days}
 * for {@code snapshot} tables (every entity every date, the default; an entity absent that day is gone), or without
 * limit for {@code effective} tables (a row when the entity changes). {@code knownAt} reads the table's snapshot as of
 * that instant (Iceberg time travel), before later corrections.
 *
 * <p>Nothing reads a whole day of documents. A date's ids come from the id column alone (an id map, {@code id-map-mb},
 * 1024); one entity from the one file the id map names, read with {@code id = …} so Parquet skips every row group but
 * the one holding it ({@code doc-cache-mb}, 256, keeps recent documents; at most {@code max-concurrent-reads}, 16, at
 * once); searches, aggregates and reverse lookups from the promoted columns ({@code layout.<kind>.columns};
 * {@code columns-cache-mb}, 1024). Caches are keyed by a date's files, so a commit of a new day keeps every other day
 * cached. Each table's current snapshot is checked every {@code refresh-seconds} (10); a new one rebuilds type-ahead
 * from the newest day's ids and loads that day's columns in the background. Delete files are applied by every read.
 *
 * <p>Settings: {@code catalog} ({@code hadoop}|{@code rest}), {@code root} ({@code ./data/iceberg}), {@code domain},
 * {@code uri}, {@code warehouse}, {@code credential}, {@code token}, {@code namespace}, {@code kinds} (default: every
 * table found), {@code mode.<kind>}, {@code lookback-days} (10), {@code max-load-rows} (200000), {@code reverse-index}
 * (true), {@code cache-mb} (512), {@code source-name} ({@code iceberg}); object storage as {@link IcebergLake} says.
 */
public final class IcebergSourcePlugin implements SourcePlugin {

    private record SnapKey(String kind, long snapshotId) {}

    /** A business date's files of a kind: the key of everything read from them. */
    private record DayKey(String kind, LocalDate date, long fingerprint) {}

    /** A whole day of a small table, for reverse lookups without promoted columns: its ids and who refers to what. */
    private record Part(Set<String> ids, Map<String, Set<String>> refs) {}

    private final Map<String, IcebergTable> tables = new ConcurrentHashMap<>();
    private final Map<String, String> modes = new ConcurrentHashMap<>();
    private final Map<String, Long> current = new ConcurrentHashMap<>();
    private final Map<String, List<String>> promoted = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    // loads run on virtual threads and callers of the same key wait on its future without holding a lock
    private final ExecutorService loaders = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantLock refreshing = new ReentrantLock();
    private AsyncCache<SnapKey, Layout> layouts;
    private AsyncCache<DayKey, IdMap> idMaps;
    private AsyncCache<DayKey, ColumnSet> columnSets;
    private AsyncCache<DayKey, Part> parts;
    private Cache<String, String> docs;
    private Semaphore docReads;
    private IcebergLake lake;
    private SourceContext context;
    private String sourceName;
    private int lookbackDays;
    private int maxLoadRows;
    private boolean reverseIndex;
    /** True when no {@code kinds} are configured: every table in the domain is served, including new ones. */
    private boolean discover;
    private volatile Instant lastUpdate;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(sourceName == null ? "iceberg" : sourceName, "1.0", tables.keySet(), new SourceCapabilities(false, true, true, true));
    }

    /** A table's snapshots answer {@code knownAt}. */
    @Override
    public boolean timeTravel() {
        return true;
    }

    @Override
    public void start(SourceContext ctx) throws IOException {
        if (ctx.setting("root", "").isBlank() && ctx.setting("uri", "").isBlank()) {
            // installed but not configured (the plugin running as itself): idle, rather than DOWN on a lake nobody named
            throw new com.ash.drishti.api.PluginNotConfigured("iceberg needs settings.root (a folder or s3a://…) or settings.uri (a REST catalog)");
        }
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "iceberg");
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        this.maxLoadRows = Integer.parseInt(ctx.setting("max-load-rows", "200000"));
        this.reverseIndex = Boolean.parseBoolean(ctx.setting("reverse-index", "true"));
        this.lake = IcebergLake.of(ctx.settings());
        this.layouts = Caffeine.newBuilder().executor(loaders).maximumSize(Long.parseLong(ctx.setting("layout-cache", "64"))).buildAsync();
        this.idMaps = Caffeine.newBuilder().executor(loaders).maximumWeight(mb(ctx, "id-map-mb", "1024"))
                .weigher((DayKey k, IdMap v) -> (int) Math.min(Integer.MAX_VALUE, v.bytes())).buildAsync();
        this.columnSets = Caffeine.newBuilder().executor(loaders).maximumWeight(mb(ctx, "columns-cache-mb", "1024"))
                .weigher((DayKey k, ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, weight(v))).buildAsync();
        this.parts = Caffeine.newBuilder().executor(loaders).maximumWeight(mb(ctx, "cache-mb", "512"))
                .weigher((DayKey k, Part v) -> (int) Math.min(Integer.MAX_VALUE,
                        64L + 56L * v.ids().size() + v.refs().values().stream().mapToLong(s -> 64L + 40L * s.size()).sum()))
                .buildAsync();
        this.docs = Caffeine.newBuilder().maximumWeight(mb(ctx, "doc-cache-mb", "256"))
                .weigher((String k, String v) -> 2 * (k.length() + v.length()) + 64).build();
        this.docReads = new Semaphore(Integer.parseInt(ctx.setting("max-concurrent-reads", "16")));
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("layout.") && k.endsWith(".columns")) {
                promoted.put(k.substring(7, k.length() - 8), Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            }
        });
        String kinds = ctx.setting("kinds", "");
        this.discover = kinds.isBlank();
        for (String kind : kinds.split(",")) {
            if (!kind.isBlank()) {
                add(kind.trim());
            }
        }
        refresh();
        long every = Long.parseLong(ctx.setting("refresh-seconds", "10"));
        // the scheduler's tasks must be short: the refresh (metadata reads, an id map) runs on a virtual thread
        ctx.scheduler().scheduleWithFixedDelay(() -> loaders.execute(this::refresh), every, every, TimeUnit.SECONDS);
    }

    private static long mb(SourceContext ctx, String key, String fallback) {
        return Long.parseLong(ctx.setting(key, fallback)) * 1024 * 1024;
    }

    private void add(String kind) {
        modes.put(kind, context.setting("mode." + kind, "snapshot"));
        tables.putIfAbsent(kind, new IcebergTable(kind, lake, loaders));
    }

    /**
     * Checks every table's current snapshot (and, with no {@code kinds} configured, looks for new tables). When one
     * changed: type-ahead is rebuilt from the newest days' ids and the newest day's columns are loaded in the background.
     */
    void refresh() {
        if (!refreshing.tryLock()) {
            return;                                            // the previous refresh is still running
        }
        try {
            if (discover) {
                try {
                    lake.kinds().forEach(k -> {
                        if (!tables.containsKey(k)) {
                            add(k);                            // shows in /api/v1/sources and Admin → Health
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    // the lake is unreachable for now: health says so, and the next refresh tries again
                }
            }
            boolean changed = false;
            for (Map.Entry<String, IcebergTable> e : tables.entrySet()) {
                try {
                    Optional<Long> id = e.getValue().refresh();
                    if (id.isPresent() && !id.get().equals(current.get(e.getKey()))) {
                        current.put(e.getKey(), id.get());
                        changed = true;
                        Instant committed = Instant.ofEpochMilli(layout(e.getKey(), null).map(Layout::timestamp).orElse(0L));
                        Instant before = lastUpdate;
                        if (before == null || committed.isAfter(before)) {
                            lastUpdate = committed;
                        }
                    }
                } catch (RuntimeException ex) {
                    // unreachable or being rewritten: the last snapshot keeps serving, health reports reachability
                }
            }
            if (changed || index.size() == 0) {
                reindex();
            }
        } finally {
            refreshing.unlock();
        }
    }

    /** Type-ahead from ids alone: the newest day's (snapshot kinds) or every day's (effective kinds, which are small). */
    private void reindex() {
        List<EntityHit> hits = new ArrayList<>();
        for (String kind : tables.keySet()) {
            try {
                layout(kind, null).ifPresent(l -> {
                    if (l.days().isEmpty()) {
                        return;
                    }
                    String subtitle = kind + " · " + sourceName;
                    Collection<Day> days = effective(kind) ? l.days().values() : List.of(l.days().lastEntry().getValue());
                    Set<String> seen = new HashSet<>();
                    for (Day d : days) {
                        for (String id : idMap(kind, l, d).ids()) {
                            if (seen.add(id)) {
                                hits.add(new EntityHit(EntityRef.of(kind, id), id, subtitle));
                            }
                        }
                    }
                    Map<String, String> cols = promotedColumns(kind, l);
                    if (!cols.isEmpty() && !effective(kind)) {   // the newest day's columns, ready before the first search
                        loaders.execute(() -> {
                            try {
                                columnSet(kind, l, l.days().lastEntry().getValue(), cols);
                            } catch (RuntimeException e) {
                                // the first search loads them instead
                            }
                        });
                    }
                });
            } catch (RuntimeException e) {
                // a table being rewritten is indexed next time
            }
        }
        index.replaceAll(hits);
    }

    private boolean effective(String kind) {
        return "effective".equals(modes.get(kind));
    }

    private static long weight(ColumnSet c) {
        long w = 64L + 48L * c.size();
        for (String id : c.ids()) {
            w += id.length();
        }
        return w + 8L * c.size() * (c.numbers().size() + c.texts().size());   // texts share their values (books, desks)
    }

    /** The table's snapshot (the current one, or as known at {@code knownAt}) planned by business date. */
    private Optional<Layout> layout(String kind, Instant knownAt) {
        IcebergTable t = tables.get(kind);
        if (t == null) {
            return Optional.empty();
        }
        Optional<Long> snapshot;
        if (knownAt == null) {
            Long id = current.get(kind);
            snapshot = id != null ? Optional.of(id) : t.current();
        } else {
            snapshot = t.snapshotAt(knownAt);
        }
        return snapshot.map(id -> join(layouts.get(new SnapKey(kind, id), k -> t.layout(id))));
    }

    private IdMap idMap(String kind, Layout l, Day d) {
        return join(idMaps.get(new DayKey(kind, d.date(), d.fingerprint()), k -> tables.get(kind).ids(l, d)));
    }

    /** The promoted paths the table really has as columns (a pack may declare more than a writer wrote). */
    private Map<String, String> promotedColumns(String kind, Layout l) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String path : promoted.getOrDefault(kind, List.of())) {
            String column = IcebergLayout.column(path);
            if (l.schema().findField(column) != null) {
                out.put(path, column);
            }
        }
        return out;
    }

    /** Every promoted column of one date, read once and kept (by memory), shared by searches and aggregates. */
    private ColumnSet columnSet(String kind, Layout l, Day d, Map<String, String> cols) {
        return join(columnSets.get(new DayKey(kind, d.date(), d.fingerprint()), k -> tables.get(kind).columns(l, d, cols)));
    }

    private Part part(String kind, Layout l, Day d) {
        return join(parts.get(new DayKey(kind, d.date(), d.fingerprint()), k -> {
            Map<String, Set<String>> refs = new HashMap<>();
            Set<String> ids = new HashSet<>();
            tables.get(kind).read(l, d).forEach((id, json) -> {
                ids.add(id);
                ReferenceScanner.referencedIds(json).forEach(r -> refs.computeIfAbsent(r, x -> new HashSet<>()).add(id));
            });
            return new Part(ids, refs);
        }));
    }

    /** Waits for a load, rethrowing what it threw (not wrapped). */
    private static <T> T join(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    /** The days to look in for {@code date}, newest first: one for a snapshot kind, every earlier one for an effective kind. */
    private List<Day> candidates(String kind, Layout l, LocalDate date) {
        var head = date == null ? l.days().descendingMap() : l.days().headMap(date, true).descendingMap();
        List<Day> out = new ArrayList<>();
        for (Day d : head.values()) {
            if (!effective(kind) && date != null && d.date().isBefore(date.minusDays(lookbackDays))) {
                break;
            }
            out.add(d);
            if (!effective(kind)) {
                break;   // a snapshot table: the newest day on or before the date holds everything
            }
        }
        return out;
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws IOException {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws IOException {
        Optional<Layout> l = layout(ref.kind(), asOf.knownAt());
        if (l.isEmpty()) {
            return Optional.empty();
        }
        for (Day d : candidates(ref.kind(), l.get(), asOf.businessDate())) {
            // the id map names the file holding the entity; only that file's matching row group is read
            int file = idMap(ref.kind(), l.get(), d).file(ref.id());
            if (file < 0) {
                continue;
            }
            String key = ref.kind() + "\u001f" + d.fingerprint() + "\u001f" + d.date() + "\u001f" + ref.id();
            String json = docs.getIfPresent(key);
            if (json == null) {
                docReads.acquireUninterruptibly();
                try {
                    json = tables.get(ref.kind()).doc(l.get(), d, file, ref.id()).orElse(null);
                } finally {
                    docReads.release();
                }
                if (json != null) {
                    docs.put(key, json);
                }
            }
            if (json != null) {
                DataNode doc = context.parseJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                long generation = Optional.ofNullable(tables.get(ref.kind()).table().orElseThrow().snapshot(l.get().snapshotId()))
                        .map(s -> s.sequenceNumber()).orElse(0L);
                return Optional.of(new EntityDocument(ref, doc, new Provenance(sourceName, generation, Instant.now(), false,
                        LocalDate.MIN.equals(d.date()) ? null : d.date())));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    /**
     * Entities of {@code kind} (every kind when null) referring to {@code target} on the date: from the promoted text
     * columns when the table has them (no document is read), else from the documents of days of at most
     * {@code max-load-rows} rows. An effective kind is judged by each entity's latest row on or before the date.
     */
    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        if (!reverseIndex) {
            return List.of();
        }
        List<EntityRef> out = new ArrayList<>();
        for (String k : kind == null ? tables.keySet() : Set.of(kind)) {
            if (!tables.containsKey(k)) {
                continue;
            }
            layout(k, asOf.knownAt()).ifPresent(l -> {
                Map<String, String> cols = promotedColumns(k, l);
                Set<String> seen = new HashSet<>();
                Set<String> found = new TreeSet<>();
                for (Day d : candidates(k, l, asOf.businessDate())) {
                    if (!cols.isEmpty()) {
                        ColumnSet c = columnSet(k, l, d, cols);
                        for (int i = 0; i < c.size(); i++) {
                            if (!seen.add(c.ids()[i])) {
                                continue;                       // a newer row of this entity was already judged
                            }
                            for (String[] values : c.texts().values()) {
                                if (target.id().equals(values[i])) {
                                    found.add(c.ids()[i]);
                                    break;
                                }
                            }
                        }
                    } else if (d.rows() <= maxLoadRows) {
                        Part p = part(k, l, d);
                        Set<String> referring = p.refs().getOrDefault(target.id(), Set.of());
                        for (String id : p.ids()) {
                            if (seen.add(id) && referring.contains(id)) {
                                found.add(id);
                            }
                        }
                    }
                }
                found.forEach(id -> out.add(EntityRef.of(k, id)));
            });
        }
        return out;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public Instant lastUpdate() {
        return lastUpdate;
    }

    @Override
    public Set<String> columnar(String kind) {
        if (!promoted.containsKey(kind) || effective(kind)) {
            return Set.of();                                   // effective tables need each entity's latest row: documents
        }
        try {
            return layout(kind, null).map(l -> promotedColumns(kind, l).keySet()).orElse(Set.of());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    @Override
    public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
        if (effective(kind)) {
            return Optional.empty();
        }
        Optional<Layout> l = layout(kind, asOf.knownAt());
        if (l.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> cols = promotedColumns(kind, l.get());
        if (!cols.keySet().containsAll(paths)) {
            return Optional.empty();
        }
        List<Day> days = candidates(kind, l.get(), asOf.businessDate());
        if (days.isEmpty()) {
            return Optional.empty();                           // a date this table does not hold: another source may
        }
        ColumnSet all = columnSet(kind, l.get(), days.get(0), cols);
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        for (String p : paths) {
            if (all.numbers().containsKey(p)) {
                nums.put(p, all.numbers().get(p));
            } else {
                texts.put(p, all.texts().get(p));
            }
        }
        return Optional.of(new ColumnSet(all.ids(), nums, texts, all.businessDate()));
    }

    @Override
    public Map<String, Object> cacheStats() {
        long deletes = 0;
        for (String kind : tables.keySet()) {
            try {
                deletes += layout(kind, null).map(l -> l.days().values().stream().filter(Day::deletes).count()).orElse(0L);
            } catch (RuntimeException e) {
                // not readable now
            }
        }
        return Map.of("tables", tables.size(), "snapshots", layouts.synchronous().estimatedSize(), "idMaps", idMaps.synchronous().estimatedSize(),
                "columnSets", columnSets.synchronous().estimatedSize(), "documents", docs.estimatedSize(),
                "partitions", parts.synchronous().estimatedSize(), "daysWithDeletes", deletes, "ids", index.size());
    }

    @Override
    public void purgeCaches() {
        layouts.synchronous().invalidateAll();
        idMaps.synchronous().invalidateAll();
        columnSets.synchronous().invalidateAll();
        parts.synchronous().invalidateAll();
        docs.invalidateAll();
    }

    @Override
    public void close() {
        loaders.shutdownNow();               // loads in flight are abandoned; their callers get an error, not a hang
        try {
            lake.close();
        } catch (IOException e) {
            // closing a REST client: nothing to do
        }
    }

    @Override
    public String health() {
        if (!lake.reachable()) {
            return "DOWN: cannot reach " + lake.describe();
        }
        if (tables.isEmpty()) {
            return "DOWN: no Iceberg tables under " + lake.describe();
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
        String up = notLaidOut.isEmpty() ? "UP" : "UP (not laid out as the pack declares: " + String.join(", ", notLaidOut) + "; searches read documents)";
        return lake.tls() == null ? up : lake.tls().annotate(up, java.time.Instant.now());
    }
}
