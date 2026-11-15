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
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.live.LiveIntake;
import com.ash.drishti.api.live.LiveMessage;
import com.ash.drishti.api.live.LivePosition;
import com.ash.drishti.api.live.LiveTransport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Kafka as a live transport (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 2.5): it connects (or waits calmly for its broker),
 * reads every partition of its topics from the beginning with no consumer group and no commits (so every server builds the same state on
 * its own), and hands each record to the kernel's live intake with its position. The kernel decodes, maps, validates, orders and keeps
 * the rows, answers every read and ticks every view ({@link com.ash.drishti.api.live.LiveTransport}); this plugin holds no rows of its own.
 * It reads a record back by its offset when the kernel no longer holds a document in memory, and writes approved changes back to a topic
 * ({@link KafkaWrites}).
 *
 * <p>Settings: {@code bootstrap-servers} (from the broker the connector names, {@code broker: <name>}), {@code topics} (comma list),
 * {@code poll-ms} (200), {@code source-name}, {@code value-format} and {@code schema-registry.url} (the Confluent wire format),
 * {@code client.<property>} (any Kafka consumer property); the security settings of {@link KafkaSecurity}. What a message means
 * ({@code shape}, {@code kind}, {@code id-field}, {@code cache-mb}, {@code disk-cache.*}) is read by the kernel's intake. The former
 * {@code mode} setting is no longer read: a companion ({@code companion-of:}) is a feed over a lake (what {@code ticks} was), a live
 * connector without one holds its rows (what {@code state} was).
 */
public final class KafkaSourcePlugin implements SourcePlugin, LiveTransport {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(KafkaSourcePlugin.class);
    /** How long the consumer may have no broker connection before health says so (short blips are not reported). */
    static final long BROKER_LOST_MS = 10_000;

    private final AtomicReference<String> health = new AtomicReference<>("DOWN: not started");
    /** Where the broker is looked for, and the one-line transitions of waiting for it (a broker that is not there is calm, not a fault). */
    private volatile String bootstrap = "";
    private volatile com.ash.drishti.api.BrokerWaiting.Watch watch;
    private volatile KafkaConsumer<String, String> consumer;   // replaced when the supervisor reconnects
    private volatile Properties consumerProps;
    /** The next offset to read per partition: a reconnect resumes here instead of replaying the topic. */
    private final Map<TopicPartition, Long> resumeAt = new ConcurrentHashMap<>();
    private KafkaConsumer<String, String> reader;
    /** Guards the read-back reader (a KafkaConsumer is single-threaded). A ReentrantLock: the guarded poll is network I/O. */
    private final java.util.concurrent.locks.ReentrantLock readerLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.ReentrantLock writesLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile KafkaWrites writes;
    private volatile Function<EntityRef, Optional<EntityDocument>> current = ref -> Optional.empty();
    private Thread loop;
    private SourceContext context;
    private String sourceName;
    private List<String> topics = List.of();
    private long pollMs = 200;
    private final Set<String> kinds = new HashSet<>();
    private volatile boolean caughtUp;
    private volatile boolean running = true;
    private volatile boolean closed;
    private volatile Instant lastUpdate;
    private com.ash.drishti.api.tls.TlsMaterial tls;
    private org.apache.kafka.common.serialization.Deserializer<String> valueDeserializer = new StringDeserializer();

