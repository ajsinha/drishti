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
package com.ash.drishti.plugin.mongodb;

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
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonType;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * Serves entities from MongoDB, laid out for a million entities a day kept for years ({@link MongoLayout}): a document
 * per entity per business date in the domain's collection. A {@code snapshot} kind reads the kind's newest date on or
 * before the one asked (within {@code lookback-days}) by its exact {@code _id}, and an entity without a document that
 * day is gone; an {@code effective} kind reads the last {@code _id} of the entity's range on or before the date. Both
 * are one indexed read. The kinds' dates come from distinct scans of the {@code day_ids} index and type-ahead from the
 * newest day's ids (a covered query), every {@code refresh-seconds}. Searches, pick lists, derived kinds, impact and
 * reverse lookups read a day's promoted fields ({@code layout.<kind>.columns}) as columns from the narrow
 * {@code <collection>_columns} collection (or, without it, from the documents), in {@code read-threads} id ranges at once, kept by memory ({@code columns-cache-mb}, 1024) and re-read in the background after
 * {@code columns-seconds} (300) while the last read is served; the newest day is read after each refresh. Reverse
 * lookups without promoted link fields scan at most {@code max-load-rows} (200000) of the day's documents.
 *
 * <p>Settings: {@code uri} ({@code mongodb://localhost:27017}, credentials and replica set options included),
 * {@code database} ({@code drishti}), {@code collection} (the data domain), {@code read-preference} ({@code primary};
 * {@code secondaryPreferred} reads from secondaries), {@code kinds}, {@code mode.<kind>}, {@code reverse-index} (true),
 * {@code lookback-days} (10), {@code refresh-seconds} (60), {@code read-threads} (8), {@code heavy-reads} (2),
 * {@code batch-size} (5000), {@code connect-timeout-ms} (3000), {@code source-name} ({@code mongodb}).
 */
public final class MongoSourcePlugin implements SourcePlugin {

    private record DayKey(String kind, LocalDate date) {}

    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private static final BsonDocument DAY_HINT = new BsonDocument(MongoLayout.KIND, new BsonInt32(1)).append(MongoLayout.DATE, new BsonInt32(1))
            .append(MongoLayout.ID, new BsonInt32(1));

    private final Map<String, String> modes = new HashMap<>();
    private final Map<String, List<String>> promoted = new ConcurrentHashMap<>();
    private final Map<String, List<String>> boundaries = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    private volatile Map<String, NavigableSet<LocalDate>> kindDates = Map.of();
    private volatile List<String> missingIndexes = List.of();
    private volatile Instant lastUpdate;
    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong dayReads = new AtomicLong();
    private MongoClient client;
    private MongoCollection<RawBsonDocument> docs;
    private MongoCollection<BsonDocument> rows;
    private MongoDatabase database;
    private String collection;
    private SourceContext context;
    private String sourceName;
    private int lookbackDays;
    private int maxLoadRows;
    private int readThreads;
    private List<String> configuredKinds = List.of();
    private boolean reverseIndex = true;
    /** A day's columns come from the {@code _columns} collection when it exists, else from the documents. */
    private volatile ColumnReader reader;
    private ColumnReader narrowReader;
    private ColumnReader documentReader;
    private volatile boolean narrow;
    private LoadingCache<DayKey, ColumnSet> columnSets;
    /** Reads of a whole day (a million documents' promoted fields) at once per connector; more wait. */
    private Semaphore heavy;

    @Override
    public PluginManifest manifest() {
        Set<String> kinds = configuredKinds.isEmpty() ? kindDates.keySet() : new HashSet<>(configuredKinds);
        return new PluginManifest(sourceName == null ? "mongodb" : sourceName, "1.0", kinds, new SourceCapabilities(false, reverseIndex, true, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "mongodb");
        this.collection = ctx.setting("collection", ctx.setting("domain", "drishti"));
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        this.maxLoadRows = Integer.parseInt(ctx.setting("max-load-rows", "200000"));
        this.reverseIndex = Boolean.parseBoolean(ctx.setting("reverse-index", "true"));
        this.readThreads = Math.max(1, Integer.parseInt(ctx.setting("read-threads", "8")));
        this.heavy = new Semaphore(Math.max(1, Integer.parseInt(ctx.setting("heavy-reads", "2"))));
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
        int timeout = Integer.parseInt(ctx.setting("connect-timeout-ms", "3000"));
        MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(ctx.setting("uri", "mongodb://localhost:27017")))
                .readPreference(ReadPreference.valueOf(ctx.setting("read-preference", "primary")))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(timeout, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(timeout, TimeUnit.MILLISECONDS)).build();
        // the client connects in the background: start even when the server is not reachable yet (health says so)
        this.client = MongoClients.create(settings);
        this.database = client.getDatabase(ctx.setting("database", "drishti"));
        this.docs = database.getCollection(collection, RawBsonDocument.class);
        this.rows = database.getCollection(collection, BsonDocument.class);
        int batch = Integer.parseInt(ctx.setting("batch-size", "5000"));
        this.narrowReader = new ColumnReader(database.getCollection(MongoLayout.columnsCollection(collection), BsonDocument.class), batch);
        this.documentReader = new ColumnReader(rows, batch);
        this.reader = documentReader;
        this.columnSets = Caffeine.newBuilder().maximumWeight(Long.parseLong(ctx.setting("columns-cache-mb", "1024")) * 1024 * 1024)
                .weigher((DayKey k, ColumnSet v) -> (int) Math.min(Integer.MAX_VALUE, weight(v)))
                .refreshAfterWrite(Duration.ofSeconds(Long.parseLong(ctx.setting("columns-seconds", "300"))))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build(k -> readDay(k.kind(), k.date()));
        refresh();
        long refresh = Long.parseLong(ctx.setting("refresh-seconds", "60"));
        ctx.scheduler().scheduleWithFixedDelay(this::refresh, refresh, refresh, TimeUnit.SECONDS);
    }

    /**
     * The kinds and their business dates (distinct scans of the {@code day_ids} index) and the ids for type-ahead (the
     * newest day's ids, a covered query; an effective kind's from its {@code _id} range): no document is read.
     */
    void refresh() {
        Map<String, NavigableSet<LocalDate>> dates = new ConcurrentHashMap<>();
        List<EntityHit> hits = new ArrayList<>();
        try {
            List<String> missing = new ArrayList<>(MongoLayout.missingIndexes(rows));
            narrow = database.listCollectionNames().into(new ArrayList<>()).contains(MongoLayout.columnsCollection(collection));
            if (narrow) {
                MongoLayout.missingIndexes(database.getCollection(MongoLayout.columnsCollection(collection)))
                        .forEach(m -> missing.add(m + " on " + MongoLayout.columnsCollection(collection)));
            }
            missingIndexes = missing;
            reader = narrow ? narrowReader : documentReader;
            List<String> kinds = configuredKinds.isEmpty() ? rows.distinct(MongoLayout.KIND, String.class).into(new ArrayList<>()) : configuredKinds;
            for (String kind : kinds) {
                NavigableSet<LocalDate> ds = new TreeSet<>();
                rows.distinct(MongoLayout.DATE, Filters.eq(MongoLayout.KIND, kind), Integer.class).forEach(d -> ds.add(MongoLayout.date(d)));
                if (ds.isEmpty()) {
                    continue;
                }
                dates.put(kind, ds);
                List<String> ids = effective(kind) ? effectiveIds(kind) : dayIds(kind, ds.last());
                String subtitle = kind + " · " + sourceName;
                ids.forEach(id -> hits.add(new EntityHit(EntityRef.of(kind, id), id, subtitle)));
                if (!ids.isEmpty()) {
                    List<String> cuts = new ArrayList<>();
                    for (int i = 1; i < readThreads; i++) {
                        cuts.add(ids.get((int) ((long) i * ids.size() / readThreads)));
                    }
                    boundaries.put(kind, cuts);
                }
            }
        } catch (MongoException e) {
            return;   // keep the last catalogue; health reports the connection
        }
        if (!dates.equals(kindDates) || hits.size() != index.size()) {
            lastUpdate = Instant.now();
        }
        kindDates = dates;
        index.replaceAll(hits);
        // the newest day's columns, ready before the first search (a background thread; searches find them cached)
        promoted.forEach((kind, paths) -> {
            NavigableSet<LocalDate> ds = dates.get(kind);
            if (ds != null && !effective(kind) && columnSets.getIfPresent(new DayKey(kind, ds.last())) == null) {
                Thread.ofVirtual().name("mongodb-warm-" + kind).start(() -> {
                    try {
                        java.util.Objects.requireNonNull(columnSets.get(new DayKey(kind, ds.last())));
                    } catch (RuntimeException e) {
                        // the first search reads it instead
                    }
                });
            }
        });
    }

    /** A day's ids in id order, from the {@code day_ids} index alone (a covered query: no document is read). */
    private List<String> dayIds(String kind, LocalDate day) {
        List<String> ids = new ArrayList<>();
        try (MongoCursor<BsonDocument> it = rows.find(Filters.and(Filters.eq(MongoLayout.KIND, kind), Filters.eq(MongoLayout.DATE, MongoLayout.day(day))))
                .projection(Projections.fields(Projections.excludeId(), Projections.include(MongoLayout.ID))).sort(Sorts.ascending(MongoLayout.ID))
                .hint(DAY_HINT).batchSize(20_000).iterator()) {
            while (it.hasNext()) {
                BsonValue id = it.next().get(MongoLayout.ID);
                if (id != null && id.isString()) {
                    ids.add(id.asString().getValue());
                }
            }
        }
        return ids;
    }

    /** An effective kind's ids from its keys ({@code kind/id/yyyyMMdd}, a covered read of the {@code _id} index). */
    private List<String> effectiveIds(String kind) {
        List<String> ids = new ArrayList<>();
        try (MongoCursor<BsonDocument> it = rows.find(Filters.and(Filters.gte(MongoLayout.KEY, kind + "/"), Filters.lt(MongoLayout.KEY, kind + "0")))
                .projection(Projections.include(MongoLayout.KEY)).sort(Sorts.ascending(MongoLayout.KEY)).batchSize(20_000).iterator()) {
            while (it.hasNext()) {
                String key = it.next().getString(MongoLayout.KEY).getValue();
                int end = key.lastIndexOf('/');
                String id = end > kind.length() ? key.substring(kind.length() + 1, end) : null;
                if (id != null && (ids.isEmpty() || !ids.get(ids.size() - 1).equals(id))) {
                    ids.add(id);
                }
            }
        }
        return ids;
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
        if (configuredKinds.isEmpty() ? !kindDates.containsKey(ref.kind()) : !configuredKinds.contains(ref.kind())) {
            return Optional.empty();                           // a kind this collection does not hold (or MongoDB not reached yet)
        }
        reads.incrementAndGet();
        LocalDate asked = asOf.businessDate();
        Bson projection = Projections.include(MongoLayout.DOC, MongoLayout.DATE);
        RawBsonDocument r;
        if (effective(ref.kind()) || kindDates.get(ref.kind()) == null) {
            // the entity's last day on or before the date: the last key of its range (kind/id/ up to kind/id/yyyyMMdd)
            String prefix = MongoLayout.entityPrefix(ref.kind(), ref.id());
            Bson range = Filters.and(Filters.gte(MongoLayout.KEY, prefix),
                    Filters.lte(MongoLayout.KEY, prefix + (asked == null ? "99999999" : MongoLayout.day(asked))), Filters.eq(MongoLayout.ID, ref.id()));
            r = docs.find(range).projection(projection).sort(Sorts.descending(MongoLayout.KEY)).limit(1).first();
        } else {
            Optional<LocalDate> day = snapshotDate(ref.kind(), asked);
            if (day.isEmpty()) {
                return Optional.empty();
            }
            r = docs.find(Filters.eq(MongoLayout.KEY, MongoLayout.docKey(ref.kind(), ref.id(), day.get()))).projection(projection).first();
        }
        String json = r == null ? null : json(r.get(MongoLayout.DOC));
        if (json == null) {
            return Optional.empty();
        }
        int day = r.getInt32(MongoLayout.DATE).getValue();
        var data = context.parseJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return Optional.of(new EntityDocument(ref, data, new Provenance(sourceName, day, Instant.now(), false, MongoLayout.date(day))));
    }

    /** The stored document as JSON: the text itself, or an embedded document written out (relaxed extended JSON). */
    private static String json(BsonValue doc) {
        if (doc == null) {
            return null;
        }
        return doc.isString() ? doc.asString().getValue() : doc.isDocument() ? doc.asDocument().toJson(RELAXED) : null;
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
            return Optional.empty();                           // a date this collection does not hold: another source may
        }
        ColumnSet all = columnSets.get(new DayKey(kind, day.get()));
        if (all.size() == 0) {
            return Optional.empty();                           // the day was deleted (retention): an older store may hold it
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

    /** One day of a kind as columns, at most {@code heavy-reads} at once. */
    private ColumnSet readDay(String kind, LocalDate day) {
        heavy.acquireUninterruptibly();
        try {
            dayReads.incrementAndGet();
            return reader.read(kind, day, promoted.getOrDefault(kind, List.of()), boundaries.getOrDefault(kind, List.of()));
        } finally {
            heavy.release();
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
                continue;                                      // a kind this domain does not hold: no read
            }
            Set<String> found = new TreeSet<>();
            Optional<LocalDate> day = effective(k) ? Optional.empty() : snapshotDate(k, asOf.businessDate());
            Optional<ColumnSet> cols = day.isPresent() && !columnar(k).isEmpty() ? columns(k, columnar(k), asOf) : Optional.empty();
            if (cols.isPresent()) {
                // promoted link fields (nettingSet, book, counterparty.id …): no document is read
                ColumnSet c = cols.get();
                c.texts().values().forEach(values -> {
                    for (int i = 0; i < values.length; i++) {
                        if (target.id().equals(values[i])) {
                            found.add(c.ids()[i]);
                        }
                    }
                });
            } else if (effective(k) || day.isPresent()) {
                try {
                    found.addAll(scanReferences(k, target.id(), day.orElse(null), asOf.businessDate()));
                } catch (MongoException e) {
                    continue;                                  // failed: no referrers from here, not an error page
                }
            }
            found.forEach(i -> out.add(EntityRef.of(k, i)));
        }
        return out;
    }

    /**
     * Entities whose documents mention the target, for a kind without promoted fields: the day's documents (or, for an
     * effective kind, each entity's latest document on or before the date), at most {@code max-load-rows}. A document
     * stored as text is matched on the server first ({@code "<id>"} in it), so only candidates travel.
     */
    private Set<String> scanReferences(String kind, String target, LocalDate day, LocalDate asked) {
        Bson scope = day != null ? Filters.and(Filters.eq(MongoLayout.KIND, kind), Filters.eq(MongoLayout.DATE, MongoLayout.day(day)))
                : Filters.and(Filters.gte(MongoLayout.KEY, kind + "/"), Filters.lt(MongoLayout.KEY, kind + "0"),
                        Filters.lte(MongoLayout.DATE, asked == null ? 99_999_999 : MongoLayout.day(asked)));
        Bson mentions = Filters.or(Filters.regex(MongoLayout.DOC, Pattern.quote("\"" + target + "\"")), Filters.type(MongoLayout.DOC, BsonType.DOCUMENT));
        Map<String, Boolean> latest = new LinkedHashMap<>();             // id -> its latest document mentions the target
        Bson filter = day != null ? Filters.and(scope, mentions) : scope; // effective: every version, to know which is latest
        try (MongoCursor<RawBsonDocument> it = docs.find(filter).projection(Projections.include(MongoLayout.ID, MongoLayout.DOC))
                .sort(Sorts.ascending(MongoLayout.KEY)).limit(maxLoadRows).batchSize(1000).iterator()) {
            while (it.hasNext()) {
                RawBsonDocument r = it.next();
                String json = json(r.get(MongoLayout.DOC));
                BsonValue id = r.get(MongoLayout.ID);
                if (id != null && id.isString() && json != null) {
                    latest.put(id.asString().getValue(), json.contains(target) && ReferenceScanner.referencedIds(json).contains(target));
                }
            }
        }
        Set<String> out = new TreeSet<>();
        latest.forEach((id, yes) -> {
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
    public Instant lastUpdate() {
        return lastUpdate;
    }

    @Override
    public Map<String, Object> cacheStats() {
        long bytes = columnSets.policy().eviction().map(e -> e.weightedSize().orElse(0L)).orElse(0L);
        return Map.of("kinds", kindDates.size(), "datesIndexed", kindDates.values().stream().mapToInt(Set::size).sum(), "ids", index.size(),
                "columnSets", columnSets.estimatedSize(), "columnSetsMb", bytes / (1024 * 1024), "documentReads", reads.get(), "dayReads", dayReads.get());
    }

    /** Re-reads the kinds' dates and ids, and forgets the columns read. */
    @Override
    public void purgeCaches() {
        columnSets.invalidateAll();
        refresh();
    }

    @Override
    public String health() {
        try {
            database.runCommand(new Document("ping", 1));
        } catch (MongoException e) {
            return "DOWN: cannot reach MongoDB (" + e.getMessage() + ")";
        }
        if (kindDates.isEmpty()) {
            return "DOWN: no documents in " + database.getName() + "." + collection;
        }
        if (!missingIndexes.isEmpty()) {
            return "UP (not laid out: missing index " + String.join(", ", missingIndexes) + "; dates, ids and searches scan the collection)";
        }
        return narrow || promoted.isEmpty() ? "UP"
                : "UP (no " + MongoLayout.columnsCollection(collection) + " collection: a day's columns are read from the documents, 3 to 4 times slower)";
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
