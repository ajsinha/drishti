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
package com.ash.drishti.plugin.cassandra;

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
import com.ash.drishti.api.pool.PoolStats;
import com.ash.drishti.api.tls.TlsMaterial;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Serves entities from Apache Cassandra (and ScyllaDB, DataStax Astra, Amazon Keyspaces), in the layout of
 * {@link CassandraLayout}: a keyspace per data domain, a table per kind with a partition per business day, generation and
 * bucket keyed by id, and the shared {@code entity_days} and {@code kind_days}. Readers follow {@code kind_days}, so a day
 * being loaded or reloaded is seen whole or not at all (the loader names the new generation there last).
 *
 * <p>Reads (docs/archive/CASSANDRA_CONNECTOR_DESIGN.md section 5): the catalogue is each kind's {@code kind_days}
 * partition, re-read every {@code refresh-seconds} (60). One entity on a day is one row of one partition: a {@code snapshot}
 * kind takes the kind's newest day on or before the date asked (within {@code lookback-days}, 10), an {@code effective} kind
 * the entity's own latest day from {@code entity_days}; documents are kept by {@code doc-cache-mb} (64). Type-ahead is the
 * newest day's ids (an effective kind: every day's). Searches, pick lists, derived kinds and impact read a day's promoted
 * columns ({@code layout.<kind>.columns}) bucket by bucket, {@code scan-threads} (8) at a time, kept by
 * {@code columns-cache-mb} (1024); reverse lookups use them, or read at most {@code max-load-rows} (200000) documents of the
 * day. No statement scans a table: each names a whole partition key.
 *
 * <p>Settings: {@code contact-points}, {@code local-datacenter}, {@code keyspace} (the domain), {@code kinds},
 * {@code mode.<kind>}, {@code layout.<kind>.columns}, {@code consistency}, {@code page-size}, {@code request-timeout-ms},
 * {@code connections-per-node}, {@code max-concurrent-reads}, log-ins and {@code tls.*} ({@link CassandraSessions}).
 */
public final class CassandraSourcePlugin implements SourcePlugin {

    private record DayKey(String kind, LocalDate date, int generation) {}

    private record DocKey(String kind, String id, LocalDate date, int generation) {}

    /** How far back an effective entity's days are looked for a day the catalogue lists (later ones being loaded or dropped). */
    private static final int ENTITY_DAYS_PROBE = 16;

    private final Map<String, String> modes = new HashMap<>();
    private final Map<String, List<String>> promoted = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    private final Map<String, List<EntityHit>> hitsByKind = new ConcurrentHashMap<>();
    private final Map<String, String> hitsBuiltFor = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Map<String, NavigableMap<LocalDate, CassandraLayout.Day>> catalogue = Map.of();
    private volatile Map<String, String> tables = Map.of();
    private volatile String catalogueProblem;
    private volatile Instant lastUpdate;
    private CassandraStore store;
    private DayReads reads;
    private SourceContext context;
    private TlsMaterial tls;
    private String sourceName;
    private String keyspace;
    private String contactPoints;
    private int lookbackDays;
    private int maxLoadRows;
    private int connectionsPerNode;
    private int maxConcurrent;
    private boolean reverseIndex = true;
    private List<String> configuredKinds = List.of();
    private Cache<DayKey, ColumnSet> columnSets;
    private Cache<DocKey, String> documents;

