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
package com.ash.drishti.plugin.rabbitmq;

import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
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
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultSaslConfig;
import com.rabbitmq.client.SocketConfigurators;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.SSLSocket;

/**
 * RabbitMQ (AMQP 0-9-1) as a live transport (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 2.5): it connects (or waits calmly for
 * its broker), receives, and hands each delivery to the kernel's live intake, which decodes, checks and keeps it in the live state; a
 * queue replays nothing, so the kernel keeps that state on disk ({@code disk-cache.*}, {@code disk-cache.durability} {@code sync} by
 * default: neither a crash nor a power loss loses an acknowledged message) and a delivery is acknowledged only once it is kept (else it is
 * requeued). Settings: {@code uri} ({@code amqp://guest:guest@localhost:5672/%2f}, or the broker the connector names, {@code broker:
 * <name>}), {@code queues} ({@code trades,quotes}), {@code declare} (declare the queues durable; default true), {@code bind.<queue>}
 * ({@code exchange:routing.key} to bind a declared queue), {@code prefetch} (100); what a message means ({@code kind.<queue>},
 * {@code shape}, {@code id-field}) is the kernel's. A delivery's {@code id} header (else its message id) is its key; a {@code deleted}
 * header or an empty body deletes the row. The client's automatic recovery reconnects and re-subscribes after an outage; a supervisor
 * keeps trying until the first connection succeeds.
 *
 * <p><b>TLS.</b> An {@code amqps://} {@code uri} (port 5671) connects over TLS, with the shared {@code tls.*} settings
 * ({@link TlsSettings}): a private CA, a PKCS12/JKS truststore, hostname verification, and a client certificate for mutual
 * TLS. {@code auth-mechanism: external} then logs in with that certificate (RabbitMQ's {@code rabbitmq_auth_mechanism_ssl}
 * plugin) instead of a user name and password.
 */
public final class RabbitMqSourcePlugin implements SourcePlugin, LiveTransport {

