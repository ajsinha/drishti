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
package com.ash.drishti.plugin.kafka;

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
import com.ash.drishti.api.Subscription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * A live source over Kafka: each topic carries entity documents, and the latest message per entity is the entity
 * (compacted-topic semantics). At start the plugin reads every partition from the beginning, then keeps consuming;
 * each new message is pushed to every open view of its entity, so views tick from the stream. No consumer group
 * commits are made, so every server builds the same state independently.
 *
 * <p><b>Memory stays bounded.</b> In {@code state} mode (default) the plugin keeps an index of where each entity's
 * latest message is (partition and offset, tens of bytes per entity) and a cache of recently read documents limited
 * by size ({@code cache-mb}, 256): a miss reads that one record back from Kafka by its offset. Messages for entities
 * nobody is viewing are not even parsed. In {@code ticks} mode it keeps nothing: a store (Delta Lake, a database)
 * serves the entities and the stream only drives the ticks of open views. Reverse lookups are left to the stores.
 *
 * <p><b>Disk cache</b> ({@code disk-cache.enabled: true}): every message is also written to this connector's own
 * RocksDB store on local disk, so the day's live data is served from disk instead of re-read from Kafka. Each
 * connector has its own store ({@code disk-cache.dir}, default {@code ./data/cache/<connector>}; root
 * {@code DRISHTI_CACHE_ROOT}), its own budget ({@code disk-cache.max-gb}, 10) and its own nightly clearing
 * ({@code disk-cache.reset-at}, {@code 02:00}, in {@code disk-cache.zone}, {@code America/New_York}).
 *
 * <p>Two message shapes. <b>Envelope</b> (default): {@code {"kind": "trade", "id": "T-1", "doc": {...}}}.
 * <b>Mapped</b>: {@code kind.<topic>: trade} and {@code id-field.<topic>: tradeId} (or {@code kind} and {@code id-field} for
 * every topic) make the whole value the document.
 * A message whose value is null (a tombstone) deletes the entity.
 *
 * <p>Settings: {@code bootstrap-servers}, {@code topics} (comma list), {@code kind.<topic>}, {@code id-field.<topic>},
 * {@code mode} ({@code state} or {@code ticks}), {@code cache-mb} (256), {@code search} (true: keep identifiers for
 * type-ahead), {@code poll-ms} (200), {@code source-name} ({@code kafka}), {@code client.<property>} (any Kafka consumer property,
 * e.g. {@code client.security.protocol}).
 */
public final class KafkaSourcePlugin implements SourcePlugin {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Where an entity's latest message is. */
    private record Pos(int partition, long offset, String topic) {}

    private final Map<EntityRef, Pos> positions = new ConcurrentHashMap<>();
    private com.github.benmanes.caffeine.cache.Cache<EntityRef, EntityDocument> cache;
    private KafkaConsumer<String, String> reader;
    /** Guards the cold-miss reader (a KafkaConsumer is single-threaded). A ReentrantLock: the guarded poll is network I/O. */
    private final java.util.concurrent.locks.ReentrantLock readerLock = new java.util.concurrent.locks.ReentrantLock();
    private boolean ticksOnly;
    private com.ash.drishti.diskcache.DiskCache disk;
    private boolean searchable;
    private final Map<EntityRef, List<Consumer<EntityDocument>>> listeners = new ConcurrentHashMap<>();
    private final Set<String> kinds = ConcurrentHashMap.newKeySet();
    private final HitIndex index = new HitIndex();
    private final AtomicReference<String> health = new AtomicReference<>("DOWN: not started");
    /** How long the consumer may have no broker connection before health says so (short blips are not reported). */
    static final long BROKER_LOST_MS = 10_000;
    private final Map<String, String> kindOfTopic = new HashMap<>();
    private final Map<String, String> idFieldOfTopic = new HashMap<>();
    private volatile KafkaConsumer<String, String> consumer;   // replaced when the supervisor reconnects
    private Properties consumerProps;
    /** The next offset to read per partition: a reconnect resumes here instead of replaying the topic. */
    private final Map<TopicPartition, Long> resumeAt = new ConcurrentHashMap<>();
    private Thread loop;
    private SourceContext context;
    private String sourceName;
    private volatile boolean caughtUp;
    private volatile boolean running = true;
    private volatile boolean closed;
    private String defaultKind;
    private String defaultIdField = "id";

