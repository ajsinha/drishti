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

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.write.WriteCommand;
import com.ash.drishti.api.write.WriteResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * The Kafka plugin's {@code writes:} commands (docs/guides/APPROVALS_GUIDE.md section 10): a change approved in Drishti is written back as
 * one Kafka message, sent by the lane owner's outbox, one at a time per record.
 * <pre>
 * writes:
 *   amend-trade:
 *     kind: trade
 *     kafka:
 *       topic: trades.amend.v1          # where the change goes
 *       shape: command                  # command: {changeId, kind, entity, field, value, from, basedOn, by, reason, at};
 *                                       # document: the whole amended record, as the topic carries records
 *       version-field: VERSION          # the guard: sent only while the record is at the version the person saw (:basedOn)
 *       changed-by: UPDATED_BY          # said when a stale change is refused: who made the version now there
 *       changed-at: UPDATED_AT
 *       change-id-field: DRISHTI_CHANGE_ID   # the idempotency clause: a record already carrying the change id is a resend
 *     confirm: reply                    # the broker's acknowledgement (acks=all, an idempotent producer)
 * </pre>
 * The message key is the change id for a command (so a resend after a failover carries the same key and the consumer de-duplicates
 * on it) and the record's key for a document. Every message carries the header {@code drishti-change-id}.
 */