    /**
     * The consumer properties: the connector's own, then the security settings translated to Kafka properties, then every
     * {@code client.<property>} setting, which wins over both. Fetches are capped at 4 MB ({@code fetch.max.bytes}) and 1 MB per
     * partition ({@code max.partition.fetch.bytes}) unless a {@code client.} setting says otherwise.
     */
    static Properties consumerProperties(Map<String, String> settings, String sourceName, KafkaSecurity.Result security) {
        Properties p = new Properties();
        String bootstrap = settings.get("bootstrap-servers");
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap == null || bootstrap.isBlank() ? "localhost:9092" : bootstrap);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, "drishti-" + sourceName);
        // a fetch is read through one temporary direct buffer per poll thread: keep it small (Kafka's defaults are 50 MB and
        // 1 MB per partition); a record batch larger than these still arrives. client.fetch.max.bytes and
        // client.max.partition.fetch.bytes override them.
        p.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, String.valueOf(4 << 20));
        p.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, String.valueOf(1 << 20));
        security.properties().forEach(p::put);
        settings.forEach((k, v) -> {
            if (k.startsWith("client.")) {
                p.put(k.substring(7), v);
            }
        });
        return p;
    }

    /**
     * The value deserializer: plain text, or (with {@code schema-registry.url} or {@code value-format: confluent}) text that also
     * reads the Confluent wire format, see {@link ConfluentValueDeserializer}.
     */
    private static org.apache.kafka.common.serialization.Deserializer<String> valueDeserializer(Map<String, String> settings) {
        String format = settings.getOrDefault("value-format", "").strip().toLowerCase(java.util.Locale.ROOT);
        boolean registry = settings.get("schema-registry.url") != null && !settings.get("schema-registry.url").isBlank();
        if (format.equals("string") || (format.isEmpty() && !registry)) {
            return new StringDeserializer();
        }
        if (!format.isEmpty() && !format.equals("confluent")) {
            throw new IllegalStateException("value-format '" + format + "' is not string or confluent");
        }
        return new ConfluentValueDeserializer(registry ? new SchemaRegistryClient(settings) : null);
    }

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(sourceName == null ? "kafka" : sourceName, "1.0", Set.copyOf(kinds), new SourceCapabilities(true, false, true, false));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "kafka");
        this.topics = List.of(ctx.setting("topics", "").split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (topics.isEmpty()) {
            throw new com.ash.drishti.api.PluginNotConfigured("kafka needs settings.topics");
        }
        ctx.settings().forEach((k, v) -> {
            if ((k.equals("kind") || k.startsWith("kind.")) && v != null && !v.isBlank()) {
                kinds.add(v.strip());                                 // what the kernel's intake will read the topics as
            }
        });
        String mode = ctx.setting("mode", "");
        if (!mode.isBlank()) {
            LOG.info("{}: 'mode: {}' is no longer read: a companion (companion-of: <data connector>) is a feed over a lake (what ticks was); "
                    + "a live connector without one holds its rows (what state was). Remove the setting.", sourceName, mode);
        }
        KafkaSecurity.Result security = KafkaSecurity.translate(ctx.settings());
        Properties p = consumerProperties(ctx.settings(), sourceName, security);
        this.tls = security.tls();
        this.valueDeserializer = valueDeserializer(ctx.settings());
        this.bootstrap = String.valueOf(p.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        this.watch = new com.ash.drishti.api.BrokerWaiting.Watch(sourceName, LOG::info);
        health.set(com.ash.drishti.api.BrokerWaiting.message(bootstrap));   // until the supervisor has looked
        consumerProps = p;                           // the consumer is created (and re-created) by the supervisor
        Properties rp = new Properties();
        rp.putAll(p);
        rp.put(ConsumerConfig.CLIENT_ID_CONFIG, "drishti-" + sourceName + "-reader");
        rp.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        reader = new KafkaConsumer<>(rp, new StringDeserializer(), valueDeserializer);
        this.pollMs = Long.parseLong(ctx.setting("poll-ms", "200"));
    }

    @Override
    public boolean replayable() {
        return true;
    }

    @Override
    public void current(Function<EntityRef, Optional<EntityDocument>> read) {
        this.current = read == null ? ref -> Optional.empty() : read;
    }

    @Override
    public void receive(LiveIntake intake) {
        if (loop != null) {
            throw new IllegalStateException(sourceName + " is already receiving");
        }
        loop = Thread.ofVirtual().name("drishti-kafka-" + sourceName).start(() -> supervise(intake));
    }

    /**
     * Keeps a consumer running for as long as the plugin is open. The Kafka client rides out short broker outages by
     * itself; when it gives up (the broker was down at start, the topic is not there yet, a fatal error), the
     * supervisor waits (1 s doubling to 30 s), creates a new consumer and resumes where the last one stopped.
     */
    private void supervise(LiveIntake intake) {
        long backoff = 1_000;
        List<String> endpoints = com.ash.drishti.api.BrokerWaiting.endpoints(bootstrap);
        while (running) {
            if (!com.ash.drishti.api.BrokerWaiting.reachable(endpoints, 1_000)) {
                health.set(com.ash.drishti.api.BrokerWaiting.message(bootstrap));   // calm: the snapshot is served, live updates wait
                watch.waiting(bootstrap);
                if (!pause(backoff)) {
                    return;
                }
                backoff = com.ash.drishti.api.BrokerWaiting.next(backoff);
                continue;
            }
            KafkaConsumer<String, String> c;
            try {
                c = new KafkaConsumer<>(consumerProps, new StringDeserializer(), valueDeserializer);
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
                    run(c, intake);
                }
            } catch (WakeupException e) {
                // closing
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                if (e instanceof org.apache.kafka.common.errors.TimeoutException || e instanceof IllegalStateException) {
                    health.set("WAITING: " + e.getMessage() + " - live updates paused; retrying");   // the broker answers but the topic is not there yet
                    watch.waiting(bootstrap);
                } else {
                    health.set("DOWN: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " (reconnecting)");
                }
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

    private void run(KafkaConsumer<String, String> c, LiveIntake intake) {
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
        watch.connected(bootstrap);
        org.apache.kafka.common.Metric connections = c.metrics().entrySet().stream()
                .filter(e -> "connection-count".equals(e.getKey().name()) && "consumer-metrics".equals(e.getKey().group()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        long lostSince = 0;
        while (running) {
            for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(pollMs))) {
                hand(intake, r);
                resumeAt.put(new TopicPartition(r.topic(), r.partition()), r.offset() + 1);
            }
            // the client rides out a broker outage inside poll() without failing, so health watches its connections:
            // none for BROKER_LOST_MS means the broker is gone (the client keeps reconnecting by itself)
            boolean connected = connections == null || ((Number) connections.metricValue()).doubleValue() > 0;
            if (!connected && lostSince == 0) {
                lostSince = System.nanoTime();
            } else if (!connected && System.nanoTime() - lostSince > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(BROKER_LOST_MS)) {
                health.set(com.ash.drishti.api.BrokerWaiting.lost(bootstrap));
                watch.waiting(bootstrap);
            } else if (connected && lostSince != 0) {
                lostSince = 0;
                health.set(caughtUp ? "UP" : "UP (catching up)");
                watch.connected(bootstrap);
            }
            if (!caughtUp && end.entrySet().stream().allMatch(e -> c.position(e.getKey()) >= e.getValue())) {
                caughtUp = true;
                health.set("UP");
                intake.caughtUp();
            }
        }
    }

    /** One record to the kernel's intake, with its position; the stream goes on whatever the intake makes of it. */
    void hand(LiveIntake intake, ConsumerRecord<String, String> r) {
        Instant at = Instant.ofEpochMilli(r.timestamp());
        Instant was = lastUpdate;
        if (was == null || at.isAfter(was)) {
            lastUpdate = at;                                  // one consumer thread hands records: no lost update
        }
        intake.accept(message(r));
    }

    private static LiveMessage message(ConsumerRecord<String, String> r) {
        return new LiveMessage(r.key(), r.value(), new LivePosition(r.topic(), r.partition(), r.offset()), Instant.ofEpochMilli(r.timestamp()));
    }

    /** The newest record's time: when the stream last brought new data. */
    @Override
    public Instant lastUpdate() {
        return lastUpdate;
    }

    /** This plugin holds no rows: the kernel's live state answers every read of a live connector. */
    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        return Optional.empty();
    }

    /**
     * Reads the one record at {@code at} (the kernel no longer holds its document in memory); a single reader, so reads are serialised.
     * Waits at most five seconds for the reader, and gives up (empty) once the plugin is closing.
     */
    @Override
    public Optional<LiveMessage> readBack(LivePosition at) {
        if (reader == null || at.offset() < 0) {
            return Optional.empty();
        }
        try {
            if (closed || !readerLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            if (closed) {
                return Optional.empty();
            }
            TopicPartition tp = new TopicPartition(at.stream(), at.partition());
            reader.assign(List.of(tp));
            reader.seek(tp, at.offset());
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < until) {
                for (ConsumerRecord<String, String> r : reader.poll(Duration.ofMillis(200))) {
                    if (r.offset() == at.offset()) {
                        return Optional.of(message(r));
                    }
                    if (r.offset() > at.offset()) {
                        return Optional.empty();
                    }
                }
            }
            return Optional.empty();
        } catch (org.apache.kafka.common.KafkaException | IllegalStateException e) {
            return Optional.empty();                  // broker trouble or closing: a miss, not an error for the viewer
        } finally {
            readerLock.unlock();
        }
    }

    /** {@code kafka:} commands of a connector's {@code writes:} (docs/guides/APPROVALS_GUIDE.md section 10): see {@link KafkaWrites}. */
    @Override
    public Optional<String> writeKey() {
        return Optional.of("kafka");
    }

    @Override
    public List<String> checkWrite(String command, Map<String, Object> block, java.util.Set<String> known) {
        return KafkaWrites.check(command, block, known);
    }

    @Override
    public com.ash.drishti.api.write.WriteResult write(com.ash.drishti.api.write.WriteCommand command) throws Exception {
        KafkaWrites w = writes;
        if (w == null) {
            writesLock.lock();
            try {
                if (writes == null) {
                    writes = new KafkaWrites(context.settings(), sourceName, KafkaSecurity.translate(context.settings()));
                }
                w = writes;
            } finally {
                writesLock.unlock();
            }
        }
        return w.write(command, current);
    }

    @Override
    public String health() {
        String h = health.get();
        return tls == null ? h : tls.annotate(h, Instant.now());
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
        KafkaWrites w = writes;
        if (w != null) {
            w.close();
        }
    }
}