    @Override
    public PluginManifest manifest() {
        Set<String> kinds = configuredKinds.isEmpty() ? catalogue.keySet() : Set.copyOf(configuredKinds);
        return new PluginManifest(sourceName == null ? "cassandra" : sourceName, "1.0", kinds, new SourceCapabilities(false, reverseIndex, true, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "cassandra");
        this.keyspace = CassandraLayout.keyspace(ctx.setting("keyspace", ctx.setting("domain", "drishti")));
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        this.maxLoadRows = Integer.parseInt(ctx.setting("max-load-rows", "200000"));
        this.reverseIndex = Boolean.parseBoolean(ctx.setting("reverse-index", "true"));
        this.maxConcurrent = Integer.parseInt(ctx.setting("max-concurrent-reads", "256"));
        String kinds = ctx.setting("kinds", "");
        this.configuredKinds = kinds.isBlank() ? List.of() : Arrays.stream(kinds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("mode.")) {
                modes.put(k.substring(5), v);
            }
            if (k.startsWith("layout.") && k.endsWith(".columns")) {
                promoted.put(k.substring(7, k.length() - 8), Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
            }
        });
        this.columnSets = Caffeine.newBuilder().maximumWeight(Long.parseLong(ctx.setting("columns-cache-mb", "1024")) * 1024 * 1024)
                .weigher((DayKey k, ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, weight(v))).build();
        this.documents = Caffeine.newBuilder().maximumWeight(Long.parseLong(ctx.setting("doc-cache-mb", "64")) * 1024 * 1024)
                .weigher((DocKey k, String v) -> 64 + 2 * v.length()).build();
        CassandraSessions.Plan plan = CassandraSessions.plan(ctx.settings(), System::getenv);   // fails the start, naming the setting
        this.tls = plan.tls();
        this.contactPoints = plan.bundle() != null ? "the secure connect bundle " + plan.bundle().getFileName() : ctx.setting("contact-points", "");
        this.connectionsPerNode = plan.connectionsPerNode();
        Duration connect = Duration.ofMillis(Long.parseLong(ctx.setting("connect-timeout-ms", "5000")));
        this.store = new CassandraStore(CassandraSessions.open(ctx.settings(), plan, System::getenv), Integer.parseInt(ctx.setting("scan-threads", "8")), connect);
        this.probePoints = plan.bundle() == null ? plan.contactPoints() : List.of();
        this.probeTimeout = connect;
        probeLater();                                          // why it cannot connect, in words, while the driver retries (QA3B-11)
        this.reads = new DayReads(store, keyspace);
        try {
            refresh();                                         // a cluster down now: the connector starts, health says so, a later refresh fills in
        } catch (RuntimeException e) {
            catalogueProblem = e.getMessage();
        }
        long every = Long.parseLong(ctx.setting("refresh-seconds", "60"));
        this.refreshTask = ctx.scheduler().scheduleWithFixedDelay(() -> {
            if (refreshing.compareAndSet(false, true)) {       // the scheduler's thread is shared: the reads run on a virtual thread
                Thread.ofVirtual().name("cassandra-refresh-" + sourceName).start(() -> {
                    try {
                        refresh();
                    } catch (RuntimeException e) {
                        catalogueProblem = e.getMessage();     // keep the last catalogue; health reports it
                    } finally {
                        refreshing.set(false);
                    }
                });
            }
        }, every, every, TimeUnit.SECONDS);
    }

    // ---- the catalogue ---------------------------------------------------------------------------------------------------------

    /** Each kind's days from {@code kind_days} (one small partition per kind), then the type-ahead ids of kinds whose newest day changed. */
    void refresh() {
        if (store.keyspace(keyspace).isEmpty()) {
            throw new IllegalStateException("keyspace " + keyspace + " does not exist (load it with tools/load-cassandra.sh, or set keyspace)");
        }
        if (store.table(keyspace, CassandraLayout.KIND_DAYS).isEmpty()) {
            throw new IllegalStateException("keyspace " + keyspace + " has no kind_days table: nothing has been loaded in the connector's layout "
                    + "(tools/load-cassandra.sh --create, or the CQL it prints)");
        }
        Map<String, String> byComment = store.kindTables(keyspace);
        Map<String, String> t = new LinkedHashMap<>();
        if (configuredKinds.isEmpty()) {
            t.putAll(byComment);
        } else {
            configuredKinds.forEach(k -> t.put(k, byComment.getOrDefault(k, CassandraLayout.table(k))));
        }
        Map<String, NavigableMap<LocalDate, CassandraLayout.Day>> next = new ConcurrentHashMap<>();
        for (String kind : t.keySet()) {
            next.put(kind, kindDays(kind));
        }
        if (!next.equals(catalogue)) {
            lastUpdate = Instant.now();
        }
        tables = t;
        catalogue = next;
        catalogueProblem = null;
        rebuildTypeAhead(next);
        warmNewestColumns(next);
    }

