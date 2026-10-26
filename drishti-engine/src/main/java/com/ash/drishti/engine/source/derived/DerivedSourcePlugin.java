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
    /**
     * Purges so far, and the purge count each cached computation started at (under the cache lock): a purge keeps a computation that is
     * running (its readers get it) and the next reader after it builds again, so a burst of data-load signals never runs two full reads of
     * a date at once (QA3A-01: each signal cleared the cache and started a full read of every trade document on top of the running ones).
     */
    private long purges;
    private final Map<String, Long> startedAt = new java.util.HashMap<>();
    /** Dates whose version refresh runs now (a purge meanwhile serves the last pages until it ends, rather than starting a second read). */
    private final java.util.Set<String> refreshing = new java.util.HashSet<>();
    /** The rebuild of the latest pages after a purge (store: disk): one runs, and purges while it waits or runs ask for one more at most. */
    private final java.util.concurrent.atomic.AtomicBoolean rebuildRunning = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean rebuildWanted = new java.util.concurrent.atomic.AtomicBoolean();
    /** How long the rebuild after a purge waits for more purges (a burst of data-load signals) before it reads. */
    private Duration rebuildDelay = Duration.ofSeconds(5);
    private final AtomicLong builds = new AtomicLong();
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
        rebuildDelay = org.springframework.boot.convert.DurationStyle.detectAndParse(context.setting("rebuild-delay", "5s"));
        if (onDisk) {
            // the pages' only folder: <data dir>/derived/<connector>; a previous run's pages are served again when still valid, the rest removed
            storeRoot = java.nio.file.Path.of(context.setting("store-dir", com.ash.drishti.api.DataDir.under("derived/" + sourceName))).toAbsolutePath();
            try {
                java.nio.file.Files.createDirectories(storeRoot);
                restore();
            } catch (java.io.IOException | RuntimeException e) {
                LOG.warn("derived pages {}: the pages of the previous run are not used ({}): built again", sourceName, e.toString());
                try {
                    com.ash.drishti.diskcache.SortedStore.deleteFolder(storeRoot);
                    java.nio.file.Files.createDirectories(storeRoot);
                } catch (java.io.IOException again) {
                    throw new IllegalStateException("derived pages " + sourceName + ": cannot use " + storeRoot + ": " + again.getMessage(), again);
                }
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
        Pages pages = snapshot(asOf).pages();
        if (k.searchFields().isEmpty()) {
            return pages.ids(kind).stream().sorted().filter(id -> id.toLowerCase(Locale.ROOT).contains(t)).limit(limit)
                    .map(id -> new EntityHit(EntityRef.of(kind, id), id, kind + " · from " + k.from())).toList();
        }
        // search-fields: the id or a page field (a group's name) holds the text; a hit is labelled by the first field
        List<EntityHit> out = new ArrayList<>();
        for (String id : pages.ids(kind).stream().sorted().toList()) {
            if (out.size() >= limit) {
                break;
            }
            DataNode page = id.toLowerCase(Locale.ROOT).contains(t) && t.isEmpty() ? null : pages.page(kind, id);
            String label = page == null ? id : page.get(k.searchFields().get(0)).isNull() ? id : page.get(k.searchFields().get(0)).asText();
            boolean hit = id.toLowerCase(Locale.ROOT).contains(t)
                    || (page != null && k.searchFields().stream().anyMatch(f -> !page.get(f).isNull() && page.get(f).asText().toLowerCase(Locale.ROOT).contains(t)));
            if (hit) {
                out.add(new EntityHit(EntityRef.of(kind, id), label, kind + " · " + id));
            }
        }
        return out;
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
            purges++;
            for (var it = cache.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue().isDone()) {                   // a computation still running is kept: its readers get it, the next builds again
                    retire(e.getValue(), null);
                    startedAt.remove(e.getKey());
                    it.remove();
                }
            }
        }
        if (onDisk) {
            rebuildLatest();
        }
    }

    /**
     * Builds the latest pages again after a purge, in the background, after {@code rebuild-delay}: purges meanwhile (a burst of data-load
     * signals) are one rebuild, and a purge while it reads asks for one more, never a second read beside it.
     */
    private void rebuildLatest() {
        rebuildWanted.set(true);
        if (!rebuildRunning.compareAndSet(false, true)) {
            return;                                           // the running rebuild takes it
        }
        Thread.ofVirtual().name("derived-rebuild").start(() -> {
            while (true) {
                try {
                    while (rebuildWanted.get()) {
                        Thread.sleep(rebuildDelay.toMillis());
                        rebuildWanted.set(false);
                        try {
                            snapshot(AsOf.LATEST);
                            if (!current("live")) {
                                rebuildWanted.set(true);      // it joined a read that began before the last purge: once more
                            }
                        } catch (RuntimeException e) {
                            // said in health and the log by the computation; the next reader tries again
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    rebuildRunning.set(false);
                    return;
                }
                rebuildRunning.set(false);
                if (!rebuildWanted.get() || !rebuildRunning.compareAndSet(false, true)) {
                    return;                                   // nothing more asked, or another rebuild took it
                }
            }
        });
    }

    /** True when the computation cached under {@code key} began after the last purge. */
    private boolean current(String key) {
        synchronized (cache) {
            return cache.containsKey(key) && startedAt.getOrDefault(key, 0L) >= purges;
        }
    }

    /** Full computations started since the start (tests: a burst of purges is one or two of them). */
    long builds() {
        return builds.get();
    }

    // ---- Drishti*net: a group gathered across nodes (GroupShares) ----------------------------------------------------------

    /** The kind the derived kind {@code kind} groups, or empty when this source does not derive it. */
    public Optional<String> groupedFrom(String kind) {
        return kinds.stream().filter(d -> d.kind().equals(kind)).findFirst().map(DerivedKind::from);
    }

    /**
     * This node's share of one group, for the node gathering it (DRISHTI_NET.md section 8): its page and, when the page lists every member,
     * each member's values read from this node's own sources. Empty when no member of the group is here.
     */
    public Optional<DataNode> share(EntityRef ref, AsOf asOf) {
        DerivedKind k = kinds.stream().filter(d -> d.kind().equals(ref.kind())).findFirst().orElse(null);
        Optional<EntityDocument> page = k == null ? Optional.empty() : fetch(ref, asOf);
        if (page.isEmpty()) {
            return Optional.empty();
        }
        DataNode doc = page.get().data();
        DataNode listed = doc.get(k.membersField());
        long count = (long) doc.get("memberCount").asDouble();
        Map<String, DataNode> compacts = null;
        if (listed.size() == count) {
            List<EntityRef> refs = new ArrayList<>(listed.size());
            for (int i = 0; i < listed.size(); i++) {
                refs.add(EntityRef.of(k.from(), listed.get(i).asText()));
            }
            Map<EntityRef, EntityDocument> read = refs.isEmpty() ? Map.of() : reader.read(refs, asOf);
            if (read.size() == refs.size()) {
                LocalDate today = page.get().provenance() != null && page.get().provenance().businessDate() != null
                        ? page.get().provenance().businessDate() : LocalDate.now(zone);
                compacts = new LinkedHashMap<>();
                for (EntityRef r : refs) {
                    compacts.put(r.id(), k.compact(read.get(r).data(), today));
                }
            }
        }
        return Optional.of(GroupShares.document(k, doc, compacts));
    }

    /** A share as a node sent it ({@link #share}), read for {@link #gather}; empty when this source does not derive the kind. */
    public Optional<GroupShares.Share> readShare(String kind, String node, DataNode doc) {
        return kinds.stream().filter(d -> d.kind().equals(kind)).findFirst().map(k -> GroupShares.read(k, node, doc));
    }

    /** The group's page from every node's share, this node's first ({@link GroupShares}); empty when this source does not derive the kind. */
    public Optional<DataNode> gather(EntityRef ref, List<GroupShares.Share> shares) {
        return kinds.stream().filter(d -> d.kind().equals(ref.kind())).findFirst().map(k -> GroupShares.merge(k, ref.id(), shares));
    }

    @Override
    public String health() {
        return health;
    }

    /** Closes the pages on disk keeping their folders: the next start serves them again when they are still current. */
    @Override
    public void close() {
        synchronized (cache) {   // pinning-ok: join() is only called on futures that are already done
            for (CompletableFuture<Snapshot> f : cache.values()) {
                if (f.isDone() && !f.isCompletedExceptionally() && f.join().pages() instanceof DiskPages d) {
                    d.closeKeepingFiles();
                }
            }
        }
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
        synchronized (cache) {   // pinning-ok: join() is only called on a future that is already done
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
            // done before the last purge: built again (once: the new computation is cached at once and shared); while its version refresh
            // runs, the last pages are served until it ends
            boolean purged = f != null && f.isDone() && startedAt.getOrDefault(key, 0L) < purges;
            if (purged && refreshing.contains(key) && !f.isCompletedExceptionally()) {
                return f.join();
            }
            boolean done = !purged && f != null && f.isDone() && !f.isCompletedExceptionally();
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
                long epoch = purges;
                refreshing.add(key);
                Thread.ofVirtual().name("derived-refresh").start(() -> {
                    try {
                        builds.incrementAndGet();
                        next.complete(compute(asOf, false));
                        synchronized (cache) {
                            put(key, next);
                            startedAt.put(key, epoch);
                        }
                    } catch (RuntimeException | OutOfMemoryError e) {
                        // the last computation stays in use; out of memory is said, not left to kill the thread silently
                        health = (e instanceof OutOfMemoryError ? "DEGRADED: " : "DOWN: ") + e;
                        LOG.warn("derived pages for {} could not be refreshed: {}", asOf, e.toString());
                    } finally {
                        synchronized (cache) {
                            refreshing.remove(key);
                        }
                    }
                });
                return last;
            }
            if (f == null || f.isCompletedExceptionally() || purged) {
                f = new CompletableFuture<>();
                put(key, f);
                startedAt.put(key, purges);
                mine = true;
                while (cache.size() > CACHED_DATES) {
                    retire(cache.remove(cache.keySet().iterator().next()), null);
                }
            }
        }
        if (mine) {
            try {
                builds.incrementAndGet();
                f.complete(compute(asOf, true));
            } catch (RuntimeException e) {
                if (com.ash.drishti.engine.source.SourceRegistry.ServerStarting.in(e)) {
                    // asked while the server is still starting: not a failure of this source (DOWN would make the registry restart it as a
                    // lost connection); the next reader builds the pages
                    LOG.info("derived pages for {} wait for the server to start: {}", asOf, e.getMessage());
                } else {
                    health = "DOWN: " + e.getMessage();
                    LOG.warn("derived pages for {} could not be built: {}", asOf, e.toString());
                }
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
                        DataNode member = null;
                        for (String g : k.keysOf(d.data())) {   // a member of several groups (also-group-by) is read once
                            member = member == null ? k.compact(d.data(), todayOf(asOf)) : member;
                            groups.get(k).computeIfAbsent(g, x -> new HashMap<>()).put(d.ref().id(), member);
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

    /** A group's totals once built: its member count and field values (what the page shows). */
    record Frozen(long count, Map<String, Object> values) {}

    /** What a folder of pages on disk was built from: the cache key (date), the data version, and the row signatures' names. */
    record Manifest(String key, String version, String dataDate, Map<String, Integer> scanned, Map<String, String> rowKeys) {}

    private static final String MANIFEST = "pages.json";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String cacheKey(AsOf asOf) {
        return asOf.live() ? "live" : asOf.businessDate() + "@" + asOf.knownAt();
    }

    /** The row signatures' short names, as a build names them (kinds in their order). */
    private Map<String, String> rowKeys() {
        Map<String, String> out = new HashMap<>();
        kinds.forEach(k -> out.computeIfAbsent(k.signature(), x -> "s" + out.size()));
        return out;
    }

    /**
     * At start (store: disk): the newest pages of each date a previous run left are served again at once, when they were built by
     * the same definitions; whether their data is still current is then asked as for any computation (a new version rebuilds, the
     * old pages serving meanwhile). Every other folder goes. Without this, grouped pages time out until the first build ends.
     */
    private void restore() throws java.io.IOException {
        Map<String, java.nio.file.Path> newest = new HashMap<>();
        Map<String, Manifest> manifests = new HashMap<>();
        Map<String, String> want = rowKeys();
        try (var dirs = java.nio.file.Files.list(storeRoot)) {
            for (java.nio.file.Path d : dirs.filter(java.nio.file.Files::isDirectory).sorted().toList()) {
                java.nio.file.Path m = d.resolve(MANIFEST);
                if (!java.nio.file.Files.isRegularFile(m)) {
                    continue;
                }
                try {
                    Manifest man = JSON.readValue(m.toFile(), Manifest.class);
                    if (!want.equals(man.rowKeys())) {
                        continue;                                 // built by other definitions: built again
                    }
                    java.nio.file.Path had = newest.get(man.key());
                    if (had == null || java.nio.file.Files.getLastModifiedTime(m).compareTo(java.nio.file.Files.getLastModifiedTime(had.resolve(MANIFEST))) > 0) {
                        newest.put(man.key(), d);
                        manifests.put(man.key(), man);
                    }
                } catch (java.io.IOException | RuntimeException e) {
                    // unreadable: removed below
                }
            }
        }
        java.util.Set<java.nio.file.Path> keep = new java.util.HashSet<>(newest.values());
        Map<String, DerivedKind> byName = new HashMap<>();
        kinds.forEach(k -> byName.put(k.kind(), k));
        for (Map.Entry<String, java.nio.file.Path> e : newest.entrySet()) {
            com.ash.drishti.diskcache.SortedStore store = com.ash.drishti.diskcache.SortedStore.open(e.getValue());
            if (store == null) {
                keep.remove(e.getValue());
                continue;
            }
            Map<String, Map<String, Frozen>> frozen = new HashMap<>();
            kinds.forEach(k -> frozen.put(k.kind(), new HashMap<>()));
            store.scan("t" + SEP, (key, value) -> {
                String[] parts = key.split(String.valueOf(SEP), 3);
                try {
                    Frozen f = JSON.readValue(value, Frozen.class);
                    Map<String, Object> v = new LinkedHashMap<>();
                    f.values().forEach((name, x) -> v.put(name, x instanceof Integer i ? Long.valueOf(i) : x));   // as built: whole numbers are long
                    frozen.computeIfAbsent(parts[1], x -> new HashMap<>()).put(parts[2], new Frozen(f.count(), v));
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
                return true;
            });
            Manifest man = manifests.get(e.getKey());
            Snapshot s = new Snapshot(new DiskPages(store, byName, frozen, man.rowKeys()), man.scanned(), man.dataDate() == null ? null : LocalDate.parse(man.dataDate()), generation.incrementAndGet(),
                    Instant.now(), man.version());
            synchronized (cache) {
                cache.put(e.getKey(), CompletableFuture.completedFuture(s));
            }
            LOG.info("derived pages {} ({}): {} pages kept from the previous run (data version checked as usual)", sourceName,
                    e.getKey(), frozen.values().stream().mapToInt(Map::size).sum());
        }
        try (var dirs = java.nio.file.Files.list(storeRoot)) {
            for (java.nio.file.Path d : dirs.filter(d -> !keep.contains(d)).toList()) {
                if (com.ash.drishti.diskcache.SortedStore.inUse(d)) {
                    continue;                                     // another process (a server sharing the data folder) has it open
                }
                com.ash.drishti.diskcache.SortedStore.deleteFolder(d);
            }
        }
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
        Map<String, Map<String, Frozen>> frozen;
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
                        List<String> keys = k.keysOf(d.data());
                        if (keys.isEmpty()) {
                            continue;
                        }
                        Object[] values = fieldValues.computeIfAbsent(rowKeys.get(k.signature()), x -> k.fieldValues(d.data(), today));
                        for (String g : keys) {
                            totals.get(k.kind()).computeIfAbsent(g, x -> k.totals()).add(id, values);
                            store.put("m" + SEP + k.kind() + SEP + g + SEP + id, NOTHING);
                        }
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
            // the totals, frozen (the heap keeps these, not the accumulators) and saved with the pages, so a restart serves them at once
            frozen = new HashMap<>();
            for (Map.Entry<String, Map<String, DerivedKind.Totals>> t : totals.entrySet()) {
                Map<String, Frozen> groups = new HashMap<>();
                for (Map.Entry<String, DerivedKind.Totals> g : t.getValue().entrySet()) {
                    Frozen f = new Frozen(g.getValue().count, g.getValue().values());
                    groups.put(g.getKey(), f);
                    store.put("t" + SEP + t.getKey() + SEP + g.getKey(), JSON.writeValueAsBytes(f));
                }
                frozen.put(t.getKey(), groups);
            }
            store.finish();
            JSON.writeValue(dir.resolve(MANIFEST).toFile(), new Manifest(cacheKey(asOf), version, asOf.live() || dataDate[0] == null ? null : dataDate[0].toString(), scanned, rowKeys));
        } catch (IllegalStateException e) {
            store.close();
            LOG.debug("derived pages {}: a source cannot be read in one pass ({}); building in the heap", sourceName, e.getMessage());
            return null;
        } catch (java.io.IOException e) {
            store.close();
            throw new java.io.UncheckedIOException(e);
        } catch (RuntimeException | Error e) {
            store.close();
            throw e;
        }
        health = "UP";
        say(announce, "derived pages {} ({}) built in {} s on disk ({} MB): {}", sourceName, date, String.format("%.1f", (System.nanoTime() - started) / 1e9),
                store.sizeOnDisk() / (1024 * 1024), frozen.entrySet().stream()
                        .map(t -> t.getKey() + " " + t.getValue().size() + " pages from " + String.format("%,d", scanned.getOrDefault(t.getKey(), 0)))
                        .sorted().collect(Collectors.joining(", ")));
        return new Snapshot(new DiskPages(store, byName, frozen, rowKeys), scanned, asOf.live() ? null : dataDate[0], generation.incrementAndGet(),
                Instant.now(), version);
    }

    /**
     * Pages kept on disk: each group's totals in the heap; its members' ids (in id order) and rows in a {@link
     * com.ash.drishti.diskcache.SortedStore}, read when the page is asked for. The store's folder goes when the pages are retired.
     */
    static final class DiskPages implements Pages {
        private final com.ash.drishti.diskcache.SortedStore store;
        private final Map<String, DerivedKind> kinds;
        private final Map<String, Map<String, Frozen>> totals;
        private final Map<String, String> rowKeys;
        private final com.github.benmanes.caffeine.cache.Cache<String, DataNode> recent =
                com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(32).build();   // a page asked for again by the same view

        DiskPages(com.ash.drishti.diskcache.SortedStore store, Map<String, DerivedKind> kinds, Map<String, Map<String, Frozen>> totals,
                Map<String, String> rowKeys) {
            this.store = store;
            this.kinds = kinds;
            this.totals = totals;
            this.rowKeys = rowKeys;
        }

        @Override
        public DataNode page(String kind, String id) {
            DerivedKind k = kinds.get(kind);
            Frozen t = totals.getOrDefault(kind, Map.of()).get(id);
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
                return k.buildStored(id, t.count(), t.values(), listed, member -> store.get("r" + SEP + sig + SEP + member));
            });
        }

        @Override
        public java.util.Set<String> ids(String kind) {
            return totals.getOrDefault(kind, Map.of()).keySet();
        }

        long sizeOnDisk() {
            return store.sizeOnDisk();
        }

        void closeKeepingFiles() {
            store.closeKeepingFiles();
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
                for (String g : k.keysOf(d.data())) {   // kept as the values the kind reads, not the document (a day of large documents)
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