final class KafkaWrites implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KEYS = Set.of("topic", "shape", "version-field", "changed-by", "changed-at", "change-id-field", "envelope");

    /** The record this node last wrote per entity (a document shape's next item builds on it before the stream brings it back). */
    private final Map<EntityRef, ObjectNode> written = new ConcurrentHashMap<>();
    private final Map<String, Boolean> acknowledged = new ConcurrentHashMap<>();
    private final KafkaProducer<String, String> producer;
    private final String sourceName;

    KafkaWrites(Map<String, String> settings, String sourceName, KafkaSecurity.Result security) {
        this.sourceName = sourceName;
        Properties p = new Properties();
        String bootstrap = settings.get("bootstrap-servers");
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap == null || bootstrap.isBlank() ? "localhost:9092" : bootstrap);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.CLIENT_ID_CONFIG, "drishti-" + sourceName + "-writes");
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "20000");
        security.properties().forEach(p::put);
        settings.forEach((k, v) -> {
            if (k.startsWith("client.") && !k.startsWith("client.group") && !k.contains("deserializer")) {
                p.put(k.substring(7), v);
            }
        });
        this.producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
    }

    /** What is wrong with a command's {@code kafka:} block, in words. */
    static List<String> check(String command, Map<String, Object> block, Set<String> known) {
        List<String> out = new ArrayList<>();
        for (String k : block.keySet()) {
            if (!KEYS.contains(k)) {
                out.add(command + ": '" + k + "' is not a key of kafka: (" + String.join(", ", new java.util.TreeSet<>(KEYS)) + ")");
            }
        }
        if (!(block.get("topic") instanceof String t) || t.isBlank()) {
            out.add(command + ": kafka.topic names the topic the change is written to");
        }
        Object shape = block.getOrDefault("shape", "command");
        if (!"command".equals(shape) && !"document".equals(shape)) {
            out.add(command + ": kafka.shape is command or document, not '" + shape + "'");
        }
        if (!(block.get("version-field") instanceof String v) || v.isBlank()) {
            out.add(command + ": kafka.version-field is required: the guard that refuses a change to a record that moved since it was seen");
        }
        if (!(block.get("change-id-field") instanceof String c) || c.isBlank()) {
            out.add(command + ": kafka.change-id-field is required: the idempotency clause that recognises a resend");
        }
        return out;
    }

    /**
     * Sends one command. The record is read as the stream holds it now (or as this node last wrote it, if newer): a version other than
     * {@code basedOn} is a conflict naming who changed it; a record already carrying the change id is a resend.
     */
    WriteResult write(WriteCommand c, Function<EntityRef, Optional<EntityDocument>> current) throws Exception {
        Map<String, Object> b = c.template();
        Map<String, Object> pm = c.params();
        String kind = String.valueOf(pm.get("kind"));
        String entity = String.valueOf(pm.get("entity"));
        EntityRef ref = EntityRef.of(kind, entity);
        String versionField = String.valueOf(b.get("version-field"));
        String changeIdField = String.valueOf(b.get("change-id-field"));
        String changeId = c.changeId();
        if (acknowledged.containsKey(changeId)) {
            return WriteResult.already();
        }
        ObjectNode now = newest(ref, current, versionField);
        if (now != null && changeId.equals(now.path(changeIdField).asText(null))) {
            return WriteResult.already();
        }
        Object basedOn = pm.get("basedOn");
        if (basedOn == null) {
            return WriteResult.refused("the change names no version of the record (" + versionField + "): open it again and edit from what is there");
        }
        long seen = Long.parseLong(String.valueOf(basedOn));
        String base = changeId.contains("#") ? changeId.substring(0, changeId.indexOf('#')) : changeId;
        if (now != null) {
            long at = now.path(versionField).asLong(-1);
            String lastChange = now.path(changeIdField).asText("");
            boolean sameChange = !lastChange.isEmpty() && lastChange.startsWith(base) && !base.equals(changeId);
            if (at != seen && !sameChange) {
                String by = b.get("changed-by") == null ? "" : now.path(String.valueOf(b.get("changed-by"))).asText("");
                String when = b.get("changed-at") == null ? "" : now.path(String.valueOf(b.get("changed-at"))).asText("");
                return WriteResult.conflicted(kind + " " + entity + " is at version " + at + (by.isBlank() ? "" : ", changed by " + by)
                        + (when.isBlank() ? "" : " at " + when) + "; the change was made on version " + seen);
            }
        } else if (seen > 0) {
            return WriteResult.conflicted(kind + " " + entity + " is not in " + sourceName + " any more");
        }
        String topic = String.valueOf(b.get("topic"));
        String shape = String.valueOf(b.getOrDefault("shape", "command"));
        String key;
        String value;
        if ("document".equals(shape)) {
            ObjectNode doc = now == null ? JSON.createObjectNode() : now.deepCopy();
            String field = String.valueOf(pm.get("field"));
            doc.set(field, JSON.valueToTree(pm.get("value")));
            doc.put(versionField, (now == null ? 0 : now.path(versionField).asLong(0)) + 1);
            doc.put(changeIdField, changeId);
            if (b.get("changed-by") != null) {
                doc.put(String.valueOf(b.get("changed-by")), String.valueOf(pm.get("by")));
            }
            if (b.get("changed-at") != null) {
                doc.put(String.valueOf(b.get("changed-at")), String.valueOf(pm.get("at")));
            }
            boolean envelope = Boolean.parseBoolean(String.valueOf(b.getOrDefault("envelope", "false")));
            key = envelope ? kind + "/" + entity : entity;
            if (envelope) {
                ObjectNode env = JSON.createObjectNode();
                env.put("kind", kind);
                env.put("id", entity);
                env.set("doc", doc);
                value = JSON.writeValueAsString(env);
            } else {
                value = JSON.writeValueAsString(doc);
            }
            written.put(ref, doc);
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("changeId", changeId);
            for (String k : List.of("kind", "entity", "field", "value", "from", "basedOn", "by", "reason", "at", "businessDate")) {
                m.put(k, pm.get(k));
            }
            pm.forEach(m::putIfAbsent);
            key = changeId;
            value = JSON.writeValueAsString(m);
        }
        ProducerRecord<String, String> rec = new ProducerRecord<>(topic, key, value);
        rec.headers().add("drishti-change-id", changeId.getBytes(StandardCharsets.UTF_8));
        producer.send(rec).get(30, TimeUnit.SECONDS);
        acknowledged.put(changeId, Boolean.TRUE);
        if (acknowledged.size() > 100_000) {
            acknowledged.clear();                         // the source's own check (the change-id field, the key) still holds
        }
        return WriteResult.applied();
    }

    /** The record as the stream holds it, or as this node last wrote it when that is newer. */
    private ObjectNode newest(EntityRef ref, Function<EntityRef, Optional<EntityDocument>> current, String versionField) throws Exception {
        ObjectNode fromStream = null;
        Optional<EntityDocument> d = current.apply(ref);
        if (d.isPresent() && !d.get().deleted()) {
            JsonNode n = JSON.valueToTree(d.get().data().unwrap());
            fromStream = n instanceof ObjectNode o ? o : null;
        }
        ObjectNode mine = written.get(ref);
        if (mine == null) {
            return fromStream;
        }
        if (fromStream == null || mine.path(versionField).asLong(-1) > fromStream.path(versionField).asLong(-1)) {
            return mine;
        }
        written.remove(ref, mine);                         // the stream caught up
        return fromStream;
    }

    /**
     * Reads {@code topics} from the beginning to their end offsets with a consumer of its own and keeps the latest value per key that
     * {@code keyOf} accepts (null: not of the kind); a tombstone removes the key.
     */
    static Map<String, String> snapshot(Properties consumerProps, List<String> topics, Deserializer<String> values, String sourceName,
            Function<ConsumerRecord<String, String>, String> keyOf) {
        Properties p = new Properties();
        p.putAll(consumerProps);
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, "drishti-" + sourceName + "-snapshot");
        p.remove(ConsumerConfig.GROUP_ID_CONFIG);
        Map<String, String> out = new HashMap<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p, new StringDeserializer(), values)) {
            List<TopicPartition> parts = new ArrayList<>();
            for (String t : topics) {
                List<PartitionInfo> info = c.partitionsFor(t, Duration.ofSeconds(10));
                if (info != null) {
                    info.forEach(i -> parts.add(new TopicPartition(t, i.partition())));
                }
            }
            if (parts.isEmpty()) {
                return out;
            }
            c.assign(parts);
            c.seekToBeginning(parts);
            Map<TopicPartition, Long> end = c.endOffsets(parts, Duration.ofSeconds(10));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline && !done(c, end)) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(200))) {
                    String id = keyOf.apply(r);
                    if (id == null) {
                        continue;
                    }
                    if (r.value() == null) {
                        out.remove(id);
                    } else {
                        out.put(id, r.value());
                    }
                }
            }
        }
        return out;
    }

    private static boolean done(KafkaConsumer<String, String> c, Map<TopicPartition, Long> end) {
        for (Map.Entry<TopicPartition, Long> e : end.entrySet()) {
            if (c.position(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(2));
    }
}