    /** A kind's days, oldest first, from its {@code kind_days} partition. */
    private NavigableMap<LocalDate, CassandraLayout.Day> kindDays(String kind) {
        NavigableMap<LocalDate, CassandraLayout.Day> days = new TreeMap<>();
        store.forEach(CassandraLayout.selectKindDays(keyspace), r -> {
            LocalDate d = r.getLocalDate(0);
            days.put(d, new CassandraLayout.Day(d, r.getInt(1), Math.max(1, r.getInt(2)), r.getLong(3), r.getInstant(4)));
        }, kind);
        return java.util.Collections.unmodifiableNavigableMap(days);
    }

    /** A kind's days, read now when the catalogue has not seen the kind yet (a kind configured but not refreshed). */
    private NavigableMap<LocalDate, CassandraLayout.Day> days(String kind) {
        NavigableMap<LocalDate, CassandraLayout.Day> d = catalogue.get(kind);
        if (d != null) {
            return d;
        }
        if (!configuredKinds.isEmpty() && !configuredKinds.contains(kind)) {
            return java.util.Collections.emptyNavigableMap();
        }
        if (!store.connected() || store.table(keyspace, table(kind)).isEmpty()) {
            return java.util.Collections.emptyNavigableMap();
        }
        return kindDays(kind);
    }

    private String table(String kind) {
        return tables.getOrDefault(kind, CassandraLayout.table(kind));
    }

    /** A kind's identifiers are rebuilt only when the day they come from changes (a new day, or a reload's new generation). */
    private void rebuildTypeAhead(Map<String, NavigableMap<LocalDate, CassandraLayout.Day>> days) {
        boolean changed = hitsByKind.keySet().retainAll(days.keySet());
        for (var e : days.entrySet()) {
            String kind = e.getKey();
            NavigableMap<LocalDate, CassandraLayout.Day> ds = e.getValue();
            String stamp = ds.isEmpty() ? "" : effective(kind) ? String.valueOf(ds.hashCode()) : ds.lastKey() + "#" + ds.lastEntry().getValue().generation();
            if (stamp.equals(hitsBuiltFor.get(kind))) {
                continue;
            }
            Set<String> ids = new TreeSet<>();
            if (!ds.isEmpty()) {
                // a snapshot kind's newest day lists every entity; an effective kind's days list only changes, so all are read (bounded)
                for (CassandraLayout.Day d : effective(kind) ? ds.descendingMap().values() : List.of(ds.lastEntry().getValue())) {
                    ids.addAll(reads.ids(table(kind), d));
                    if (ids.size() >= maxLoadRows) {
                        break;
                    }
                }
            }
            List<EntityHit> hits = new ArrayList<>(ids.size());
            ids.forEach(id -> hits.add(new EntityHit(EntityRef.of(kind, id), id, kind + " · " + sourceName)));
            hitsByKind.put(kind, hits);
            hitsBuiltFor.put(kind, stamp);
            changed = true;
        }
        if (changed) {
            List<EntityHit> all = new ArrayList<>();
            hitsByKind.values().forEach(all::addAll);
            index.replaceAll(all);
        }
    }

    /** The newest day's promoted columns, read in the background so the first search finds them cached. */
    private void warmNewestColumns(Map<String, NavigableMap<LocalDate, CassandraLayout.Day>> days) {
        promoted.forEach((kind, paths) -> {
            NavigableMap<LocalDate, CassandraLayout.Day> ds = days.get(kind);
            if (ds == null || ds.isEmpty() || effective(kind)) {
                return;
            }
            CassandraLayout.Day newest = ds.lastEntry().getValue();
            if (columnSets.getIfPresent(new DayKey(kind, newest.date(), newest.generation())) == null) {
                Thread.ofVirtual().name("cassandra-warm-" + kind).start(() -> {
                    try {
                        dayColumns(kind, newest);
                    } catch (RuntimeException e) {
                        // the first search reads them instead
                    }
                });
            }
        });
    }