    private volatile boolean running = true;
    private SourceContext context;
    private String sourceName;
    private volatile LiveIntake intake;
    private final java.util.concurrent.atomic.AtomicReference<String> health = new java.util.concurrent.atomic.AtomicReference<>("DOWN: not started");
    private volatile TlsMaterial tls;
    private volatile java.time.Instant lastUpdate;
    private final java.util.Set<String> kinds = new java.util.HashSet<>();
    /** The delivery sequence: increasing across restarts (from the clock), the order of a queue's messages when a kind has no order field. */
    private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
    private volatile Connection connection;
    private Thread loop;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RabbitMqSourcePlugin.class);
    private volatile com.ash.drishti.api.BrokerWaiting.Watch watch;

    /** The broker's endpoint, for the look before a connection and the words of health. */
    private String where() {
        String uri = context.setting("uri", "amqp://guest:guest@localhost:5672/%2f");
        java.util.List<String> e = com.ash.drishti.api.BrokerWaiting.endpoints(uri);
        return e.isEmpty() ? redact(uri) : String.join(",", e);
    }

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(sourceName == null ? "rabbitmq" : sourceName, "1.0", java.util.Set.copyOf(kinds), new SourceCapabilities(true, false, true, false));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "rabbitmq");
        tls = tlsMaterial();                         // fails the start at once, with the file and the reason, when TLS is misconfigured
        if (java.util.Arrays.stream(context.setting("queues", "").split(",")).allMatch(q -> q.isBlank())) {
            throw new com.ash.drishti.api.PluginNotConfigured("rabbitmq needs settings.queues");   // not a fault: the connector is idle
        }
        ctx.settings().forEach((k, v) -> {
            if ((k.equals("kind") || k.startsWith("kind.")) && v != null && !v.isBlank()) {
                kinds.add(v.strip());
            }
        });
        watch = new com.ash.drishti.api.BrokerWaiting.Watch(sourceName, LOG::info);
        health.set(com.ash.drishti.api.BrokerWaiting.message(where()));   // until the supervisor has looked
    }

    @Override
    public void receive(LiveIntake intake) {
        this.intake = intake;
        loop = Thread.ofVirtual().name("drishti-rabbitmq-" + sourceName).start(this::firstConnection);
    }

    /** A queue delivers each message once: nothing is replayed at start (the kernel keeps the state on disk). */
    @Override
    public boolean replayable() {
        return false;
    }

    /** This plugin holds no rows: the kernel's live state answers every read. */
    @Override
    public java.util.Optional<EntityDocument> fetch(EntityRef ref) {
        return java.util.Optional.empty();
    }

    @Override
    public java.time.Instant lastUpdate() {
        return lastUpdate;
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private TlsMaterial tlsMaterial() {
        TlsSettings ts = TlsSettings.from(context.settings());
        String uri = context.setting("uri", "amqp://guest:guest@localhost:5672/%2f");
        boolean amqps = uri.regionMatches(true, 0, "amqps:", 0, 6);
        if (ts.enabled() && !amqps) {
            throw new TlsException("tls.enabled is true but uri is not amqps:// (the TLS port is 5671): " + redact(uri));
        }
        if (!amqps) {
            if (isExternal()) {
                throw new TlsException("auth-mechanism: external needs an amqps:// uri and a client certificate (tls.cert-file or tls.keystore)");
            }
            return null;
        }
        TlsMaterial m = TlsContexts.build(ts);
        if (isExternal() && m.clientKey() == null) {
            throw new TlsException("auth-mechanism: external logs in with a client certificate: set tls.cert-file and tls.key-file, or tls.keystore");
        }
        return m;
    }

    private boolean isExternal() {
        String a = context.setting("auth-mechanism", "plain");
        if (!a.equalsIgnoreCase("plain") && !a.equalsIgnoreCase("external")) {
            throw new TlsException("auth-mechanism '" + a + "' is not plain or external");
        }
        return a.equalsIgnoreCase("external");
    }

    /** The uri without its user name and password. */
    private static String redact(String uri) {
        return uri.replaceFirst("//[^/@]*@", "//");
    }

    /** Applies the TLS material and the log-in mechanism to a connection factory. */
    static void secure(ConnectionFactory f, TlsMaterial m, boolean external) {
        if (m == null) {
            return;
        }
        f.useSslProtocol(m.sslContext());
        f.setSocketConfigurator(SocketConfigurators.defaultConfigurator().andThen(socket -> {
            if (socket instanceof SSLSocket ssl) {
                ssl.setSSLParameters(m.sslParameters());     // protocols, cipher suites and the hostname check
            }
        }));
        if (external) {
            f.setSaslConfig(DefaultSaslConfig.EXTERNAL);
        }
    }

    /** Until the first connection succeeds; after that the client's automatic recovery takes over. */
    private void firstConnection() {
        long backoff = 1_000;
        java.util.List<String> endpoints = com.ash.drishti.api.BrokerWaiting.endpoints(context.setting("uri", "amqp://guest:guest@localhost:5672/%2f"));
        while (running) {
            if (com.ash.drishti.api.BrokerWaiting.reachable(endpoints, 1_000)) {
                try {
                    open();
                    watch.connected(where());
                    return;
                } catch (Exception e) {
                    health.set("DOWN: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " (retrying)");
                    closeConnection();
                }
            } else {
                health.set(com.ash.drishti.api.BrokerWaiting.message(where()));   // calm: the snapshot is served, live updates wait
                watch.waiting(where());
            }
            if (!pause(backoff)) {
                return;
            }
            backoff = com.ash.drishti.api.BrokerWaiting.next(backoff);
        }
    }

    private void open() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setUri(context.setting("uri", "amqp://guest:guest@localhost:5672/%2f"));
        secure(f, tls, isExternal());
        f.setAutomaticRecoveryEnabled(true);
        f.setTopologyRecoveryEnabled(true);
        f.setNetworkRecoveryInterval(Long.parseLong(context.setting("recovery-interval-ms", "2000")));
        f.setRequestedHeartbeat(Integer.parseInt(context.setting("heartbeat-seconds", "20")));
        Connection c = f.newConnection("drishti-" + sourceName);
        connection = c;
        Channel ch = c.createChannel();
        ch.basicQos(Integer.parseInt(context.setting("prefetch", "100")));
        boolean declare = Boolean.parseBoolean(context.setting("declare", "true"));
        for (String q : context.setting("queues", "").split(",")) {
            String queue = q.trim();
            if (queue.isEmpty()) {
                continue;
            }
            if (declare) {
                ch.queueDeclare(queue, true, false, false, null);
                String bind = context.setting("bind." + queue, "");
                if (bind.contains(":")) {
                    ch.queueBind(queue, bind.substring(0, bind.indexOf(':')), bind.substring(bind.indexOf(':') + 1));
                }
            }
            ch.basicConsume(queue, false, "drishti-" + sourceName + "-" + queue, (tag, delivery) -> {
                AMQP.BasicProperties p = delivery.getProperties();
                Map<String, Object> h = p.getHeaders() == null ? Map.of() : p.getHeaders();
                String body = delivery.getBody() == null ? null : new String(delivery.getBody(), StandardCharsets.UTF_8);
                boolean delete = Boolean.parseBoolean(String.valueOf(h.get("deleted"))) || body == null || body.isBlank();
                lastUpdate = p.getTimestamp() == null ? java.time.Instant.now() : p.getTimestamp().toInstant();
                long seq = sequence.updateAndGet(x -> Math.max(x + 1, System.currentTimeMillis() * 1000));
                boolean kept = intake.accept(new LiveMessage(h.get("id") == null ? p.getMessageId() : String.valueOf(h.get("id")), delete ? null : body,
                        new LivePosition(queue, 0, seq), lastUpdate));
                if (kept) {
                    ch.basicAck(delivery.getEnvelope().getDeliveryTag(), false);   // after the kernel kept it (disk-cache.durability, sync by default)
                } else {
                    try {
                        Thread.sleep(1000);                   // the live state could not keep it: back on the queue, a second later
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ch.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                }
            }, tag -> health.set("DOWN: consumer cancelled on " + queue));
        }
        health.set("UP");
    }

    @Override
    public String health() {
        Connection c = connection;
        if (c != null && !c.isOpen()) {
            return com.ash.drishti.api.BrokerWaiting.lost(where());
        }
        return tls == null ? health.get() : tls.annotate(health.get(), java.time.Instant.now());
    }

    private void closeConnection() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // already broken
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (loop != null) {
            loop.interrupt();
        }
        closeConnection();
    }
}