    @Override
    public PluginManifest manifest() {
        Set<String> k = new HashSet<>(kinds);
        k.addAll(kindOfTopic.values());
        if (defaultKind != null) {
            k.add(defaultKind);
        }
        return new PluginManifest(sourceName == null ? "kafka" : sourceName, "1.0", k, new SourceCapabilities(true, false, searchable, false));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "kafka");
        List<String> topics = List.of(ctx.setting("topics", "").split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (topics.isEmpty()) {
            throw new com.ash.drishti.api.PluginNotConfigured("kafka needs settings.topics");
        }
        this.defaultKind = ctx.setting("kind", null);
        this.defaultIdField = ctx.setting("id-field", "id");
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("kind.")) {
                kindOfTopic.put(k.substring(5), v);
            } else if (k.startsWith("id-field.")) {
                idFieldOfTopic.put(k.substring(9), v);
            }
        });
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ctx.setting("bootstrap-servers", "localhost:9092"));
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, "drishti-" + sourceName);
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("client.")) {
                p.put(k.substring(7), v);
            }
        });
        consumerProps = p;                           // the consumer is created (and re-created) by the supervisor
        this.ticksOnly = "ticks".equals(ctx.setting("mode", "state"));
        this.searchable = !ticksOnly && Boolean.parseBoolean(ctx.setting("search", "true"));
        long cacheBytes = Long.parseLong(ctx.setting("cache-mb", "256")) * 1024 * 1024;
        this.cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumWeight(cacheBytes)
                .weigher((EntityRef ref, EntityDocument d) -> weights.getOrDefault(ref, 4096)).build();
        if (!ticksOnly && Boolean.parseBoolean(ctx.setting("disk-cache.enabled", "false"))) {
            try {
                String dir = ctx.setting("disk-cache.dir", ctx.setting("disk-cache.root", "./data/cache") + "/" + sourceName);
                String at = ctx.setting("disk-cache.reset-at", "02:00");
                disk = new com.ash.drishti.diskcache.DiskCache(java.nio.file.Path.of(dir),
                        (long) (Double.parseDouble(ctx.setting("disk-cache.max-gb", "10")) * 1024 * 1024 * 1024),
                        at.isBlank() || "never".equals(at) ? null : java.time.LocalTime.parse(at),
                        java.time.ZoneId.of(ctx.setting("disk-cache.zone", "America/New_York")), ctx.scheduler(), java.time.Clock.systemUTC());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        if (!ticksOnly) {
            Properties rp = new Properties();
            rp.putAll(p);
            rp.put(ConsumerConfig.CLIENT_ID_CONFIG, "drishti-" + sourceName + "-reader");
            rp.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
            reader = new KafkaConsumer<>(rp);
        }
        long pollMs = Long.parseLong(ctx.setting("poll-ms", "200"));
        loop = Thread.ofVirtual().name("drishti-kafka-" + sourceName).start(() -> supervise(topics, pollMs));
    }

    /**
     * Keeps a consumer running for as long as the plugin is open. The Kafka client rides out short broker outages by
     * itself; when it gives up (the broker was down at start, the topic is not there yet, a fatal error), the
     * supervisor waits (1 s doubling to 30 s), creates a new consumer and resumes where the last one stopped.
     */
    private void supervise(List<String> topics, long pollMs) {
        long backoff = 1_000;
        while (running) {
            KafkaConsumer<String, String> c;
            try {
                c = new KafkaConsumer<>(consumerProps);
            } catch (RuntimeException e) {
                health.set("DOWN: " + e.getMessage() + " (retrying)");
                if (!pause(backoff)) {
                    return;
                }
                backoff = Math.min(30_000, backoff * 2);
                continue;
            }
            consumer = c;
            long started = System.nanoTime();
            try {
                if (running) {
                    run(c, topics, pollMs);
                }
            } catch (WakeupException e) {
                // closing
            } catch (RuntimeException e) {
                health.set("DOWN: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " (reconnecting)");
            } finally {
                c.close(Duration.ofSeconds(2));
            }
            if (System.nanoTime() - started > java.util.concurrent.TimeUnit.MINUTES.toNanos(1)) {
                backoff = 1_000;                     // it had been running fine: retry promptly
            }
            if (!running || !pause(backoff)) {
                return;
            }
            backoff = Math.min(30_000, backoff * 2);
        }
    }

    private boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void run(KafkaConsumer<String, String> c, List<String> topics, long pollMs) {
        List<TopicPartition> parts = new ArrayList<>();
        for (String t : topics) {
            List<org.apache.kafka.common.PartitionInfo> infos = c.partitionsFor(t, Duration.ofSeconds(30));
            if (infos == null || infos.isEmpty()) {
                throw new IllegalStateException("topic " + t + " has no partitions yet");
            }
            infos.forEach(i -> parts.add(new TopicPartition(t, i.partition())));
        }
        c.assign(parts);
        for (TopicPartition tp : parts) {
            Long at = resumeAt.get(tp);
            if (at == null) {
                c.seekToBeginning(List.of(tp));
            } else {
                c.seek(tp, at);                      // after a reconnect: carry on, do not replay
            }
        }
        Map<TopicPartition, Long> end = c.endOffsets(parts);
        health.set(caughtUp ? "UP" : "UP (catching up)");
        org.apache.kafka.common.Metric connections = c.metrics().entrySet().stream()
                .filter(e -> "connection-count".equals(e.getKey().name()) && "consumer-metrics".equals(e.getKey().group()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        long lostSince = 0;
        while (running) {
            for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(pollMs))) {
                apply(r);
                resumeAt.put(new TopicPartition(r.topic(), r.partition()), r.offset() + 1);
            }
            // the client rides out a broker outage inside poll() without failing, so health watches its connections:
            // none for BROKER_LOST_MS means the broker is gone (the client keeps reconnecting by itself)
            boolean connected = connections == null || ((Number) connections.metricValue()).doubleValue() > 0;
            if (!connected && lostSince == 0) {
                lostSince = System.nanoTime();
            } else if (!connected && System.nanoTime() - lostSince > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(BROKER_LOST_MS)) {
                health.set("DOWN: no connection to the broker (reconnecting)");
            } else if (connected && lostSince != 0) {
                lostSince = 0;
                health.set(caughtUp ? "UP" : "UP (catching up)");
            }
            if (!caughtUp && end.entrySet().stream().allMatch(e -> c.position(e.getKey()) >= e.getValue())) {
                caughtUp = true;
                health.set("UP");
                rebuildIndex();
            }
        }
    }

    /** Approximate size of each cached document (its message length), for the cache's weigher. */
    private final Map<EntityRef, Integer> weights = new ConcurrentHashMap<>();

    /**
     * One message. Mapped messages are indexed from their key without parsing unless someone is viewing the entity
     * or it is cached; envelopes need their kind and id, so they are parsed.
     */
    /** The newest message's time: when the stream last brought new data. */
    private volatile java.time.Instant lastUpdate;

    @Override
    public java.time.Instant lastUpdate() {
        return lastUpdate;
    }

    void apply(ConsumerRecord<String, String> r) {
        java.time.Instant at = java.time.Instant.ofEpochMilli(r.timestamp());
        java.time.Instant was = lastUpdate;
        if (was == null || at.isAfter(was)) {
            lastUpdate = at;                                  // one consumer thread applies records: no lost update
        }
        String mapped = kindOfTopic.getOrDefault(r.topic(), defaultKind);
        if (mapped != null && r.key() != null) {
            EntityRef ref = EntityRef.of(mapped, r.key());
            track(ref, r);
            // after track: a fetch that cached an older document either sees the new position and drops it, or
            // finished before track, in which case this check sees its entry and replaces it
            boolean wanted = listeners.containsKey(ref) || cache.getIfPresent(ref) != null;
            if (!wanted || r.value() == null) {
                if (r.value() == null) {
                    cache.invalidate(ref);
                }
                return;
            }
        }
        parseAndPush(r);
    }

    private void track(EntityRef ref, ConsumerRecord<String, String> r) {
        if (ticksOnly) {
            return;
        }
        if (r.value() == null) {
            positions.remove(ref);
            weights.remove(ref);
            if (disk != null) {
                disk.delete(diskKey(ref));
            }
            return;
        }
        if (disk != null && kindOfTopic.getOrDefault(r.topic(), defaultKind) != null) {
            disk.put(diskKey(ref), r.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        boolean isNew = positions.put(ref, new Pos(r.partition(), r.offset(), r.topic())) == null;
        kinds.add(ref.kind());
        if (isNew && caughtUp && searchable) {
            index.add(new EntityHit(ref, ref.id(), ref.kind() + " · " + sourceName));
        }
    }

    private void parseAndPush(ConsumerRecord<String, String> r) {
        try {
            String kind;
            String id;
            JsonNode doc;
            String mapped = kindOfTopic.getOrDefault(r.topic(), defaultKind);
            if (mapped != null) {
                kind = mapped;
                if (r.value() == null) {
                    id = r.key();
                    doc = null;
                } else {
                    doc = JSON.readTree(r.value());
                    String field = idFieldOfTopic.getOrDefault(r.topic(), defaultIdField);
                    id = doc.path(field).asText(r.key());
                }
            } else {
                if (r.value() == null) {
                    String[] k = r.key() == null ? new String[0] : r.key().split("/", 2);
                    if (k.length < 2) {
                        return;
                    }
                    kind = k[0];
                    id = k[1];
                    doc = null;
                } else {
                    JsonNode env = JSON.readTree(r.value());
                    kind = env.path("kind").asText(null);
                    id = env.path("id").asText(null);
                    doc = env.get("doc");
                }
            }
            if (kind == null || id == null) {
                return;
            }
            EntityRef ref = EntityRef.of(kind, id);
            if (doc == null || doc.isNull()) {
                positions.remove(ref);
                cache.invalidate(ref);
                return;
            }
            if (mapped == null || !ref.id().equals(r.key())) {   // apply() tracked only a mapped message, by its key
                track(ref, r);
            }
            if (disk != null && mapped == null) {
                disk.put(diskKey(ref), JSON.writeValueAsBytes(doc));
            }
            DataNode data = context.parseJson(new ByteArrayInputStream(JSON.writeValueAsBytes(doc)));
            EntityDocument d = new EntityDocument(ref, data, new Provenance(sourceName, r.offset(), Instant.ofEpochMilli(r.timestamp()), true));
            if (!ticksOnly && (cache.getIfPresent(ref) != null || listeners.containsKey(ref))) {
                weights.put(ref, r.value() == null ? 64 : r.value().length());
                cache.put(ref, d);
            }
            List<Consumer<EntityDocument>> subs = listeners.get(ref);
            if (subs != null) {
                subs.forEach(l -> l.accept(d));
            }
        } catch (Exception e) {
            // a malformed message is skipped; the stream goes on
        }
    }

    private void rebuildIndex() {
        if (!searchable) {
            return;
        }
        List<EntityHit> hits = new ArrayList<>();
        positions.keySet().forEach(ref -> hits.add(new EntityHit(ref, ref.id(), ref.kind() + " · " + sourceName)));
        index.replaceAll(hits);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        if (ticksOnly) {
            return Optional.empty();
        }
        EntityDocument hit = cache.getIfPresent(ref);
        if (hit != null) {
            return Optional.of(hit);
        }
        Pos pos = positions.get(ref);
        if (pos == null) {
            return Optional.empty();
        }
        if (disk != null) {
            byte[] bytes = disk.get(diskKey(ref));
            if (bytes != null) {
                try {
                    DataNode data = context.parseJson(new ByteArrayInputStream(bytes));
                    EntityDocument d = new EntityDocument(ref, data, new Provenance(sourceName, pos.offset(), Instant.now(), true));
                    weights.put(ref, bytes.length);
                    cacheIfCurrent(ref, pos, d);
                    return Optional.of(d);
                } catch (java.io.IOException e) {
                    disk.delete(diskKey(ref));
                }
            }
        }
        ConsumerRecord<String, String> r = readAt(pos);
        if (r == null) {
            return Optional.empty();
        }
        {
            // a cache miss: parse the record once for this caller and keep it (within the cache's size limit)
            try {
                String mapped = kindOfTopic.getOrDefault(r.topic(), defaultKind);
                JsonNode doc = JSON.readTree(r.value());
                JsonNode body = mapped != null ? doc : doc.get("doc");
                DataNode data = context.parseJson(new ByteArrayInputStream(JSON.writeValueAsBytes(body)));
                EntityDocument d = new EntityDocument(ref, data, new Provenance(sourceName, r.offset(), Instant.ofEpochMilli(r.timestamp()), true));
                weights.put(ref, r.value().length());
                cacheIfCurrent(ref, pos, d);
                return Optional.of(d);
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }

    /**
     * Caches a document read at {@code pos} unless the entity moved on meanwhile (a newer record or a tombstone was
     * applied while this fetch was reading): then the entry is dropped, so a stale or deleted document never sticks.
     */
    private void cacheIfCurrent(EntityRef ref, Pos pos, EntityDocument d) {
        cache.put(ref, d);
        if (!pos.equals(positions.get(ref))) {
            cache.invalidate(ref);
        }
    }

    /**
     * Reads the one record at {@code pos} (a cache miss); a single reader, so reads are serialised. Waits at most
     * the read budget for the reader, and gives up (a miss) once the plugin is closing.
     */
    private ConsumerRecord<String, String> readAt(Pos pos) {
        try {
            if (closed || !readerLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            if (closed) {
                return null;
            }
            TopicPartition tp = new TopicPartition(pos.topic(), pos.partition());
            reader.assign(List.of(tp));
            reader.seek(tp, pos.offset());
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < until) {
                for (ConsumerRecord<String, String> r : reader.poll(Duration.ofMillis(200))) {
                    if (r.offset() == pos.offset()) {
                        return r;
                    }
                    if (r.offset() > pos.offset()) {
                        return null;
                    }
                }
            }
            return null;
        } catch (org.apache.kafka.common.KafkaException | IllegalStateException e) {
            return null;                      // broker trouble or closing: a miss, not an error for the viewer
        } finally {
            readerLock.unlock();
        }
    }

    /** In ticks mode this connector keeps nothing but pushes every message for its kinds to the views another store serves. */
    @Override
    public boolean pushes(EntityRef ref) {
        return ticksOnly && manifest().kinds().contains(ref.kind());
    }

    @Override
    public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        listeners.compute(ref, (r, subs) -> {
            List<Consumer<EntityDocument>> l = subs == null ? new CopyOnWriteArrayList<>() : subs;
            l.add(listener);
            return l;
        });
        // the entry goes when its last listener does, so an entity nobody watches is no longer parsed on every update
        return () -> listeners.computeIfPresent(ref, (r, subs) -> {
            subs.remove(listener);
            return subs.isEmpty() ? null : subs;
        });
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public Map<String, Object> cacheStats() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("indexedEntities", positions.size());
        out.put("memoryEntries", cache.estimatedSize());
        out.put("memoryMb", Math.round(cache.asMap().keySet().stream().mapToLong(r -> weights.getOrDefault(r, 0)).sum() / 1048576.0 * 10) / 10.0);
        if (disk != null) {
            out.put("diskMb", Math.round(disk.sizeOnDisk() / 1048576.0 * 10) / 10.0);
            out.put("diskHits", disk.hits());
            out.put("diskMisses", disk.misses());
            out.put("diskClears", disk.resets());
        }
        return out;
    }

    @Override
    public void purgeCaches() {
        cache.invalidateAll();
        if (disk != null) {
            disk.clear();
        }
    }

    private static String diskKey(EntityRef ref) {
        return ref.kind() + "/" + ref.id();
    }

    /** The disk cache, if enabled (for tests and metrics). */
    com.ash.drishti.diskcache.DiskCache diskCache() {
        return disk;
    }

    @Override
    public String health() {
        return health.get();
    }

    @Override
    public void close() {
        closed = true;
        running = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            c.wakeup();
        }
        if (loop != null) {
            loop.interrupt();                        // ends a supervisor waiting to reconnect
        }
        if (loop != null) {
            try {
                loop.join(10_000);           // the loop ends at the wakeup; its consumer.close is bounded to 2 s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reader != null) {
            readerLock.lock();                // after any read in flight; later reads see closed and miss
            try {
                reader.close(Duration.ofSeconds(1));
            } finally {
                readerLock.unlock();
            }
        }
        if (disk != null) {
            disk.close();                     // safe even if the loop still writes: the disk cache drops late calls
        }
    }
}