    private boolean effective(String kind) {
        return "effective".equals(modes.get(kind));
    }

    // ---- one entity ------------------------------------------------------------------------------------------------------------

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
        if (!configuredKinds.isEmpty() && !configuredKinds.contains(ref.kind())) {
            return Optional.empty();
        }
        NavigableMap<LocalDate, CassandraLayout.Day> days = days(ref.kind());
        CassandraLayout.Day day = effective(ref.kind()) ? entityDay(ref, days, asOf.businessDate())
                : DayReads.floor(days, asOf.businessDate(), lookbackDays);
        if (day == null) {
            return Optional.empty();
        }
        String json = document(ref, day);
        if (json == null) {
            return Optional.empty();                           // not in that day's snapshot (gone), or the day's rows expired
        }
        return Optional.of(new EntityDocument(ref, parse(json), new Provenance(sourceName, day.generation(), Instant.now(), false, day.date())));
    }

    /**
     * An effective entity's latest day on or before the date that the catalogue lists: a later day being loaded (not yet named
     * in {@code kind_days}) or dropped by retention is passed over, as readers of the kind do.
     */
    private CassandraLayout.Day entityDay(EntityRef ref, NavigableMap<LocalDate, CassandraLayout.Day> days, LocalDate asked) {
        if (days.isEmpty()) {
            return null;
        }
        List<Row> rows = asked == null
                ? store.execute(CassandraLayout.selectEntityDays(keyspace), ref.kind(), ref.id(), ENTITY_DAYS_PROBE).all()
                : store.execute(CassandraLayout.selectEntityDaysUpTo(keyspace), ref.kind(), ref.id(), asked, ENTITY_DAYS_PROBE).all();
        for (Row r : rows) {
            CassandraLayout.Day d = days.get(r.getLocalDate(0));
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /**
     * History queries (HISTORY_QUERIES.md section 10): the entity's days from {@code entity_days} (one single-partition range read), kept
     * only where {@code kind_days} still names the day (an {@code entity_days} row may outlive its data). Reads no document.
     */
    @Override
    public Optional<java.util.NavigableSet<LocalDate>> days(EntityRef ref, LocalDate from, LocalDate to) {
        if (!configuredKinds.isEmpty() && !configuredKinds.contains(ref.kind())) {
            return Optional.of(new java.util.TreeSet<>());
        }
        NavigableMap<LocalDate, CassandraLayout.Day> held = days(ref.kind());
        java.util.TreeSet<LocalDate> out = new java.util.TreeSet<>();
        if (held.isEmpty()) {
            return Optional.of(out);
        }
        for (Row r : store.execute(CassandraLayout.selectEntityDaysBetween(keyspace), ref.kind(), ref.id(), from, to).all()) {
            LocalDate d = r.getLocalDate(0);
            if (held.containsKey(d)) {
                out.add(d);
            }
        }
        return Optional.of(out);
    }

    /**
     * The entity on each of its days in the request: the days from {@link #days(EntityRef, LocalDate, LocalDate)}, then one token-aware point
     * read per day (generation and bucket from the catalogue), {@code parallelDays} at once. The documents cannot come from one query: the
     * business date is part of the data table's partition key. One generation is kept, so {@code versions} is 1.
     */
    @Override
    public com.ash.drishti.api.HistoryRead history(com.ash.drishti.api.HistoryRequest r) throws Exception {
        if (!configuredKinds.isEmpty() && !configuredKinds.contains(r.ref().kind())) {
            return com.ash.drishti.api.HistoryRead.NONE;
        }
        NavigableMap<LocalDate, CassandraLayout.Day> held = days(r.ref().kind());
        List<LocalDate> dates = new java.util.ArrayList<>(com.ash.drishti.api.HistoryReads.dates(this, r));
        java.util.Collections.reverse(dates);                                       // newest first, for the last N days
        long deadline = System.nanoTime() + r.budget().toNanos();
        List<com.ash.drishti.api.HistoryDay> out = new java.util.ArrayList<>();
        java.util.Map<LocalDate, String> failed = new java.util.TreeMap<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < dates.size() && (r.lastDays() == 0 || out.size() < r.lastDays()); i += r.parallelDays()) {
                List<LocalDate> chunk = dates.subList(i, Math.min(dates.size(), i + r.parallelDays()));
                List<java.util.concurrent.Future<String>> reads = new java.util.ArrayList<>();
                for (LocalDate d : chunk) {
                    reads.add(pool.submit(() -> document(r.ref(), held.get(d))));
                }
                for (int k = 0; k < chunk.size(); k++) {
                    LocalDate d = chunk.get(k);
                    try {
                        String json = reads.get(k).get(Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
                        if (json != null && (r.lastDays() == 0 || out.size() < r.lastDays())) {
                            EntityDocument doc = new EntityDocument(r.ref(), parse(json),
                                    new Provenance(sourceName, held.get(d).generation(), Instant.now(), false, d));
                            out.add(new com.ash.drishti.api.HistoryDay(d, com.ash.drishti.api.HistoryReads.project(doc, r.fields()), 1));
                        }
                    } catch (java.util.concurrent.TimeoutException e) {
                        reads.get(k).cancel(true);
                        failed.put(d, com.ash.drishti.api.HistoryReads.TIMED_OUT);
                    } catch (java.util.concurrent.ExecutionException e) {
                        failed.put(d, String.valueOf(e.getCause() == null ? e : e.getCause().getMessage()));
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new com.ash.drishti.api.HistoryRead(out, java.util.Set.of(), failed);
    }

    private String document(EntityRef ref, CassandraLayout.Day day) {
        DocKey key = new DocKey(ref.kind(), ref.id(), day.date(), day.generation());
        String cached = documents.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Row r = store.execute(CassandraLayout.selectDoc(keyspace, table(ref.kind())), day.date(), day.generation(),
                (short) CassandraLayout.bucket(ref.id(), day.buckets()), ref.id()).one();
        String json = r == null ? null : r.getString(0);
        if (json != null) {
            documents.put(key, json);
        }
        return json;
    }

    private DataNode parse(String json) {
        try {
            return context.parseJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- a day's columns -------------------------------------------------------------------------------------------------------

    /** The declared promoted paths that the kind's table has a column for (an effective kind has none: its days hold changes only). */
    @Override
    public Set<String> columnar(String kind) {
        List<String> paths = promoted.get(kind);
        if (paths == null || effective(kind) || !store.connected()) {
            return Set.of();
        }
        Optional<TableMetadata> t = store.table(keyspace, table(kind));
        if (t.isEmpty()) {
            return Set.of();
        }
        Map<String, Boolean> cols = CassandraStore.promotedColumns(t.get());
        Set<String> out = new java.util.LinkedHashSet<>();
        paths.forEach(p -> {
            if (cols.containsKey(CassandraLayout.column(p))) {
                out.add(p);
            }
        });
        return out;
    }

    @Override
    public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
        if (!columnar(kind).containsAll(paths)) {
            return Optional.empty();
        }
        CassandraLayout.Day day = DayReads.floor(days(kind), asOf.businessDate(), lookbackDays);
        if (day == null) {
            return Optional.empty();                           // a day this store does not hold: the next connector is asked
        }
        ColumnSet all = dayColumns(kind, day);
        if (all.size() == 0) {
            return Optional.empty();                           // the day's rows expired (TTL): an older store may hold it
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

    /** Every promoted column of one day's generation, read once and kept (a reload's new generation is a new entry). */
    private ColumnSet dayColumns(String kind, CassandraLayout.Day day) {
        return columnSets.get(new DayKey(kind, day.date(), day.generation()), k -> {
            Map<String, Boolean> cols = store.table(keyspace, table(kind)).map(CassandraStore::promotedColumns).orElse(Map.of());
            Map<String, String> byPath = new LinkedHashMap<>();
            for (String p : columnar(kind)) {
                byPath.put(p, CassandraLayout.column(p));
            }
            return reads.columns(table(kind), day, byPath, cols);
        });
    }

    private static long weight(ColumnSet c) {
        long w = 64L + 56L * c.size();
        for (String id : c.ids()) {
            w += 2L * id.length();
        }
        return w + 8L * c.size() * (c.numbers().size() + c.texts().size());
    }

    // ---- reverse lookups and documents ------------------------------------------------------------------------------------------

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
        for (String k : kind == null ? catalogue.keySet() : Set.of(kind)) {
            NavigableMap<LocalDate, CassandraLayout.Day> days = catalogue.get(k);
            if (days == null || days.isEmpty()) {
                continue;                                      // a kind this keyspace does not hold: nothing is read
            }
            Set<String> found = new TreeSet<>();
            try {
                Set<String> columnar = columnar(k);
                if (!columnar.isEmpty()) {
                    // promoted link columns (nettingSet, PORTFOLIO, COUNTERPARTY …): no document is read
                    columns(k, columnar, asOf).ifPresent(c -> c.texts().values().forEach(values -> {
                        for (int i = 0; i < values.length; i++) {
                            if (target.id().equals(values[i])) {
                                found.add(c.ids()[i]);
                            }
                        }
                    }));
                } else {
                    found.addAll(scanReferences(k, target.id(), days, asOf.businessDate()));
                }
            } catch (RuntimeException e) {
                continue;                                      // failed: no referrers from here, not an error page
            }
            found.forEach(i -> out.add(EntityRef.of(k, i)));
        }
        return out;
    }

    /**
     * Entities whose documents mention the target, for a kind without promoted columns: a snapshot kind's day, or for an effective
     * kind each entity's latest document on or before the date (days newest first, the first seen of an entity wins); at most
     * {@code max-load-rows} documents are read.
     */
    private Set<String> scanReferences(String kind, String target, NavigableMap<LocalDate, CassandraLayout.Day> days, LocalDate asked) {
        Collection<CassandraLayout.Day> scan;
        if (effective(kind)) {
            scan = (asked == null ? days : days.headMap(asked, true)).descendingMap().values();
        } else {
            CassandraLayout.Day d = DayReads.floor(days, asked, lookbackDays);
            scan = d == null ? List.of() : List.of(d);
        }
        Map<String, Boolean> mentions = new HashMap<>();
        long budget = maxLoadRows;
        for (CassandraLayout.Day d : scan) {
            if (budget <= 0) {
                break;
            }
            budget -= reads.documents(table(kind), d, budget, (id, json) -> mentions.putIfAbsent(id, ReferenceScanner.referencedIds(json).contains(target)));
        }
        Set<String> out = new TreeSet<>();
        mentions.forEach((id, yes) -> {
            if (yes) {
                out.add(id);
            }
        });
        return out;
    }

    /** A snapshot kind's day in one pass, bucket by bucket (derived pages); an effective kind is read entity by entity. */
    @Override
    public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
        if (effective(kind)) {
            return false;
        }
        CassandraLayout.Day day = DayReads.floor(days(kind), asOf.businessDate(), lookbackDays);
        if (day == null) {
            return true;                                       // the store does not hold the date: nothing
        }
        Instant now = Instant.now();
        reads.documents(table(kind), day, 0, (id, json) -> sink.accept(new EntityDocument(EntityRef.of(kind, id), parse(json),
                new Provenance(sourceName, day.generation(), now, false, day.date()))));
        return true;
    }

    // ---- what the store holds ---------------------------------------------------------------------------------------------------

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    /** Each day's row count as the loader recorded it in {@code kind_days}, newest first (read now: Test connection). */
    @Override
    public Map<LocalDate, Long> inventory(String kind, int maxDates) {
        Map<LocalDate, Long> out = new LinkedHashMap<>();
        try {
            for (CassandraLayout.Day d : kindDays(kind).descendingMap().values()) {
                if (out.size() >= maxDates) {
                    break;
                }
                out.put(d.date(), d.rows());
            }
        } catch (RuntimeException e) {
            return Map.of();
        }
        return out;
    }

    @Override
    public Optional<LocalDate> latestDate(String kind) {
        NavigableMap<LocalDate, CassandraLayout.Day> d = catalogue.get(kind);
        return d == null || d.isEmpty() ? Optional.empty() : Optional.of(d.lastKey());
    }

    @Override
    public Optional<LocalDate> earliestDate(String kind) {
        NavigableMap<LocalDate, CassandraLayout.Day> d = catalogue.get(kind);
        return d == null || d.isEmpty() ? Optional.empty() : Optional.of(d.firstKey());
    }

    /** The kind's days and their generations: a new day, a reload or a dropped day changes it. */
    @Override
    public Optional<String> dataVersion(String kind) {
        NavigableMap<LocalDate, CassandraLayout.Day> d = catalogue.get(kind);
        if (d == null) {
            return Optional.empty();
        }
        if (d.isEmpty()) {
            return Optional.of("empty");
        }
        int h = 1;
        for (CassandraLayout.Day day : d.values()) {
            h = 31 * h + day.date().hashCode() * 17 + day.generation();
        }
        return Optional.of(d.lastKey() + "#" + d.lastEntry().getValue().generation() + "/" + d.size() + "/" + Integer.toHexString(h));
    }

    /** The day a read of {@code date} is served from, and its generation: a later day or another day's reload leaves it valid. */
    @Override
    public Optional<String> dataVersion(String kind, LocalDate date) {
        if (date == null) {
            return dataVersion(kind);
        }
        NavigableMap<LocalDate, CassandraLayout.Day> d = catalogue.get(kind);
        if (d == null) {
            return Optional.empty();
        }
        CassandraLayout.Day day = DayReads.floor(d, date, lookbackDays);
        return Optional.of(day == null ? date + "#none" : day.date() + "#" + day.generation());
    }

    @Override
    public Optional<String> tableIdentity(String kind) {
        return store.clusterName().map(c -> "cassandra:" + c + "/" + keyspace + "." + table(kind));
    }

    @Override
    public boolean holdsNone(String kind) {
        NavigableMap<LocalDate, CassandraLayout.Day> d = catalogue.get(kind);
        return d != null && d.isEmpty();
    }

    @Override
    public Instant lastUpdate() {
        return lastUpdate;
    }

    @Override
    public Map<String, Object> cacheStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kinds", catalogue.size());
        m.put("daysIndexed", catalogue.values().stream().mapToInt(Map::size).sum());
        m.put("ids", index.size());
        m.put("columnSets", columnSets.estimatedSize());
        m.put("documents", documents.estimatedSize());
        return m;
    }

    /** Forgets the columns and documents read, nothing else (the measurements' first reads). */
    void dropCaches() {
        columnSets.invalidateAll();
        documents.invalidateAll();
    }

    /** Forgets the columns and documents read and re-reads the catalogue and the ids. */
    @Override
    public void purgeCaches() {
        columnSets.invalidateAll();
        documents.invalidateAll();
        hitsBuiltFor.clear();
        try {
            refresh();
        } catch (RuntimeException e) {
            catalogueProblem = e.getMessage();
        }
    }

    @Override
    public String health() {
        if (store == null || !store.connected()) {
            String why = store == null ? null : store.lastError();
            String probed = store == null ? null : probed();
            return "DOWN: connecting to Cassandra at " + contactPoints + (why == null ? "" : " (" + why + ")") + (probed == null ? "" : ": " + probed);
        }
        int[] nodes = store.nodes();
        if (nodes[0] == 0) {
            return "DOWN: no Cassandra node is up (0 of " + nodes[1] + ")";
        }
        String problem = catalogueProblem;
        if (problem != null) {
            return "DOWN: " + problem;
        }
        String up = nodes[0] == nodes[1] ? "UP" : "UP (" + nodes[0] + " of " + nodes[1] + " nodes up)";
        return tls == null ? up : tls.annotate(up, Instant.now());
    }

    /**
     * The driver's pools added up over the nodes: {@code connections-per-node} each. A Cassandra connection carries many requests
     * at once, so "in use" counts the connections busy with the requests in flight; requests beyond {@code max-concurrent-reads}
     * wait in the driver's queue. The driver does not time borrows.
     */
    @Override
    public Optional<PoolStats> poolStats() {
        if (store == null) {
            return Optional.empty();
        }
        int[] nodes = store.nodes();
        int open = store.openConnections();
        int flying = store.inFlight();
        int inUse = Math.min(open, flying);
        return Optional.of(new PoolStats("cassandra", connectionsPerNode * Math.max(1, nodes[1]), open, inUse, Math.max(0, open - inUse),
                Math.max(0, flying - maxConcurrent), -1, 0, 0));
    }

    // ---- why it cannot connect ------------------------------------------------------------------------------------------------

    private volatile List<java.net.InetSocketAddress> probePoints = List.of();
    private volatile Duration probeTimeout = Duration.ofSeconds(5);
    private volatile java.util.concurrent.CompletableFuture<Optional<String>> probe;
    private volatile long probedAt;
    private volatile String lastProbed;
    private volatile boolean closed;
    private volatile java.util.concurrent.ScheduledFuture<?> refreshTask;
    /** A probe is repeated at most this often while the connector cannot connect. */
    static final long PROBE_EVERY_MS = 30_000;

    /** Starts a probe of the first contact point on a virtual thread, unless one ran moments ago or the session is connected. */
    private void probeLater() {
        if (closed || probePoints.isEmpty() || store != null && store.connected()) {
            return;
        }
        java.util.concurrent.CompletableFuture<Optional<String>> p = probe;
        if (p != null && (!p.isDone() || System.currentTimeMillis() - probedAt < PROBE_EVERY_MS)) {
            return;
        }
        java.util.concurrent.CompletableFuture<Optional<String>> next = new java.util.concurrent.CompletableFuture<>();
        probe = next;
        probedAt = System.currentTimeMillis();
        List<java.net.InetSocketAddress> points = probePoints;
        Thread.ofVirtual().name("cassandra-probe-" + sourceName).start(() -> {
            try {
                Optional<String> words = CassandraProbe.diagnose(points, tls, probeTimeout);
                lastProbed = words.orElse(null);
                next.complete(words);
            } catch (RuntimeException e) {
                next.complete(Optional.empty());
            }
            probedAt = System.currentTimeMillis();
        });
    }

    /** The probe's words (the first one waited for briefly, so the first look at Health has them), or null. */
    private String probed() {
        probeLater();
        java.util.concurrent.CompletableFuture<Optional<String>> p = probe;
        if (p == null) {
            return null;
        }
        if (!p.isDone() && lastProbed != null) {
            return lastProbed;                                 // a later probe running: the last words meanwhile
        }
        try {
            return p.get(Math.min(2_000, probeTimeout.toMillis() + 500), TimeUnit.MILLISECONDS).orElse(null);
        } catch (java.util.concurrent.TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            return null;
        }
    }

    /** Stops the refresh and closes the session, also one still connecting (QA3B-05: a session leaked at every restart). */
    @Override
    public void close() {
        closed = true;
        java.util.concurrent.ScheduledFuture<?> t = refreshTask;
        if (t != null) {
            t.cancel(false);
        }
        if (store != null) {
            store.close();
        }
    }
}
