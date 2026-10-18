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
package com.ash.drishti.engine.source.derived;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityReader;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Derived kinds (plugin {@code derived}): entities computed from other kinds, read through the server's routing, such as
 * a book's P&amp;L from its trades or a currency's exposure. A pack declares them as a connector (see {@link DerivedKind}
 * for the settings). Each business date is computed once per {@code refresh} (default 30 s) and shared by every
 * reader; a picked date is computed from that date's members, so derived kinds have history wherever their members do.
 */
public final class DerivedSourcePlugin implements SourcePlugin {

    /** Members read as documents at a time (a group key that is an expression): memory holds one batch, not the kind. */
    private static final int READ_BATCH = 500;
    /** Members read between two progress lines in the log. */
    private static final int PROGRESS_EVERY = 100_000;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DerivedSourcePlugin.class);

    /**
     * One computation: every group of every derived kind, for one business date; {@code version} the version of the data it
     * was built from (null when a source cannot say), {@code at} when it was built or its version last found unchanged.
     */
    private record Snapshot(Pages pages, Map<String, Integer> scanned, LocalDate dataDate, long generation,
            Instant at, String version) {
        Snapshot checked(Instant now) {
            return new Snapshot(pages, scanned, dataDate, generation, now, version);
        }
    }

    /** The pages of one computation: in the heap (built documents) or on disk ({@link DiskPages}). */
    interface Pages {
        DataNode page(String kind, String id);

        java.util.Set<String> ids(String kind);

        default void close() {}
    }

    /** Pages built as documents and held in the heap (store: heap, the default). */
    record HeapPages(Map<String, Map<String, DataNode>> byKind) implements Pages {
        public DataNode page(String kind, String id) {
            return byKind.getOrDefault(kind, Map.of()).get(id);
        }

        public java.util.Set<String> ids(String kind) {
            return byKind.getOrDefault(kind, Map.of()).keySet();
        }
    }

    private static final int CACHED_DATES = 16;
    private List<DerivedKind> kinds = List.of();
    private EntityReader reader;
    private Duration refresh = Duration.ofSeconds(30);
    private int maxScan = 50_000;
    /** Most members read in one pass (sources that read a whole day at once): a guard, not a sample size. */
    private int maxStream = 100_000;
    /** How often the data's version is asked when the sources can say it (a load rebuilds; nothing else does). */
    private Duration versionCheck = Duration.ofSeconds(10);
    private String sourceName = "derived";
    private final AtomicLong generation = new AtomicLong();
    /** store: disk keeps each computation's pages in a sorted store under {@link #storeRoot}; the heap holds per group totals only. */
    private boolean onDisk;
    /** The zone whose calendar date {@code today()} is for the latest pages. */
    private java.time.ZoneId zone = java.time.ZoneId.of("America/New_York");
    private java.nio.file.Path storeRoot;
    /** How long retired pages on disk are kept for reads still running. */
    private Duration retireAfter = Duration.ofSeconds(60);
    private final Map<String, CompletableFuture<Snapshot>> cache = new LinkedHashMap<>(CACHED_DATES, 0.75f, true);
    private volatile String health = "UP (nothing computed yet)";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("derived", "1.0", kinds.stream().map(DerivedKind::kind).collect(Collectors.toSet()),
                new SourceCapabilities(false, false, true, true));
    }

    /**
     * A derived kind is computed from the kinds it groups, read through the router with the same {@code knownAt}: their
     * stores answer it or refuse it themselves.
     */
    @Override
    public boolean timeTravel() {
        return true;
    }

    @Override
    public void start(SourceContext context) {
        kinds = DerivedKind.parse(context.settings(), new ElCompiler(), Formats.defaults());
        if (kinds.isEmpty()) {
            throw new PluginNotConfigured("derived needs at least one <kind>.from setting");
        }
        reader = context.reader();
        refresh = org.springframework.boot.convert.DurationStyle.detectAndParse(context.setting("refresh", "30s"));
        maxScan = Integer.parseInt(context.setting("max-scan", "50000"));
        maxStream = Integer.parseInt(context.setting("max-stream", "100000"));
        versionCheck = org.springframework.boot.convert.DurationStyle.detectAndParse(context.setting("version-check", "10s"));
        sourceName = context.setting("source-name", "derived");
        onDisk = "disk".equalsIgnoreCase(context.setting("store", "heap"));
        zone = java.time.ZoneId.of(context.setting("zone", "America/New_York"));
        retireAfter = org.springframework.boot.convert.DurationStyle.detectAndParse(context.setting("retire-after", "60s"));
        if (onDisk) {
            // the pages' only folder: <data dir>/derived/<connector>; what a previous run left there is removed
            storeRoot = java.nio.file.Path.of(context.setting("store-dir", com.ash.drishti.api.DataDir.under("derived/" + sourceName))).toAbsolutePath();
            try {
                com.ash.drishti.diskcache.SortedStore.deleteFolder(storeRoot);
                java.nio.file.Files.createDirectories(storeRoot);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("derived pages " + sourceName + ": cannot use " + storeRoot + ": " + e.getMessage(), e);
            }
        }
        // the first computation after start, so the first reader of a large book finds it ready
        if (context.scheduler() == null) {
            return;
        }
        context.scheduler().schedule(() -> {
            try {
                snapshot(AsOf.LATEST);
            } catch (RuntimeException e) {
                // the first reader computes it
            }
        }, 15, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        Snapshot s = snapshot(asOf);
        DataNode doc = s.pages().page(ref.kind(), ref.id());
        return doc == null ? Optional.empty()
                : Optional.of(new EntityDocument(ref, doc, new Provenance(sourceName, s.generation(), s.at(), false, s.dataDate())));
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return search(kind, text, limit, AsOf.LATEST);
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
        DerivedKind k = kinds.stream().filter(d -> d.kind().equals(kind)).findFirst().orElse(null);
        if (k == null) {
            return List.of();
        }
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return snapshot(asOf).pages().ids(kind).stream().sorted().filter(id -> id.toLowerCase(Locale.ROOT).contains(t)).limit(limit)
                .map(id -> new EntityHit(EntityRef.of(kind, id), id, kind + " · from " + k.from())).toList();
    }

    /** What this plugin's own definition says a field of one of its derived kinds means, or empty (not its kind, or not a field of it). */
    @Override
    public Optional<FieldNote> describeField(String kind, String field) {
        return kinds.stream().filter(d -> d.kind().equals(kind)).findFirst().flatMap(d -> d.describe(field));
    }

    @Override
    public Map<String, Object> cacheStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("store", onDisk ? "disk" : "heap");
        synchronized (cache) {   // pinning-ok: join() is only called on futures that are already done
            out.put("dates", cache.size());
            long pages = 0;
            long bytes = 0;
            for (CompletableFuture<Snapshot> f : cache.values()) {
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    Pages p = f.join().pages();
                    for (DerivedKind k : kinds) {
                        pages += p.ids(k.kind()).size();
                    }
                    if (p instanceof DiskPages d) {
                        bytes += d.sizeOnDisk();
                    }
                }
            }
            out.put("pages", pages);
            if (onDisk) {
                out.put("diskMB", bytes / (1024 * 1024));
                out.put("folder", String.valueOf(storeRoot));
            }
        }
        if (!"UP".equals(health)) {
            out.put("health", health);
        }
        return out;
    }

    @Override
    public boolean dependsOn(String kind) {
        return kinds.stream().anyMatch(k -> k.from().equals(kind));
    }

    /**
     * Drops every computation (on disk: their folders go). With pages on disk, the latest date is built again from the source
     * at once, in the background: Admin -> Caches -> Purge, or {@code drishti.py server caches purge}, rebuilds from scratch.
     */
    @Override
    public void purgeCaches() {
        synchronized (cache) {
            cache.values().forEach(f -> retire(f, null));
            cache.clear();
        }
        if (onDisk) {
            Thread.ofVirtual().name("derived-rebuild").start(() -> {
                try {
                    snapshot(AsOf.LATEST);
                } catch (RuntimeException e) {
                    // said in health and the log by the computation; the next reader tries again
                }
            });
        }
    }

    @Override
    public String health() {
        return health;
    }

    /**
     * The computation for this date: shared while it runs, reused while its data is unchanged. When the sources can say
     * their data's version, it is asked at most every {@code version-check} and a new version (a load) rebuilds; when they
     * cannot, the computation is rebuilt once it is {@code refresh} old.
     */
    private Snapshot snapshot(AsOf asOf) {
        String key = asOf.live() ? "live" : asOf.businessDate() + "@" + asOf.knownAt();
        Boolean changed = null;                               // null: not asked, or the sources cannot say now
        boolean asked = false;
        synchronized (cache) {
            CompletableFuture<Snapshot> f0 = cache.get(key);
            if (f0 != null && f0.isDone() && !f0.isCompletedExceptionally()) {
                Snapshot s0 = f0.join();
                if (s0.version() != null && s0.at().plus(versionCheck).isBefore(Instant.now())) {
                    asked = true;                               // asked below, outside the lock
                }
            }
        }
        if (asked) {
            Optional<String> now = version(asOf);
            changed = now.isEmpty() ? null : !now.get().equals(currentVersion(key));
        }
        CompletableFuture<Snapshot> f;
        boolean mine = false;
        synchronized (cache) {   // pinning-ok: join() is only called on futures that are already done
            f = cache.get(key);
            boolean done = f != null && f.isDone() && !f.isCompletedExceptionally();
            if (done && Boolean.FALSE.equals(changed)) {
                Snapshot same = f.join().checked(Instant.now());          // unchanged: asked again after version-check
                put(key, CompletableFuture.completedFuture(same));
                return same;
            }
            boolean timer = done && f.join().at().plus(refresh).isBefore(Instant.now());
            // a version the sources can say decides (a load rebuilds); without one, the refresh timer does
            boolean stale = done && (f.join().version() == null ? timer : Boolean.TRUE.equals(changed) || asked && changed == null && timer);
            if (stale) {
                // serve the last computation now and refresh it behind: a reader never waits for a recomputation
                Snapshot last = f.join();
                CompletableFuture<Snapshot> next = new CompletableFuture<>();
                put(key, CompletableFuture.completedFuture(last.checked(Instant.now())));   // one refresh at a time
                Thread.ofVirtual().name("derived-refresh").start(() -> {
                    try {
                        next.complete(compute(asOf, false));
                        synchronized (cache) {
                            put(key, next);
                        }
                    } catch (RuntimeException | OutOfMemoryError e) {
                        // the last computation stays in use; out of memory is said, not left to kill the thread silently
                        health = (e instanceof OutOfMemoryError ? "DEGRADED: " : "DOWN: ") + e;
                        LOG.warn("derived pages for {} could not be refreshed: {}", asOf, e.toString());
                    }
                });
                return last;
            }
            if (f == null || f.isCompletedExceptionally()) {
                f = new CompletableFuture<>();
                put(key, f);
                mine = true;
                while (cache.size() > CACHED_DATES) {
                    retire(cache.remove(cache.keySet().iterator().next()), null);
                }
            }
        }
        if (mine) {
            try {
                f.complete(compute(asOf, true));
            } catch (RuntimeException e) {
                health = "DOWN: " + e.getMessage();
                LOG.warn("derived pages for {} could not be built: {}", asOf, e.toString());
                f.completeExceptionally(e);
            } catch (OutOfMemoryError e) {
                // never leave the computation pending: every later reader would wait on it forever
                // DEGRADED, not DOWN: DOWN makes the registry restart the connector, which would run out of memory again
                health = "DEGRADED: out of memory building the pages (lower max-stream, or give the server more heap)";
                LOG.error("derived pages {} for {} ran out of memory: lower its max-stream setting or raise -Xmx", sourceName, asOf);
                f.completeExceptionally(new IllegalStateException(health, e));
            } catch (Error e) {
                health = "DOWN: " + e;
                f.completeExceptionally(e);
                throw e;
            }
        }
        try {
            return f.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : e;
        }
    }

    /** Puts a computation in the cache; one it replaces is retired (its pages on disk deleted) unless the new one shares them. Under the cache lock. */
    private void put(String key, CompletableFuture<Snapshot> f) {
        CompletableFuture<Snapshot> old = cache.put(key, f);
        retire(old, f);
    }

    /** Retires a computation no longer cached: its pages on disk go, a minute later (a read may still be using them). */
    private void retire(CompletableFuture<Snapshot> old, CompletableFuture<Snapshot> by) {
        if (old == null || !old.isDone() || old.isCompletedExceptionally()) {
            return;
        }
        Pages gone = old.join().pages();
        if (by != null && by.isDone() && !by.isCompletedExceptionally() && by.join().pages() == gone) {
            return;                                           // the same pages, checked again
        }
        if (gone instanceof DiskPages) {
            Thread.ofVirtual().name("derived-retire").start(() -> {
                try {
                    Thread.sleep(retireAfter.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                gone.close();
            });
        }
    }

    /** What {@code today()} is in the pages' expressions: the business date asked for, or the calendar date for the latest. */
    private LocalDate todayOf(AsOf asOf) {
        return asOf.live() || asOf.businessDate() == null ? LocalDate.now(zone) : asOf.businessDate();
    }

    /** The version of the snapshot cached under {@code key}, or null. */
    private String currentVersion(String key) {
        synchronized (cache) {   // pinning-ok: join() is only called on futures that are already done
            CompletableFuture<Snapshot> f = cache.get(key);
            return f != null && f.isDone() && !f.isCompletedExceptionally() ? f.join().version() : null;
        }
    }

    /** The version of every kind the derived kinds are built from, on the date; empty when a source cannot say. */
    private Optional<String> version(AsOf asOf) {
        StringBuilder out = new StringBuilder();
        for (String from : kinds.stream().map(DerivedKind::from).distinct().sorted().toList()) {
            Optional<String> v;
            try {
                v = reader.dataVersion(from, asOf);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
            if (v.isEmpty()) {
                return Optional.empty();
            }
            out.append(from).append(':').append(v.get()).append(';');
        }
        return Optional.of(out.toString());
    }

    /** Every group of every derived kind for the date; {@code announce}: the first computation of the date says its progress at INFO (a refresh at DEBUG). */
    private Snapshot compute(AsOf asOf, boolean announce) {
        long started = System.nanoTime();
        String version = version(asOf).orElse(null);          // before reading: a load during the read makes the next look rebuild
        if (onDisk) {
            Snapshot d = computeOnDisk(asOf, announce, version, started);
            if (d != null) {
                return d;
            }
        }
        List<String> partial = new ArrayList<>();
        Map<String, Map<String, DataNode>> byKind = new HashMap<>();
        Map<String, Integer> scanned = new HashMap<>();
        LocalDate dataDate = null;
        // the kinds that need their members' documents, by source kind: read once, in batches, each member kept as only what it needs
        Map<String, List<DerivedKind>> byDocs = new java.util.LinkedHashMap<>();
        for (DerivedKind k : kinds) {
            Optional<java.util.Set<String>> paths = k.paths();
            Optional<com.ash.drishti.api.ColumnSet> cols = paths.isEmpty() ? Optional.empty() : reader.columns(k.from(), paths.get(), asOf);
            if (cols.isPresent()) {                       // every member, from columns: no document is read
                com.ash.drishti.api.ColumnSet c = cols.get();
                byKind.put(k.kind(), k.buildAll(c, k.groupBySource()));
                scanned.put(k.kind(), c.size());
                if (c.businessDate() != null && (dataDate == null || c.businessDate().isAfter(dataDate))) {
                    dataDate = c.businessDate();
                }
                continue;
            }
            byDocs.computeIfAbsent(k.from(), x -> new ArrayList<>()).add(k);
        }
        for (Map.Entry<String, List<DerivedKind>> e : byDocs.entrySet()) {
            Members one = new Members(e.getValue(), todayOf(asOf));
            if (streamed(e.getKey(), asOf, one, announce)) {           // one pass over the sources' files
                one.into(byKind, scanned);
                if (one.partial != null) {
                    partial.add(one.partial);
                }
                if (one.dataDate != null && (dataDate == null || one.dataDate.isAfter(dataDate))) {
                    dataDate = one.dataDate;
                }
                continue;
            }
            List<EntityRef> refs = reader.list(e.getKey(), asOf, maxScan);
            List<String> names = e.getValue().stream().map(DerivedKind::kind).toList();
            say(announce, "derived pages {} ({}): reading {} {} documents", sourceName, asOf.live() ? "latest" : asOf.businessDate(),
                    String.format("%,d", refs.size()), e.getKey());
            if (refs.size() >= maxScan) {
                LOG.warn("derived pages {}: {} has more than {} documents; {} are built from the first {} only (raise the connector's max-scan setting)",
                        sourceName, e.getKey(), String.format("%,d", maxScan), names, String.format("%,d", maxScan));
            }
            Map<DerivedKind, Map<String, Map<String, DataNode>>> groups = new HashMap<>();
            e.getValue().forEach(k -> {
                groups.put(k, new HashMap<>());
            });
            int n = 0;
            for (int from = 0; from < refs.size(); from += READ_BATCH) {
                Map<EntityRef, EntityDocument> batch = reader.read(refs.subList(from, Math.min(refs.size(), from + READ_BATCH)), asOf);
                for (EntityDocument d : batch.values()) {
                    if (++n % PROGRESS_EVERY == 0) {
                        say(announce, "derived pages {}: {} of {} {} documents read", sourceName, String.format("%,d", n),
                                String.format("%,d", refs.size()), e.getKey());
                    }
                    for (DerivedKind k : e.getValue()) {
                        String g = k.keyOf(d.data());
                        if (g != null) {
                            groups.get(k).computeIfAbsent(g, x -> new HashMap<>()).put(d.ref().id(), k.compact(d.data(), todayOf(asOf)));
                        }
                    }
                    LocalDate dd = d.provenance().businessDate();
                    if (dd != null && (dataDate == null || dd.isAfter(dataDate))) {
                        dataDate = dd;
                    }
                }
            }
            for (DerivedKind k : e.getValue()) {
                Map<String, DataNode> built = new HashMap<>();
                groups.get(k).forEach((g, m) -> built.put(g, k.buildCompact(g, m)));
                byKind.put(k.kind(), built);
                scanned.put(k.kind(), n);
            }
        }
        // a cut build is said where an administrator looks (Admin -> Health), not only in the log
        health = partial.isEmpty() ? "UP" : "DEGRADED: partial: " + String.join("; ", partial);
        say(announce, "derived pages {} ({}) built in {} s: {}", sourceName, asOf.live() ? "latest" : asOf.businessDate(),
                String.format("%.1f", (System.nanoTime() - started) / 1e9), byKind.entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue().size() + " pages from " + String.format("%,d", scanned.getOrDefault(e.getKey(), 0)))
                        .sorted().collect(Collectors.joining(", ")));
        return new Snapshot(new HeapPages(byKind), scanned, asOf.live() ? null : dataDate, generation.incrementAndGet(), Instant.now(), version);
    }

    private static final byte[] NOTHING = new byte[0];
    private static final char SEP = '\u0001';

    /**
     * Every group of every derived kind for the date, kept on disk (store: disk): one pass over each source kind's documents;
     * each member's key under its group, each member's row once (kinds with the same columns share it); the heap keeps each
     * group's totals only, so it does not grow with the members. Null when a source cannot read a day in one pass (then the
     * pages are built in the heap).
     */
    private Snapshot computeOnDisk(AsOf asOf, boolean announce, String version, long started) {
        String date = asOf.live() ? "latest" : String.valueOf(asOf.businessDate());
        LocalDate today = todayOf(asOf);
        java.nio.file.Path dir = storeRoot.resolve((asOf.live() ? "live" : date + "-" + (asOf.knownAt() == null ? "now" : asOf.knownAt().toEpochMilli()))
                + "-" + System.nanoTime());
        com.ash.drishti.diskcache.SortedStore store;
        try {
            store = com.ash.drishti.diskcache.SortedStore.create(dir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("derived pages " + sourceName + ": " + e.getMessage(), e);
        }
        Map<String, String> rowKeys = new HashMap<>();       // row signature -> a short name in keys
        Map<String, Map<String, DerivedKind.Totals>> totals = new HashMap<>();
        Map<String, DerivedKind> byName = new HashMap<>();
        kinds.forEach(k -> {
            totals.put(k.kind(), new HashMap<>());
            byName.put(k.kind(), k);
            rowKeys.computeIfAbsent(k.signature(), x -> "s" + rowKeys.size());
        });
        Map<String, Integer> scanned = new HashMap<>();
        LocalDate[] dataDate = {null};
        try {
            for (Map.Entry<String, List<DerivedKind>> e : kinds.stream().collect(Collectors.groupingBy(DerivedKind::from, java.util.LinkedHashMap::new,
                    Collectors.toList())).entrySet()) {
                String from = e.getKey();
                List<DerivedKind> ks = e.getValue();
                long[] n = {0};
                say(announce, "derived pages {} ({}): reading every {} document in one pass, onto disk", sourceName, date, from);
                boolean read = reader.documents(from, asOf, d -> {
                    String id = d.ref().id();
                    java.util.Set<String> rowsDone = new java.util.HashSet<>(2);
                    Map<String, Object[]> fieldValues = new HashMap<>(2);   // once per signature: kinds of one pack share them
                    for (DerivedKind k : ks) {
                        String g = k.keyOf(d.data());
                        if (g == null) {
                            continue;
                        }
                        Object[] values = fieldValues.computeIfAbsent(rowKeys.get(k.signature()), x -> k.fieldValues(d.data(), today));
                        totals.get(k.kind()).computeIfAbsent(g, x -> k.totals()).add(id, values);
                        store.put("m" + SEP + k.kind() + SEP + g + SEP + id, NOTHING);
                        String sig = rowKeys.get(k.signature());
                        if (k.hasRows() && rowsDone.add(sig)) {
                            store.put("r" + SEP + sig + SEP + id, k.rowBytes(d.data(), today));
                        }
                    }
                    LocalDate dd = d.provenance() == null ? null : d.provenance().businessDate();
                    if (dd != null && (dataDate[0] == null || dd.isAfter(dataDate[0]))) {
                        dataDate[0] = dd;
                    }
                    if (++n[0] % PROGRESS_EVERY == 0) {
                        say(announce, "derived pages {}: {} {} documents read", sourceName, String.format("%,d", n[0]), from);
                    }
                });
                if (!read) {
                    LOG.info("derived pages {}: {} cannot be read in one pass; its pages are built in the heap", sourceName, from);
                    store.close();
                    return null;
                }
                ks.forEach(k -> scanned.put(k.kind(), (int) Math.min(Integer.MAX_VALUE, n[0])));
            }
            store.finish();
        } catch (IllegalStateException e) {
            store.close();
            LOG.debug("derived pages {}: a source cannot be read in one pass ({}); building in the heap", sourceName, e.getMessage());
            return null;
        } catch (RuntimeException | Error e) {
            store.close();
            throw e;
        }
        health = "UP";
        say(announce, "derived pages {} ({}) built in {} s on disk ({} MB): {}", sourceName, date, String.format("%.1f", (System.nanoTime() - started) / 1e9),
                store.sizeOnDisk() / (1024 * 1024), totals.entrySet().stream()
                        .map(t -> t.getKey() + " " + t.getValue().size() + " pages from " + String.format("%,d", scanned.getOrDefault(t.getKey(), 0)))
                        .sorted().collect(Collectors.joining(", ")));
        return new Snapshot(new DiskPages(store, byName, totals, rowKeys), scanned, asOf.live() ? null : dataDate[0], generation.incrementAndGet(),
                Instant.now(), version);
    }

    /**
     * Pages kept on disk: each group's totals in the heap; its members' ids (in id order) and rows in a {@link
     * com.ash.drishti.diskcache.SortedStore}, read when the page is asked for. The store's folder goes when the pages are retired.
     */
    static final class DiskPages implements Pages {
        private final com.ash.drishti.diskcache.SortedStore store;
        private final Map<String, DerivedKind> kinds;
        private final Map<String, Map<String, DerivedKind.Totals>> totals;
        private final Map<String, String> rowKeys;
        private final com.github.benmanes.caffeine.cache.Cache<String, DataNode> recent =
                com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(32).build();   // a page asked for again by the same view

        DiskPages(com.ash.drishti.diskcache.SortedStore store, Map<String, DerivedKind> kinds, Map<String, Map<String, DerivedKind.Totals>> totals,
                Map<String, String> rowKeys) {
            this.store = store;
            this.kinds = kinds;
            this.totals = totals;
            this.rowKeys = rowKeys;
        }

        @Override
        public DataNode page(String kind, String id) {
            DerivedKind k = kinds.get(kind);
            DerivedKind.Totals t = totals.getOrDefault(kind, Map.of()).get(id);
            if (k == null || t == null) {
                return null;
            }
            return recent.get(kind + SEP + id, x -> {
                String prefix = "m" + SEP + kind + SEP + id + SEP;
                List<String> listed = new ArrayList<>();
                store.scan(prefix, (key, v) -> {
                    listed.add(key.substring(prefix.length()));
                    return listed.size() < k.maxMembers();
                });
                String sig = rowKeys.get(k.signature());
                return k.buildStored(id, t, listed, member -> store.get("r" + SEP + sig + SEP + member));
            });
        }

        @Override
        public java.util.Set<String> ids(String kind) {
            return totals.getOrDefault(kind, Map.of()).keySet();
        }

        long sizeOnDisk() {
            return store.sizeOnDisk();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    /**
     * The members of one source kind read in one pass ({@link EntityReader#documents}): true when the sources could, the
     * groups then filled. False (nothing kept) when they cannot, and the members are read entity by entity instead.
     */
    private boolean streamed(String from, AsOf asOf, Members m, boolean announce) {
        say(announce, "derived pages {} ({}): reading every {} document in one pass", sourceName, asOf.live() ? "latest" : asOf.businessDate(), from);
        boolean read;
        try {
            read = reader.documents(from, asOf, d -> {
                m.seen++;
                if (m.n >= maxStream) {
                    m.cut = true;
                    return;
                }
                m.add(d);
                if (m.n % PROGRESS_EVERY == 0) {
                    say(announce, "derived pages {}: {} {} documents read", sourceName, String.format("%,d", m.n), from);
                }
            });
        } catch (IllegalStateException e) {
            LOG.debug("derived pages {}: {} cannot be read in one pass ({}); reading entity by entity", sourceName, from, e.getMessage());
            m.clear();
            return false;
        }
        if (!read) {
            return false;
        }
        if (m.cut) {
            m.partial = m.names() + " built from the first " + String.format("%,d", m.n) + " of " + String.format("%,d", m.seen) + " " + from
                    + " documents (max-stream " + String.format("%,d", maxStream) + "): raise max-stream, with heap for it";
            LOG.warn("derived pages {}: {}", sourceName, m.partial);
        }
        return true;
    }

    /** The groups of the derived kinds built from one source kind, filled document by document. */
    private static final class Members {
        final List<DerivedKind> kinds;
        final Map<DerivedKind, Map<String, Map<String, DataNode>>> groups = new HashMap<>();
        int n;
        long seen;
        boolean cut;
        String partial;
        LocalDate dataDate;

        final LocalDate today;

        Members(List<DerivedKind> kinds, LocalDate today) {
            this.kinds = kinds;
            this.today = today;
            clear();
        }

        void clear() {
            n = 0;
            seen = 0;
            cut = false;
            partial = null;
            dataDate = null;
            kinds.forEach(k -> groups.put(k, new HashMap<>()));
        }

        void add(EntityDocument d) {
            n++;
            Map<String, DataNode> made = new HashMap<>(4);   // one compact copy per signature: the grouped kinds of a pack share it
            for (DerivedKind k : kinds) {
                String g = k.keyOf(d.data());
                if (g != null) {                       // kept as the values the kind reads, not the document (a day of large documents)
                    DataNode member = made.computeIfAbsent(k.signature(), x -> k.compact(d.data(), today));
                    groups.get(k).computeIfAbsent(g, x -> new HashMap<>()).put(d.ref().id(), member);
                }
            }
            LocalDate dd = d.provenance() == null ? null : d.provenance().businessDate();
            if (dd != null && (dataDate == null || dd.isAfter(dataDate))) {
                dataDate = dd;
            }
        }

        List<String> names() {
            return kinds.stream().map(DerivedKind::kind).toList();
        }

        void into(Map<String, Map<String, DataNode>> byKind, Map<String, Integer> scanned) {
            for (DerivedKind k : kinds) {
                Map<String, DataNode> built = new HashMap<>();
                groups.get(k).forEach((g, m) -> built.put(g, k.buildCompact(g, m)));
                byKind.put(k.kind(), built);
                scanned.put(k.kind(), n);
            }
        }
    }

    private static void say(boolean info, String format, Object... args) {
        if (info) {
            LOG.info(format, args);
        } else {
            LOG.debug(format, args);
        }
    }
}
