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
import java.nio.charset.StandardCharsets;
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
 * (compacted-topic semantics). At start the plugin reads every partition from the beginning to rebuild that state,
 * then keeps consuming: each new message replaces the entity and is pushed to every open view of it, so views tick
 * from the stream. No consumer group commits are made, so every server rebuilds the same state independently.
 *
 * <p>Two message shapes. <b>Envelope</b> (default): {@code {"kind": "trade", "id": "T-1", "doc": {...}}}.
 * <b>Mapped</b>: {@code kind.<topic>: trade} and {@code id-field.<topic>: tradeId} (or {@code kind} and {@code id-field} for
 * every topic) make the whole value the document.
 * A message whose value is null (a tombstone) deletes the entity.
 *
 * <p>Settings: {@code bootstrap-servers}, {@code topics} (comma list), {@code kind.<topic>}, {@code id-field.<topic>},
 * {@code poll-ms} (200), {@code source-name} ({@code kafka}), {@code client.<property>} (any Kafka consumer property,
 * e.g. {@code client.security.protocol}).
 */
public final class KafkaSourcePlugin implements SourcePlugin {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<EntityRef, EntityDocument> documents = new ConcurrentHashMap<>();
    private final Map<EntityRef, List<Consumer<EntityDocument>>> listeners = new ConcurrentHashMap<>();
    private final Set<String> kinds = ConcurrentHashMap.newKeySet();
    private final HitIndex index = new HitIndex();
    private final AtomicReference<String> health = new AtomicReference<>("DOWN: not started");
    private final Map<String, String> kindOfTopic = new HashMap<>();
    private final Map<String, String> idFieldOfTopic = new HashMap<>();
    private KafkaConsumer<String, String> consumer;
    private Thread loop;
    private SourceContext context;
    private String sourceName;
    private volatile boolean caughtUp;
    private volatile boolean running = true;
    private String defaultKind;
    private String defaultIdField = "id";

    @Override
    public PluginManifest manifest() {
        Set<String> k = new HashSet<>(kinds);
        k.addAll(kindOfTopic.values());
        if (defaultKind != null) {
            k.add(defaultKind);
        }
        return new PluginManifest(sourceName == null ? "kafka" : sourceName, "1.0", k, new SourceCapabilities(true, true, true, false));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "kafka");
        List<String> topics = List.of(ctx.setting("topics", "").split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (topics.isEmpty()) {
            throw new IllegalStateException("kafka plugin needs settings.topics");
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
        consumer = new KafkaConsumer<>(p);
        long pollMs = Long.parseLong(ctx.setting("poll-ms", "200"));
        loop = Thread.ofVirtual().name("drishti-kafka-" + sourceName).start(() -> run(topics, pollMs));
    }

    private void run(List<String> topics, long pollMs) {
        try {
            List<TopicPartition> parts = new ArrayList<>();
            for (String t : topics) {
                consumer.partitionsFor(t, Duration.ofSeconds(30)).forEach(i -> parts.add(new TopicPartition(t, i.partition())));
            }
            consumer.assign(parts);
            consumer.seekToBeginning(parts);
            Map<TopicPartition, Long> end = consumer.endOffsets(parts);
            health.set("UP (catching up)");
            while (running) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(pollMs))) {
                    apply(r);
                }
                if (!caughtUp && end.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue())) {
                    caughtUp = true;
                    health.set("UP");
                    rebuildIndex();
                }
            }
        } catch (WakeupException e) {
            // closing
        } catch (RuntimeException e) {
            health.set("DOWN: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            consumer.close(Duration.ofSeconds(2));
        }
    }

    /** One message: parse, replace (or delete) the entity, and push it to open views. */
    void apply(ConsumerRecord<String, String> r) {
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
                documents.remove(ref);
                return;
            }
            kinds.add(kind);
            DataNode data = context.parseJson(new ByteArrayInputStream(JSON.writeValueAsBytes(doc)));
            EntityDocument d = new EntityDocument(ref, data, new Provenance(sourceName, r.offset(), Instant.ofEpochMilli(r.timestamp()), true));
            boolean isNew = documents.put(ref, d) == null;
            if (isNew && caughtUp) {
                index.add(new EntityHit(ref, id, kind + " · " + sourceName));
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
        List<EntityHit> hits = new ArrayList<>();
        documents.keySet().forEach(ref -> hits.add(new EntityHit(ref, ref.id(), ref.kind() + " · " + sourceName)));
        index.replaceAll(hits);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        return Optional.ofNullable(documents.get(ref));
    }

    @Override
    public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        List<Consumer<EntityDocument>> subs = listeners.computeIfAbsent(ref, r -> new CopyOnWriteArrayList<>());
        subs.add(listener);
        return () -> subs.remove(listener);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        List<EntityRef> out = new ArrayList<>();
        documents.forEach((ref, d) -> {
            if ((kind == null || ref.kind().equals(kind)) && refersTo(d.data(), target.id())) {
                out.add(ref);
            }
        });
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    private static boolean refersTo(DataNode node, String id) {
        if (node instanceof DataNode.Obj o) {
            for (DataNode v : o.fields().values()) {
                if (v instanceof DataNode.Val val && id.equals(val.asText()) || v instanceof DataNode.Obj && refersTo(v, id)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public String health() {
        return health.get();
    }

    @Override
    public void close() {
        running = false;
        if (consumer != null) {
            consumer.wakeup();
        }
        if (loop != null) {
            try {
                loop.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
