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
package com.ash.drishti.plugin.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.exp.Exp;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.ScanPolicy;
import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Serves entities from Aerospike, laid out for millions of entities a day kept for years ({@link AerospikeLayout}): a
 * record per entity per business date, an index record per entity listing its dates, and the kind's dates. A read is
 * two key gets (the entity's dates, then that day's document). A {@code snapshot} kind takes the kind's newest date on
 * or before the one asked (within {@code lookback-days}) and an entity without a document that day is gone; an
 * {@code effective} kind takes the entity's last date on or before it. Type-ahead comes from the index set alone;
 * searches, pick lists, derived kinds and impact read the day's promoted bins ({@code layout.<kind>.columns}) with a
 * server-side filtered scan, kept by memory ({@code columns-cache-mb}, 1024; refreshed after {@code columns-seconds},
 * 300, for the newest day). Reverse lookups use the promoted link bins; without them up to {@code max-load-rows}
 * (200000) of the day's documents are scanned.
 *
 * <p>Settings: {@code hosts} ({@code localhost:3000}), {@code namespace} ({@code test}), {@code set} (the data domain,
 * e.g. {@code trading}), {@code kinds} (default: what the kinds set lists), {@code mode.<kind>}, {@code reverse-index}
 * (true), {@code lookback-days} (10), {@code refresh-seconds} (60: the kinds' dates and the ids), {@code source-name}
 * ({@code aerospike}), {@code user}/{@code password} (optional), {@code connect-timeout-ms} (3000).
 */
public final class AerospikeSourcePlugin implements SourcePlugin {

    private record DayKey(String kind, LocalDate date) {}

    private final Map<String, String> modes = new HashMap<>();
    private final Map<String, List<String>> promoted = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    private volatile Map<String, NavigableSet<LocalDate>> kindDates = Map.of();
    private AerospikeClient client;
    private SourceContext context;
    private String namespace;
    private String set;
    private String sourceName;
    private int lookbackDays;
    private int maxLoadRows;
    private List<String> configuredKinds = List.of();
    private boolean reverseIndex = true;
    private Cache<DayKey, ColumnSet> columnSets;
    /** Aerospike refuses scans beyond its limit ("operation not allowed at this time"): a few at a time per connector. */
    private final java.util.concurrent.Semaphore scans = new java.util.concurrent.Semaphore(2);
    private int scanThreads;

    @Override
    public PluginManifest manifest() {
        Set<String> kinds = configuredKinds.isEmpty() ? kindDates.keySet() : new HashSet<>(configuredKinds);
        return new PluginManifest(sourceName == null ? "aerospike" : sourceName, "1.0", kinds, new SourceCapabilities(false, reverseIndex, true, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "aerospike");
        this.namespace = ctx.setting("namespace", "test");
        this.set = ctx.setting("set", ctx.setting("domain", "drishti"));
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        this.maxLoadRows = Integer.parseInt(ctx.setting("max-load-rows", "200000"));
        this.reverseIndex = Boolean.parseBoolean(ctx.setting("reverse-index", "true"));
        this.scanThreads = Integer.parseInt(ctx.setting("scan-threads", "8"));
        String kinds = ctx.setting("kinds", "");
        this.configuredKinds = kinds.isBlank() ? List.of() : java.util.Arrays.stream(kinds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("mode.")) {
                modes.put(k.substring(5), v);
            }
            if (k.startsWith("layout.") && k.endsWith(".columns")) {
                promoted.put(k.substring(7, k.length() - 8), java.util.Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            }
        });
        this.columnSets = Caffeine.newBuilder().maximumWeight(Long.parseLong(ctx.setting("columns-cache-mb", "1024")) * 1024 * 1024)
                .weigher((DayKey k, ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, weight(v)))
                .expireAfterWrite(Duration.ofSeconds(Long.parseLong(ctx.setting("columns-seconds", "300")))).build();
        ClientPolicy policy = new ClientPolicy();
        policy.user = ctx.setting("user", null);
        policy.password = ctx.setting("password", null);
        policy.timeout = Integer.parseInt(ctx.setting("connect-timeout-ms", "3000"));
        // Start even when the cluster is not reachable or still initialising: the client keeps trying in the background,
        // reads fail (and health says so) until it answers, and the next refresh fills the catalogue.
        policy.failIfNotConnected = false;
        this.client = new AerospikeClient(policy, Host.parseHosts(ctx.setting("hosts", "localhost:3000"), 3000));
        refresh();
        long refresh = Long.parseLong(ctx.setting("refresh-seconds", "60"));
        ctx.scheduler().scheduleWithFixedDelay(this::refresh, refresh, refresh, TimeUnit.SECONDS);
    }

    /** The kinds' dates (a small set) and the entities' ids (the index set, two short bins each): no document is read. */
    void refresh() {
        Map<String, NavigableSet<LocalDate>> dates = new ConcurrentHashMap<>();
        List<EntityHit> hits = java.util.Collections.synchronizedList(new ArrayList<>());
        ScanPolicy sp = new ScanPolicy();
        sp.concurrentNodes = true;
        try {
            client.scanAll(sp, namespace, AerospikeLayout.kindsSet(set), (Key key, Record r) -> {
                String kind = r.getString(AerospikeLayout.KIND);
                List<?> ds = r.getList(AerospikeLayout.DATES);
                if (kind != null && ds != null) {
                    NavigableSet<LocalDate> out = new TreeSet<>();
                    ds.forEach(d -> out.add(AerospikeLayout.date(((Number) d).longValue())));
                    dates.put(kind, out);
                }
            }, AerospikeLayout.KIND, AerospikeLayout.DATES);
            client.scanAll(sp, namespace, AerospikeLayout.indexSet(set), (Key key, Record r) -> {
                String kind = r.getString(AerospikeLayout.KIND);
                String id = r.getString(AerospikeLayout.ID);
                if (kind != null && id != null) {
                    hits.add(new EntityHit(EntityRef.of(kind, id), id, kind + " · " + sourceName));
                }
            }, AerospikeLayout.KIND, AerospikeLayout.ID);
        } catch (RuntimeException e) {
            return;   // keep the last catalogue; health reports the connection
        }
        kindDates = dates;
        index.replaceAll(hits);
        // the newest day's promoted bins, ready before the first search (a background thread; searches find them cached)
        promoted.forEach((kind, paths) -> {
            NavigableSet<LocalDate> ds = dates.get(kind);
            if (ds != null && !ds.isEmpty() && !effective(kind) && columnSets.getIfPresent(new DayKey(kind, ds.last())) == null) {
                Thread.ofVirtual().name("aerospike-warm-" + kind).start(() -> {
                    try {
                        ColumnSet warmed = columnSets.get(new DayKey(kind, ds.last()), k -> scanDay(kind, ds.last(), paths));
                        java.util.Objects.requireNonNull(warmed);
                    } catch (RuntimeException e) {
                        // the first search loads it instead
                    }
                });
            }
        });
    }

    private static long weight(ColumnSet c) {
        long w = 64L + 56L * c.size();
        for (String id : c.ids()) {
            w += 2L * id.length();
        }
        return w + 8L * c.size() * (c.numbers().size() + c.texts().size());   // texts share their values (books, desks)
    }

    private boolean effective(String kind) {
        return "effective".equals(modes.get(kind));
    }

    /** The snapshot date for {@code kind} on {@code asked}: the kind's newest date on or before it, within the lookback. */
    private Optional<LocalDate> snapshotDate(String kind, LocalDate asked) {
        NavigableSet<LocalDate> ds = kindDates.get(kind);
        if (ds == null || ds.isEmpty()) {
            return Optional.empty();
        }
        LocalDate d = asked == null ? ds.last() : ds.floor(asked);
        if (d == null || asked != null && d.isBefore(asked.minusDays(lookbackDays))) {
            return Optional.empty();
        }
        return Optional.of(d);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
        if (!configuredKinds.isEmpty() && !configuredKinds.contains(ref.kind())) {
            return Optional.empty();
        }
        Record ix = client.get(null, new Key(namespace, AerospikeLayout.indexSet(set), AerospikeLayout.indexKey(ref.kind(), ref.id())),
                AerospikeLayout.DATES);
        if (ix == null || ix.getList(AerospikeLayout.DATES) == null) {
            return Optional.empty();
        }
        NavigableSet<LocalDate> has = new TreeSet<>();
        ix.getList(AerospikeLayout.DATES).forEach(d -> has.add(AerospikeLayout.date(((Number) d).longValue())));
        LocalDate asked = asOf.businessDate();
        LocalDate day;
        if (effective(ref.kind()) || kindDates.get(ref.kind()) == null) {
            day = asked == null ? has.last() : has.floor(asked);           // effective, or the kinds are not known yet
        } else {
            day = snapshotDate(ref.kind(), asked).filter(has::contains).orElse(null);
        }
        if (day == null) {
            return Optional.empty();
        }
        Record r = client.get(null, new Key(namespace, set, AerospikeLayout.docKey(ref.kind(), ref.id(), day)), AerospikeLayout.DOC);
        String json = r == null ? null : r.getString(AerospikeLayout.DOC);
        if (json == null) {
            return Optional.empty();
        }
        var data = context.parseJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return Optional.of(new EntityDocument(ref, data, new Provenance(sourceName, r.generation, Instant.now(), false, day)));
    }

    @Override
    public Set<String> columnar(String kind) {
        return effective(kind) ? Set.of() : Set.copyOf(promoted.getOrDefault(kind, List.of()));
    }

    @Override
    public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
        if (!columnar(kind).containsAll(paths)) {
            return Optional.empty();
        }
        Optional<LocalDate> day = snapshotDate(kind, asOf.businessDate());
        if (day.isEmpty()) {
            return Optional.empty();                           // a date this set does not hold (older than its TTL): another source may
        }
        ColumnSet all = columnSets.get(new DayKey(kind, day.get()), k -> scanDay(kind, day.get(), promoted.get(kind)));
        if (all.size() == 0) {
            return Optional.empty();                           // the day's records expired (TTL): an older store may hold it
        }
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

    /** One day of a kind, only the id and the promoted bins: the server filters by kind and date, no document travels. */
    private ColumnSet scanDay(String kind, LocalDate day, List<String> paths) {
        ScanPolicy sp = new ScanPolicy();
        sp.concurrentNodes = true;
        sp.filterExp = Exp.build(Exp.and(Exp.eq(Exp.stringBin(AerospikeLayout.KIND), Exp.val(kind)),
                Exp.eq(Exp.intBin(AerospikeLayout.DATE), Exp.val(AerospikeLayout.day(day)))));
        List<String> bins = new ArrayList<>(List.of(AerospikeLayout.ID));
        paths.forEach(p -> bins.add(AerospikeLayout.bin(p)));
        List<Object[]> rows = java.util.Collections.synchronizedList(new ArrayList<>());
        com.aerospike.client.ScanCallback each = (Key key, Record r) -> {
            Object[] row = new Object[paths.size() + 1];
            row[0] = r.getString(AerospikeLayout.ID);
            for (int i = 0; i < paths.size(); i++) {
                row[i + 1] = r.getValue(bins.get(i + 1));
            }
            rows.add(row);
        };
        scanPartitions(sp, each, bins.toArray(new String[0]));
        rows.sort(java.util.Comparator.comparing(r -> String.valueOf(r[0])));
        String[] ids = new String[rows.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = String.valueOf(rows.get(i)[0]);
        }
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        for (int c = 0; c < paths.size(); c++) {
            int col = c + 1;
            boolean numeric = rows.stream().map(r -> r[col]).filter(v -> v != null).allMatch(v -> v instanceof Number)
                    && rows.stream().anyMatch(r -> r[col] != null);
            if (numeric) {
                double[] v = new double[ids.length];
                for (int i = 0; i < ids.length; i++) {
                    Object x = rows.get(i)[col];
                    v[i] = x == null ? Double.NaN : ((Number) x).doubleValue();
                }
                nums.put(paths.get(c), v);
            } else {
                String[] v = new String[ids.length];
                Map<String, String> pool = new HashMap<>();
                for (int i = 0; i < ids.length; i++) {
                    Object x = rows.get(i)[col];
                    String s = x == null ? null : x instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(x);
                    v[i] = s == null || pool.size() > 200_000 ? s : pool.computeIfAbsent(s, k -> k);
                }
                texts.put(paths.get(c), v);
            }
        }
        return new ColumnSet(ids, nums, texts, day);
    }

    /**
     * A filtered scan of the domain's set, its 4096 partitions split into {@code scan-threads} ranges scanned at once
     * (one sequential scan of three million records takes several times longer), at most two such scans at a time.
     */
    private void scanPartitions(ScanPolicy sp, com.aerospike.client.ScanCallback each, String... bins) {
        scans.acquireUninterruptibly();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            int parts = 4096;
            int step = Math.max(1, Math.ceilDiv(parts, Math.max(1, scanThreads)));
            List<java.util.concurrent.Future<?>> running = new ArrayList<>();
            for (int begin = 0; begin < parts; begin += step) {
                int from = begin;
                int count = Math.min(step, parts - begin);
                running.add(pool.submit(() -> client.scanPartitions(sp, com.aerospike.client.query.PartitionFilter.range(from, count), namespace, set, each, bins)));
            }
            for (var f : running) {
                f.get();
            }
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            scans.release();
        }
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        if (!reverseIndex) {
            return List.of();
        }
        List<EntityRef> out = new ArrayList<>();
        for (String k : kind == null ? kindDates.keySet() : Set.of(kind)) {
            if (!kindDates.containsKey(k)) {
                continue;                                      // a kind this domain does not hold: no scan
            }
            Set<String> found = new TreeSet<>();
            Optional<LocalDate> day = effective(k) ? Optional.empty() : snapshotDate(k, asOf.businessDate());
            if (day.isPresent() && !columnar(k).isEmpty()) {
                // promoted link bins (nettingSet, book, counterparty.id …): no document is read
                ColumnSet c = columns(k, columnar(k), asOf).orElseThrow();
                c.texts().values().forEach(values -> {
                    for (int i = 0; i < values.length; i++) {
                        if (target.id().equals(values[i])) {
                            found.add(c.ids()[i]);
                        }
                    }
                });
            } else {
                try {
                    found.addAll(scanReferences(k, target.id(), day.orElse(null), asOf.businessDate()));
                } catch (com.aerospike.client.AerospikeException e) {
                    continue;                                  // refused or failed: no referrers from here, not an error page
                }
            }
            found.forEach(i -> out.add(EntityRef.of(k, i)));
        }
        return out;
    }

    /**
     * Entities whose documents mention the target, for a kind without promoted bins: the day's documents (or, for an
     * effective kind, each entity's latest document on or before the date), at most {@code max-load-rows}.
     */
    private Set<String> scanReferences(String kind, String target, LocalDate day, LocalDate asked) {
        ScanPolicy sp = new ScanPolicy();
        sp.concurrentNodes = true;
        sp.maxRecords = Math.max(1, maxLoadRows / Math.max(1, scanThreads));   // each partition range scans its share
        Exp byKind = Exp.eq(Exp.stringBin(AerospikeLayout.KIND), Exp.val(kind));
        sp.filterExp = Exp.build(day != null ? Exp.and(byKind, Exp.eq(Exp.intBin(AerospikeLayout.DATE), Exp.val(AerospikeLayout.day(day))))
                : asked == null ? byKind : Exp.and(byKind, Exp.le(Exp.intBin(AerospikeLayout.DATE), Exp.val(AerospikeLayout.day(asked)))));
        Map<String, Long> latest = new ConcurrentHashMap<>();
        Map<String, Boolean> mentions = new ConcurrentHashMap<>();
        scanPartitions(sp, (Key key, Record r) -> {
            String id = r.getString(AerospikeLayout.ID);
            long d = r.getLong(AerospikeLayout.DATE);
            String json = r.getString(AerospikeLayout.DOC);
            synchronized (latest) {
                if (id != null && json != null && d >= latest.getOrDefault(id, Long.MIN_VALUE)) {
                    latest.put(id, d);
                    mentions.put(id, ReferenceScanner.referencedIds(json).contains(target));
                }
            }
        }, AerospikeLayout.ID, AerospikeLayout.DATE, AerospikeLayout.DOC);
        Set<String> out = new TreeSet<>();
        mentions.forEach((id, yes) -> {
            if (yes) {
                out.add(id);
            }
        });
        return out;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public Map<String, Object> cacheStats() {
        return Map.of("kinds", kindDates.size(), "datesIndexed", kindDates.values().stream().mapToInt(Set::size).sum(), "ids", index.size(),
                "columnSets", columnSets.estimatedSize());
    }

    /** Re-reads the kinds' dates and ids, and forgets the columns read. */
    @Override
    public void purgeCaches() {
        columnSets.invalidateAll();
        refresh();
    }

    @Override
    public String health() {
        return client != null && client.isConnected() ? "UP" : "DOWN: not connected to Aerospike";
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
