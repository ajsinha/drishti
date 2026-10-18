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
    private record Snapshot(Map<String, Map<String, DataNode>> byKind, Map<String, Integer> scanned, LocalDate dataDate, long generation,
            Instant at, String version) {
        Snapshot checked(Instant now) {
            return new Snapshot(byKind, scanned, dataDate, generation, now, version);
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
        DataNode doc = s.byKind().getOrDefault(ref.kind(), Map.of()).get(ref.id());
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
        Map<String, DataNode> groups = snapshot(asOf).byKind().getOrDefault(kind, Map.of());
        return groups.keySet().stream().sorted().filter(id -> id.toLowerCase(Locale.ROOT).contains(t)).limit(limit)
                .map(id -> new EntityHit(EntityRef.of(kind, id), id, kind + " · from " + k.from())).toList();
    }

    /** What this plugin's own definition says a field of one of its derived kinds means, or empty (not its kind, or not a field of it). */
    @Override
    public Optional<FieldNote> describeField(String kind, String field) {
        return kinds.stream().filter(d -> d.kind().equals(kind)).findFirst().flatMap(d -> d.describe(field));
    }

    @Override
    public Map<String, Object> cacheStats() {
        synchronized (cache) {
            return Map.of("dates", cache.size(), "kinds", kinds.size());
        }
    }

    @Override
    public boolean dependsOn(String kind) {
        return kinds.stream().anyMatch(k -> k.from().equals(kind));
    }

    @Override
    public void purgeCaches() {
        synchronized (cache) {
            cache.clear();
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
                cache.put(key, CompletableFuture.completedFuture(same));
                return same;
            }
            boolean timer = done && f.join().at().plus(refresh).isBefore(Instant.now());
            // a version the sources can say decides (a load rebuilds); without one, the refresh timer does
            boolean stale = done && (f.join().version() == null ? timer : Boolean.TRUE.equals(changed) || asked && changed == null && timer);
            if (stale) {
                // serve the last computation now and refresh it behind: a reader never waits for a recomputation
                Snapshot last = f.join();
                CompletableFuture<Snapshot> next = new CompletableFuture<>();
                cache.put(key, CompletableFuture.completedFuture(last.checked(Instant.now())));   // one refresh at a time
                Thread.ofVirtual().name("derived-refresh").start(() -> {
                    try {
                        next.complete(compute(asOf, false));
                        synchronized (cache) {
                            cache.put(key, next);
                        }
                    } catch (RuntimeException | OutOfMemoryError e) {
                        // the last computation stays in use; out of memory is said, not left to kill the thread silently
                        health = "DOWN: " + e;
                        LOG.warn("derived pages for {} could not be refreshed: {}", asOf, e.toString());
                    }
                });
                return last;
            }
            if (f == null || f.isCompletedExceptionally()) {
                f = new CompletableFuture<>();
                cache.put(key, f);
                mine = true;
                while (cache.size() > CACHED_DATES) {
                    cache.remove(cache.keySet().iterator().next());
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
                health = "DOWN: out of memory building the pages (lower max-stream, or give the server more heap)";
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
            Members one = new Members(e.getValue());
            if (streamed(e.getKey(), asOf, one, announce)) {           // one pass over the sources' files
                one.into(byKind, scanned);
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
                            groups.get(k).computeIfAbsent(g, x -> new HashMap<>()).put(d.ref().id(), k.compact(d.data()));
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
        health = "UP";
        say(announce, "derived pages {} ({}) built in {} s: {}", sourceName, asOf.live() ? "latest" : asOf.businessDate(),
                String.format("%.1f", (System.nanoTime() - started) / 1e9), byKind.entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue().size() + " pages from " + String.format("%,d", scanned.getOrDefault(e.getKey(), 0)))
                        .sorted().collect(Collectors.joining(", ")));
        return new Snapshot(byKind, scanned, asOf.live() ? null : dataDate, generation.incrementAndGet(), Instant.now(), version);
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
            LOG.warn("derived pages {}: {} has more than {} documents; {} are built from the first {} only (raise the connector's max-stream setting)",
                    sourceName, from, String.format("%,d", maxStream), m.names(), String.format("%,d", maxStream));
        }
        return true;
    }

    /** The groups of the derived kinds built from one source kind, filled document by document. */
    private static final class Members {
        final List<DerivedKind> kinds;
        final Map<DerivedKind, Map<String, Map<String, DataNode>>> groups = new HashMap<>();
        int n;
        boolean cut;
        LocalDate dataDate;

        Members(List<DerivedKind> kinds) {
            this.kinds = kinds;
            clear();
        }

        void clear() {
            n = 0;
            cut = false;
            dataDate = null;
            kinds.forEach(k -> groups.put(k, new HashMap<>()));
        }

        void add(EntityDocument d) {
            n++;
            for (DerivedKind k : kinds) {
                String g = k.keyOf(d.data());
                if (g != null) {                       // kept as the values the kind reads, not the document (a day of large documents)
                    groups.get(k).computeIfAbsent(g, x -> new HashMap<>()).put(d.ref().id(), k.compact(d.data()));
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
