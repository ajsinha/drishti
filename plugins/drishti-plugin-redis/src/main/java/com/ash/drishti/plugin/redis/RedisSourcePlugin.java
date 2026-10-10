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
package com.ash.drishti.plugin.redis;

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
import com.ash.drishti.api.Subscription;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.KeyValue;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Serves entities from Redis, the store for today and the last few days (everything Redis holds is in memory; years of
 * history belong in Delta Lake or Iceberg behind it, and the router asks those when this answers "not held"). The layout
 * ({@link RedisLayout}, written by {@link RedisLoader}) keeps a compressed document per entity per business date, each
 * entity's days, each kind's days, and each day's ids and promoted fields column-wise in chunks. A {@code snapshot}
 * read is one {@code GET} of the kind's newest day on or before the date asked (within {@code lookback-days}); an
 * {@code effective} read finds the entity's latest days on or before it and reads their documents (two round trips).
 * A day is read in its current generation ({@link RedisLayout}): a loader that replaces a day switches it whole, and
 * the connector learns the switch from the day's announcement, from its catalogue refresh, or when a read of the
 * generation it knows finds nothing. Type-ahead comes from the newest day's ids; searches, pick lists, derived kinds, impact
 * and reverse lookups read a day's column hash ({@link ColumnReader}), kept by memory ({@code columns-cache-mb}, 1024)
 * and re-read only when a loader has rewritten it. With {@code live} (true) the connector listens on
 * {@code <domain>:changes} and pushes changed entities to open views.
 *
 * <p>Settings: {@code uri} (required, e.g. {@code redis://localhost:6379}; {@code rediss://} for TLS, credentials in the URI or in
 * {@code user}/{@code password}; several URIs or {@code cluster: true} for Redis Cluster), {@code domain} (the data
 * domain, e.g. {@code trading}), {@code kinds}, {@code mode.<kind>}, {@code layout.<kind>.columns}, {@code lookback-days}
 * (10), {@code refresh-seconds} (60), {@code columns-cache-mb} (1024), {@code heavy-reads} (2), {@code max-load-rows}
 * (200000), {@code reverse-index} (true), {@code live} (true), {@code timeout-ms} (5000), {@code fail-fast} (true: while
 * the store is known to be down, reads fail at once instead of waiting for {@code timeout-ms}; {@link FailFast}),
 * {@code recheck-ms} (1000: how often a down store is asked again), {@code source-name}.
 */
public final class RedisSourcePlugin implements SourcePlugin {

    private record DayKey(String kind, LocalDate date) {}

    /** A day's columns as read, with the generation and version of the hash they came from. */
    private record Loaded(String gen, long version, ColumnSet columns) {}

    /** What a read found: the business day and the stored value. */
    private record Found(LocalDate day, byte[] value) {}

    /** The entity's days an effective read considers, newest first: the newest with a document in its day's generation wins. */
    private static final int CANDIDATE_DAYS = 8;

    private final Map<String, String> modes = new HashMap<>();
    private final Map<String, List<String>> promoted = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    private final Map<String, Long> indexed = new ConcurrentHashMap<>();
    private final Map<String, List<EntityHit>> hitsByKind = new ConcurrentHashMap<>();
    private final Map<DayKey, ReentrantLock> loading = new ConcurrentHashMap<>();
    private final Map<EntityRef, List<Consumer<EntityDocument>>> listeners = new ConcurrentHashMap<>();
    private final ReentrantLock refreshing = new ReentrantLock();
    private final ReentrantLock connecting = new ReentrantLock();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private volatile Map<String, NavigableSet<LocalDate>> kindDates = Map.of();
    private volatile Map<String, Map<LocalDate, String>> generations = Map.of();   // kind → day → current generation
    private FailFast failFast;
    private volatile RedisConnection redis;
    private volatile StatefulRedisPubSubConnection<String, String> pubSub;
    private volatile String problem = "not connected yet";
    /** Why the last read failed, until one succeeds (null when it did): health reflects a failing store at once. */
    private volatile String commandFailure;
    private volatile Instant lastUpdate;
    private SourceContext context;
    private String uri;
    private com.ash.drishti.api.tls.TlsMaterial tls;
    private boolean cluster;
    private String user;
    private String password;
    private String domain;
    private String sourceName;
    private int lookbackDays;
    private int maxLoadRows;
    private boolean reverseIndex = true;
    private boolean live = true;
    private Duration timeout;
    private List<String> configuredKinds = List.of();
    private Cache<DayKey, Loaded> columnSets;
    private Cache<Integer, DocCodec.Dictionary> dictionaries;
    private Semaphore heavy;

    @Override
    public PluginManifest manifest() {
        Set<String> kinds = configuredKinds.isEmpty() ? kindDates.keySet() : new HashSet<>(configuredKinds);
        return new PluginManifest(sourceName == null ? "redis" : sourceName, "1.0", kinds, new SourceCapabilities(live, reverseIndex, true, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "redis");
        this.uri = ctx.setting("uri", "");
        if (uri.isEmpty()) {
            // installed but not pointed at a Redis: idle, rather than a live source that serves every kind and fails
            throw new com.ash.drishti.api.PluginNotConfigured("redis needs settings.uri (redis://host:6379)");
        }
        this.tls = RedisTls.material(ctx.settings(), uri, System::getenv);       // fails the start, naming the file and the reason
        this.cluster = Boolean.parseBoolean(ctx.setting("cluster", "false"));
        this.user = ctx.setting("user", null);
        this.password = ctx.setting("password", null);
        this.domain = ctx.setting("domain", "drishti");
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        this.maxLoadRows = Integer.parseInt(ctx.setting("max-load-rows", "200000"));
        this.reverseIndex = Boolean.parseBoolean(ctx.setting("reverse-index", "true"));
        this.live = Boolean.parseBoolean(ctx.setting("live", "true"));
        this.timeout = Duration.ofMillis(Long.parseLong(ctx.setting("timeout-ms", "5000")));
        this.heavy = new Semaphore(Integer.parseInt(ctx.setting("heavy-reads", "2")));
        this.failFast = new FailFast(Boolean.parseBoolean(ctx.setting("fail-fast", "true")),
                Duration.ofMillis(Long.parseLong(ctx.setting("recheck-ms", "1000"))), this::down, this::recheck, sourceName);
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
                .weigher((DayKey k, Loaded v) -> (int) Math.min(Integer.MAX_VALUE, weight(v.columns()))).build();
        this.dictionaries = Caffeine.newBuilder().maximumSize(256).build();
        // start even when Redis is not reachable: health says so, and every refresh tries again
        refresh();
        long refresh = Long.parseLong(ctx.setting("refresh-seconds", "60"));
        ctx.scheduler().scheduleWithFixedDelay(() -> Thread.ofVirtual().name("redis-refresh-" + sourceName).start(this::refresh), refresh, refresh,
                TimeUnit.SECONDS);
    }

    /** The connection, opened on first use and after a failed start (Lettuce reconnects an open one by itself). */
    private RedisClusterAsyncCommands<byte[], byte[]> redis() {
        RedisConnection c = redis;
        if (c != null) {
            return c.async();
        }
        connecting.lock();
        try {
            if (redis == null) {
                redis = RedisConnection.open(uri, cluster, user, password, timeout, tls);
                if (live) {
                    listen(redis);
                }
            }
            return redis.async();
        } finally {
            connecting.unlock();
        }
    }

    /** Subscribes to the domain's changes: a changed entity is pushed to its open views; a written day refreshes the catalogue. */
    private void listen(RedisConnection c) {
        StatefulRedisPubSubConnection<String, String> ps = c.pubSub();
        ps.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                changed(message);
            }
        });
        ps.async().subscribe(RedisLayout.changes(domain));
        pubSub = ps;
    }

    private void changed(String message) {
        lastUpdate = Instant.now();
        String[] m = message.split("\t", 3);
        if (m.length < 3) {
            return;
        }
        if (m[1].equals("*")) {
            if (refreshQueued.compareAndSet(false, true)) {          // a day written: new dates, ids and columns
                Thread.ofVirtual().name("redis-changed-" + sourceName).start(() -> {
                    refreshQueued.set(false);
                    refresh();
                });
            }
            return;
        }
        EntityRef ref = EntityRef.of(m[0], m[1]);
        List<Consumer<EntityDocument>> subs = listeners.get(ref);
        if (subs != null) {
            Thread.ofVirtual().name("redis-push").start(() -> {
                try {
                    fetch(ref, AsOf.LATEST).ifPresent(d -> subs.forEach(l -> l.accept(d)));
                } catch (Exception e) {
                    // the next change pushes again
                }
            });
        }
    }

    /** The kinds and their days, the newest day's ids (when they changed), then the newest day's columns warmed. */
    void refresh() {
        if (!refreshing.tryLock()) {
            return;
        }
        try {
            RedisClusterAsyncCommands<byte[], byte[]> r = redis();
            Map<String, NavigableSet<LocalDate>> dates = new ConcurrentHashMap<>();
            Map<String, Map<LocalDate, String>> gens = new ConcurrentHashMap<>();
            for (byte[] k : ColumnReader.await(r.smembers(RedisLayout.bytes(RedisLayout.kinds(domain))), timeout)) {
                String kind = new String(k, StandardCharsets.UTF_8);
                NavigableSet<LocalDate> ds = new TreeSet<>();
                for (byte[] d : ColumnReader.await(r.zrange(RedisLayout.bytes(RedisLayout.days(domain, kind)), 0, -1), timeout)) {
                    ds.add(RedisLayout.date(Long.parseLong(new String(d, StandardCharsets.UTF_8))));
                }
                dates.put(kind, ds);
                Map<LocalDate, String> g = new ConcurrentHashMap<>();
                ColumnReader.await(r.hgetall(RedisLayout.bytes(RedisLayout.generations(domain, kind))), timeout)
                        .forEach((d, v) -> g.put(RedisLayout.date(Long.parseLong(new String(d, StandardCharsets.UTF_8))), new String(v, StandardCharsets.UTF_8)));
                gens.put(kind, g);
            }
            byte[] updated = ColumnReader.await(r.get(RedisLayout.bytes(RedisLayout.updated(domain))), timeout);
            if (updated != null) {
                Instant at = Instant.ofEpochMilli(Long.parseLong(new String(updated, StandardCharsets.UTF_8)));
                if (lastUpdate == null || at.isAfter(lastUpdate)) {
                    lastUpdate = at;
                }
            }
            generations = gens;
            kindDates = dates;
            problem = null;
            commandFailure = null;                             // the store answered
            reindex(dates);
            warm(dates);
        } catch (Exception e) {
            problem = "cannot reach Redis at " + RedisConnection.describe(uri) + ": " + e.getMessage();
        } finally {
            refreshing.unlock();
        }
    }

    /** Type-ahead from ids only: a snapshot kind's newest day, an effective kind's every day; re-read when a day changes. */
    private void reindex(Map<String, NavigableSet<LocalDate>> dates) throws Exception {
        boolean changed = hitsByKind.keySet().retainAll(dates.keySet());
        for (Map.Entry<String, NavigableSet<LocalDate>> e : dates.entrySet()) {
            String kind = e.getKey();
            if (e.getValue().isEmpty() || !configuredKinds.isEmpty() && !configuredKinds.contains(kind)) {
                continue;
            }
            Collection<LocalDate> days = effective(kind) ? e.getValue() : List.of(e.getValue().last());
            long version = 0;
            Map<LocalDate, ColumnCodec.Meta> metas = new LinkedHashMap<>();
            for (LocalDate d : days) {
                String gen = generation(kind, d);
                Optional<ColumnCodec.Meta> m = ColumnReader.meta(redis(), domain, kind, d, gen, timeout);
                if (m.isPresent()) {
                    metas.put(d, m.get());
                    version = version * 31 + m.get().version() + d.toEpochDay() + gen.hashCode();
                }
            }
            if (Long.valueOf(version).equals(indexed.get(kind))) {
                continue;
            }
            Set<String> ids = new TreeSet<>();
            for (Map.Entry<LocalDate, ColumnCodec.Meta> m : metas.entrySet()) {
                ColumnReader.read(redis(), domain, kind, m.getKey(), generation(kind, m.getKey()), m.getValue(), List.of(), Duration.ofMinutes(2))
                        .ifPresent(c -> ids.addAll(Arrays.asList(c.ids())));
            }
            List<EntityHit> hits = new ArrayList<>(ids.size());
            ids.forEach(id -> hits.add(new EntityHit(EntityRef.of(kind, id), id, kind + " · " + sourceName)));
            hitsByKind.put(kind, hits);
            indexed.put(kind, version);
            changed = true;
        }
        if (changed) {
            List<EntityHit> all = new ArrayList<>();
            hitsByKind.values().forEach(all::addAll);
            index.replaceAll(all);
        }
    }

    /** The newest day's columns of each promoted kind, read in the background so the first search finds them. */
    private void warm(Map<String, NavigableSet<LocalDate>> dates) {
        promoted.forEach((kind, paths) -> {
            NavigableSet<LocalDate> ds = dates.get(kind);
            if (ds != null && !ds.isEmpty() && !effective(kind)) {
                Thread.ofVirtual().name("redis-warm-" + kind).start(() -> {
                    try {
                        day(kind, ds.last());
                    } catch (Exception e) {
                        // the first search reads it instead
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
        failFast.check();
        Optional<Found> found;
        try {
            found = ColumnReader.await(read(ref, asOf), timeout);
            commandFailure = null;
        } catch (Exception e) {
            failed(e);
            throw e;
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        byte[] json = decode(found.get().value());
        var data = context.parseJson(new ByteArrayInputStream(json));
        return Optional.of(new EntityDocument(ref, data,
                new Provenance(sourceName, DocCodec.generation(found.get().value()), Instant.now(), live && asOf.live(), found.get().day())));
    }

    /**
     * The stored value of the entity on the date asked, without waiting: for a snapshot kind one {@code GET} of the
     * kind's day in its generation; for an effective kind (or before the kinds are known) the entity's latest days on or
     * before the date, then their documents in one {@code MGET}, the newest found winning.
     */
    private CompletableFuture<Optional<Found>> read(EntityRef ref, AsOf asOf) {
        RedisClusterAsyncCommands<byte[], byte[]> r = redis();
        LocalDate asked = asOf.businessDate();
        if (!effective(ref.kind()) && kindDates.get(ref.kind()) != null) {
            Optional<LocalDate> day = snapshotDate(ref.kind(), asked);
            if (day.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            LocalDate d = day.get();
            String gen = generation(ref.kind(), d);
            return get(r, ref, d, gen).thenCompose(found -> found.isPresent() ? CompletableFuture.completedFuture(found)
                    // nothing in the generation known here: the day may have been switched since; ask once
                    : currentGeneration(r, ref.kind(), List.of(d)).thenCompose(now -> now.get(0).equals(gen)
                            ? CompletableFuture.completedFuture(Optional.<Found>empty()) : get(r, ref, d, now.get(0))));
        }
        byte[] entity = RedisLayout.bytes(RedisLayout.entity(domain, ref.kind(), ref.id()));
        Range<Long> upTo = asked == null ? Range.unbounded() : Range.from(Range.Boundary.unbounded(), Range.Boundary.including(RedisLayout.day(asked)));
        return r.zrevrangebyscore(entity, upTo, Limit.create(0, CANDIDATE_DAYS)).toCompletableFuture().thenCompose(members -> {
            List<LocalDate> days = members.stream().map(m -> RedisLayout.date(Long.parseLong(new String(m, StandardCharsets.UTF_8)))).toList();
            if (days.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            List<String> known = days.stream().map(d -> generation(ref.kind(), d)).toList();
            return newest(r, ref, days, known).thenCompose(found -> found.isPresent() ? CompletableFuture.completedFuture(found)
                    : currentGeneration(r, ref.kind(), days).thenCompose(now -> now.equals(known)
                            ? CompletableFuture.completedFuture(Optional.<Found>empty()) : newest(r, ref, days, now)));
        });
    }

    private CompletableFuture<Optional<Found>> get(RedisClusterAsyncCommands<byte[], byte[]> r, EntityRef ref, LocalDate day, String gen) {
        return r.get(RedisLayout.bytes(RedisLayout.doc(domain, ref.kind(), ref.id(), day, gen))).toCompletableFuture()
                .thenApply(v -> v == null ? Optional.<Found>empty() : Optional.of(new Found(day, v)));
    }

    /** The newest of the entity's documents on {@code days} (newest first) in their generations (same hash tag: one MGET). */
    private CompletableFuture<Optional<Found>> newest(RedisClusterAsyncCommands<byte[], byte[]> r, EntityRef ref, List<LocalDate> days, List<String> gens) {
        byte[][] keys = new byte[days.size()][];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = RedisLayout.bytes(RedisLayout.doc(domain, ref.kind(), ref.id(), days.get(i), gens.get(i)));
        }
        return r.mget(keys).toCompletableFuture().thenApply(values -> {
            for (int i = 0; i < values.size(); i++) {
                KeyValue<byte[], byte[]> kv = values.get(i);
                if (kv.hasValue()) {
                    return Optional.of(new Found(days.get(i), kv.getValue()));
                }
            }
            return Optional.<Found>empty();
        });
    }

    /** The days' current generations, as Redis has them now (remembered for the next reads). */
    private CompletableFuture<List<String>> currentGeneration(RedisClusterAsyncCommands<byte[], byte[]> r, String kind, List<LocalDate> days) {
        byte[][] fields = days.stream().map(d -> RedisLayout.bytes(String.valueOf(RedisLayout.day(d)))).toArray(byte[][]::new);
        return r.hmget(RedisLayout.bytes(RedisLayout.generations(domain, kind)), fields).toCompletableFuture().thenApply(values -> {
            List<String> out = new ArrayList<>(days.size());
            for (int i = 0; i < days.size(); i++) {
                String gen = values.get(i).hasValue() ? new String(values.get(i).getValue(), StandardCharsets.UTF_8) : "";
                remember(kind, days.get(i), gen);
                out.add(gen);
            }
            return out;
        });
    }

    /** The day's current generation as last read ({@code ""}: none, the keys without a suffix). */
    private String generation(String kind, LocalDate day) {
        Map<LocalDate, String> g = generations.get(kind);
        String gen = g == null ? null : g.get(day);
        return gen == null ? "" : gen;
    }

    private void remember(String kind, LocalDate day, String gen) {
        Map<LocalDate, String> g = generations.get(kind);
        if (g != null) {
            g.put(day, gen);
        }
    }

    /** A read failed: health says DOWN at once (not at the next refresh), and the breaker opens. */
    private void failed(Exception e) {
        commandFailure = "reads fail: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        failFast.startRecheck();
    }

    /** True while health says the store is down: what opens the breaker. */
    private boolean down() {
        RedisConnection c = redis;
        return problem != null || commandFailure != null || c != null && !c.isOpen();
    }

    /** The breaker's check: a {@code PING} on a live connection (or a new connection); when it answers, health is UP again. */
    private void recheck() throws Exception {
        RedisConnection c = redis;
        if (c == null) {
            refresh();                                            // opens the connection, reads the catalogue
            return;
        }
        if (!c.isOpen()) {
            return;                                               // Lettuce is still reconnecting
        }
        ColumnReader.await(c.async().ping(), failFast.recheck());
        commandFailure = null;
        refresh();
    }

    /** A stored value's JSON; a dictionary is read from Redis once and kept. */
    private byte[] decode(byte[] value) {
        return DocCodec.decode(value, id -> dictionaries.get(id, k -> {
            try {
                byte[] d = ColumnReader.await(redis().get(RedisLayout.bytes(RedisLayout.dictionary(domain, k))), timeout);
                return d == null ? null : DocCodec.Dictionary.of(d);
            } catch (Exception e) {
                throw new IllegalStateException("cannot read zstd dictionary " + Integer.toUnsignedString(k, 16), e);
            }
        }));
    }

    @Override
    public Set<String> columnar(String kind) {
        return effective(kind) ? Set.of() : Set.copyOf(promoted.getOrDefault(kind, List.of()));
    }

    @Override
    public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) throws Exception {
        if (!columnar(kind).containsAll(paths)) {
            return Optional.empty();
        }
        failFast.check();
        Optional<LocalDate> day = snapshotDate(kind, asOf.businessDate());
        if (day.isEmpty()) {
            return Optional.empty();                           // a date Redis does not hold: the next store for the kind is asked
        }
        Optional<ColumnSet> all;
        try {
            all = day(kind, day.get());
        } catch (Exception e) {
            failed(e);
            throw e;
        }
        if (all.isEmpty() || !paths.stream().allMatch(all.get()::has)) {
            return Optional.empty();                           // expired, or loaded without some of the fields: documents are read
        }
        Map<String, double[]> nums = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        for (String p : paths) {
            if (all.get().numbers().containsKey(p)) {
                nums.put(p, all.get().numbers().get(p));
            } else {
                texts.put(p, all.get().texts().get(p));
            }
        }
        return Optional.of(new ColumnSet(all.get().ids(), nums, texts, day.get()));
    }

    /**
     * A day's column set: from memory when the hash's version is the one read before (one {@code HGET} of its meta),
     * otherwise read again; at most {@code heavy-reads} days are read at once, and one day only once at a time.
     */
    private Optional<ColumnSet> day(String kind, LocalDate date) throws Exception {
        DayKey key = new DayKey(kind, date);
        String gen = generation(kind, date);
        Optional<ColumnCodec.Meta> meta = ColumnReader.meta(redis(), domain, kind, date, gen, timeout);
        if (meta.isEmpty()) {
            String now = ColumnReader.generation(redis(), domain, kind, date, timeout);   // switched since the catalogue was read?
            if (!now.equals(gen)) {
                remember(kind, date, now);
                gen = now;
                meta = ColumnReader.meta(redis(), domain, kind, date, gen, timeout);
            }
        }
        if (meta.isEmpty()) {
            columnSets.invalidate(key);
            return Optional.empty();
        }
        Loaded have = columnSets.getIfPresent(key);
        if (have != null && have.gen().equals(gen) && have.version() == meta.get().version()) {
            return Optional.of(have.columns());
        }
        ReentrantLock lock = loading.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            have = columnSets.getIfPresent(key);
            if (have != null && have.gen().equals(gen) && have.version() == meta.get().version()) {
                return Optional.of(have.columns());
            }
            heavy.acquire();
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    Optional<ColumnSet> read = ColumnReader.read(redis(), domain, kind, date, gen, meta.get(), promoted.getOrDefault(kind, List.of()),
                            Duration.ofMinutes(2));
                    if (read.isPresent()) {
                        columnSets.put(key, new Loaded(gen, meta.get().version(), read.get()));
                        return read;
                    }
                    gen = ColumnReader.generation(redis(), domain, kind, date, timeout);    // rewritten or switched while read: read the new one
                    remember(kind, date, gen);
                    meta = ColumnReader.meta(redis(), domain, kind, date, gen, timeout);
                    if (meta.isEmpty()) {
                        return Optional.empty();
                    }
                }
                return Optional.empty();
            } finally {
                heavy.release();
            }
        } finally {
            lock.unlock();
            loading.remove(key, lock);
        }
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        if (!reverseIndex || failFast.open()) {
            return List.of();                                  // the store is down: no referrers from here, at once
        }
        List<EntityRef> out = new ArrayList<>();
        for (String k : kind == null ? kindDates.keySet() : Set.of(kind)) {
            NavigableSet<LocalDate> ds = kindDates.get(k);
            if (ds == null || ds.isEmpty()) {
                continue;                                      // a kind this domain does not hold: nothing to read
            }
            Set<String> found = new TreeSet<>();
            try {
                Optional<LocalDate> day = effective(k) ? Optional.empty() : snapshotDate(k, asOf.businessDate());
                if (!effective(k) && day.isEmpty()) {
                    continue;
                }
                Optional<ColumnSet> c = day.isPresent() && !columnar(k).isEmpty() ? columns(k, columnar(k), asOf) : Optional.empty();
                if (c.isPresent()) {
                    // promoted link fields (nettingSet, book, counterparty.id …): no document is read
                    ColumnSet cs = c.get();
                    cs.texts().values().forEach(values -> {
                        for (int i = 0; i < values.length; i++) {
                            if (target.id().equals(values[i])) {
                                found.add(cs.ids()[i]);
                            }
                        }
                    });
                } else {
                    found.addAll(scanReferences(k, target.id(), day.isPresent() ? List.of(day.get()) : ds.headSet(asOf.dateOr(ds.last()), true), asOf));
                }
            } catch (Exception e) {
                continue;                                      // unreachable or slow: no referrers from here, not an error page
            }
            found.forEach(i -> out.add(EntityRef.of(k, i)));
        }
        return out;
    }

    /**
     * Entities whose documents mention the target, for a kind without promoted fields: the ids of the days given (the
     * snapshot day, or every day up to the date for an effective kind), at most {@code max-load-rows} documents read,
     * 256 at a time.
     */
    private Set<String> scanReferences(String kind, String target, Collection<LocalDate> days, AsOf asOf) throws Exception {
        Set<String> ids = new TreeSet<>();
        for (LocalDate d : days) {
            String gen = generation(kind, d);
            Optional<ColumnCodec.Meta> m = ColumnReader.meta(redis(), domain, kind, d, gen, timeout);
            if (m.isPresent()) {
                ColumnReader.read(redis(), domain, kind, d, gen, m.get(), List.of(), Duration.ofMinutes(2)).ifPresent(c -> ids.addAll(Arrays.asList(c.ids())));
            }
            if (ids.size() >= maxLoadRows) {
                break;
            }
        }
        Set<String> out = ConcurrentHashMap.newKeySet();
        Semaphore window = new Semaphore(256);
        List<Future<?>> pending = new ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            int n = 0;
            for (String id : ids) {
                if (n++ >= maxLoadRows) {
                    break;
                }
                window.acquire();
                pending.add(pool.submit(() -> {
                    try {
                        Optional<Found> f = ColumnReader.await(read(EntityRef.of(kind, id), asOf), timeout);
                        if (f.isPresent() && ReferenceScanner.referencedIds(new String(decode(f.get().value()), StandardCharsets.UTF_8)).contains(target)) {
                            out.add(id);
                        }
                        return null;
                    } finally {
                        window.release();
                    }
                }));
            }
            for (Future<?> f : pending) {
                f.get();
            }
        }
        return out;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        if (!live) {
            return Subscription.NONE;
        }
        listeners.compute(ref, (r, subs) -> {
            List<Consumer<EntityDocument>> l = subs == null ? new CopyOnWriteArrayList<>() : subs;
            l.add(listener);
            return l;
        });
        return () -> listeners.computeIfPresent(ref, (r, subs) -> {
            subs.remove(listener);
            return subs.isEmpty() ? null : subs;
        });
    }

    /** When a loader last wrote ({@code <domain>:updated}) or announced a change. */
    @Override
    public Instant lastUpdate() {
        return lastUpdate;
    }

    @Override
    public Map<String, Object> cacheStats() {
        long bytes = columnSets.policy().eviction().map(e -> e.weightedSize().orElse(0L)).orElse(0L);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kinds", kindDates.size());
        out.put("datesIndexed", kindDates.values().stream().mapToInt(Set::size).sum());
        out.put("ids", index.size());
        out.put("columnSets", columnSets.estimatedSize());
        out.put("columnSetsMb", bytes >> 20);
        out.put("dictionaries", dictionaries.estimatedSize());
        out.put("liveViews", listeners.size());
        return out;
    }

    /** Forgets the columns and dictionaries read, then re-reads the kinds' days and ids. */
    @Override
    public void purgeCaches() {
        columnSets.invalidateAll();
        dictionaries.invalidateAll();
        indexed.clear();
        refresh();
    }

    @Override
    public String health() {
        String down = downHealth();
        if (down != null) {
            return failFast.open() ? down + "; reads fail at once until it answers (checked every " + failFast.recheck().toMillis() + " ms)" : down;
        }
        // a pack declared fields the newest day was loaded without: it works, but searches over them read documents
        List<String> notLaidOut = new ArrayList<>();
        promoted.forEach((kind, paths) -> {
            NavigableSet<LocalDate> ds = kindDates.get(kind);
            Loaded have = ds == null || ds.isEmpty() ? null : columnSets.getIfPresent(new DayKey(kind, ds.last()));
            if (have != null) {
                long held = paths.stream().filter(have.columns()::has).count();
                if (held < paths.size()) {
                    notLaidOut.add(kind + " (" + held + " of " + paths.size() + " columns)");
                }
            }
        });
        String up = notLaidOut.isEmpty() ? "UP" : "UP (not laid out as the pack declares: " + String.join(", ", notLaidOut) + "; searches read documents)";
        return tls == null ? up : tls.annotate(up, java.time.Instant.now());
    }

    /** Health when the store is down, else null. */
    private String downHealth() {
        String p = problem;
        if (p != null) {
            return "DOWN: " + p;
        }
        RedisConnection c = redis;
        if (c != null && !c.isOpen()) {
            // Lettuce marks the connection inactive the moment it drops (it reconnects by itself): DOWN now, not at the
            // next refresh
            return "DOWN: lost the connection to Redis at " + c.describe() + " (reconnecting)";
        }
        String failed = commandFailure;
        return failed == null ? null : "DOWN: " + failed;
    }

    @Override
    public void close() {
        if (failFast != null) {
            failFast.close();
        }
        StatefulRedisPubSubConnection<String, String> ps = pubSub;
        if (ps != null) {
            ps.close();
        }
        RedisConnection c = redis;
        if (c != null) {
            c.close();
        }
    }
}
