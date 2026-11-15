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
package com.ash.drishti.plugin.activemq;

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
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQSslConnectionFactory;

/**
 * ActiveMQ (Classic, OpenWire) as a live transport (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 2.5): it connects (or waits
 * calmly for its broker), receives, and hands each message to the kernel's live intake, which decodes, checks and keeps it in the live
 * state; a queue replays nothing, so the kernel keeps that state on disk ({@code disk-cache.*}) and the message is acknowledged only once
 * it is kept (else the broker delivers it again). Settings: {@code broker-url} (default {@code failover:(tcp://localhost:61616)}, whose
 * failover transport reconnects by itself; or the broker the connector names, {@code broker: <name>}), {@code user}, {@code password},
 * {@code destinations} ({@code queue:trades,topic:quotes}; a bare name is a queue), {@code client-id}, {@code max-redeliveries}; what a
 * message means ({@code kind.<destination>}, {@code shape}, {@code id-field}) is the kernel's. The message's {@code id} property is its
 * key; a {@code deleted} property or an empty body deletes the row. Topics are read through durable subscriptions, so messages sent while
 * Drishti is away are delivered when it returns. A supervisor rebuilds the connection if the session breaks for any other reason.
 *
 * <p><b>TLS.</b> A {@code broker-url} with {@code ssl://} (OpenWire over TLS, port 61617; also inside
 * {@code failover:(ssl://a:61617,ssl://b:61617)}) connects over TLS, with the shared {@code tls.*} settings
 * ({@link TlsSettings}): a private CA, a PKCS12/JKS truststore, hostname verification, and a client certificate for mutual
 * TLS. The OpenWire client takes its protocol versions from the JVM ({@code jdk.tls.client.protocols}); {@code tls.protocols}
 * and {@code tls.cipher-suites} are checked but cannot narrow them. AMQP (the {@code amqp+ssl://} scheme) is a different
 * client, not part of this connector.
 */
public final class ActiveMqSourcePlugin implements SourcePlugin, LiveTransport {

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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ActiveMqSourcePlugin.class);
    private volatile com.ash.drishti.api.BrokerWaiting.Watch watch;

    /** The broker's endpoints, for the look before a connection and the words of health. */
    private String where() {
        List<String> e = com.ash.drishti.api.BrokerWaiting.endpoints(brokerUrl());
        return e.isEmpty() ? brokerUrl() : String.join(",", e);
    }

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(sourceName == null ? "activemq" : sourceName, "1.0", java.util.Set.copyOf(kinds), new SourceCapabilities(true, false, true, false));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "activemq");
        if (java.util.Arrays.stream(context.setting("destinations", "").split(",")).allMatch(d -> d.isBlank())) {
            throw new com.ash.drishti.api.PluginNotConfigured("activemq needs settings.destinations");   // not a fault: the connector is idle
        }
        ctx.settings().forEach((k, v) -> {
            if ((k.equals("kind") || k.startsWith("kind.")) && v != null && !v.isBlank()) {
                kinds.add(v.strip());
            }
        });
        tls = tlsMaterial();                         // fails the start at once, with the file and the reason, when TLS is misconfigured
        watch = new com.ash.drishti.api.BrokerWaiting.Watch(sourceName, LOG::info);
        health.set(com.ash.drishti.api.BrokerWaiting.message(where()));   // until the supervisor has looked
    }

    @Override
    public void receive(LiveIntake intake) {
        this.intake = intake;
        loop = Thread.ofVirtual().name("drishti-activemq-" + sourceName).start(this::supervise);
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

    @Override
    public String health() {
        return tls == null ? health.get() : tls.annotate(health.get(), java.time.Instant.now());
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

    private static final Pattern SSL_ENDPOINT = Pattern.compile("(ssl://[^,)?\\s]+)(\\?[^,)\\s]*)?");

    private String brokerUrl() {
        return context.setting("broker-url", "failover:(tcp://localhost:61616)?initialReconnectDelay=1000&maxReconnectDelay=30000");
    }

    private TlsMaterial tlsMaterial() {
        TlsSettings ts = TlsSettings.from(context.settings());
        boolean ssl = brokerUrl().contains("ssl://");
        if (ts.enabled() && !ssl) {
            throw new TlsException("tls.enabled is true but broker-url has no ssl:// endpoint (OpenWire over TLS is ssl://host:61617): "
                    + brokerUrl());
        }
        return ssl ? TlsContexts.build(ts) : null;
    }

    /** The URL with {@code socket.verifyHostName=false} added to every ssl:// endpoint when hostname verification is off. */
    static String withHostnameCheck(String url, boolean verify) {
        if (verify) {
            return url;
        }
        return SSL_ENDPOINT.matcher(url).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1)
                + (m.group(2) == null ? "?" : m.group(2) + "&") + "socket.verifyHostName=false"));
    }

    /** The connection factory: an SSL one with the shared TLS material when the URL has ssl:// endpoints. */
    ActiveMQConnectionFactory factory() {
        String user = context.setting("user", null);
        String password = context.setting("password", null);
        TlsMaterial m = tls;
        if (m == null) {
            return new ActiveMQConnectionFactory(user, password, brokerUrl());
        }
        ActiveMQSslConnectionFactory f = new ActiveMQSslConnectionFactory(withHostnameCheck(brokerUrl(), m.settings().verifyHostname()));
        f.setUserName(user);
        f.setPassword(password);
        f.setKeyAndTrustManagers(m.keyManagers(), m.trustManagers(), new SecureRandom());
        return f;
    }

    private void supervise() {
        long backoff = 1_000;
        List<String> endpoints = com.ash.drishti.api.BrokerWaiting.endpoints(brokerUrl());
        while (running) {
            if (!com.ash.drishti.api.BrokerWaiting.reachable(endpoints, 1_000)) {
                health.set(com.ash.drishti.api.BrokerWaiting.message(where()));   // calm: the snapshot is served, live updates wait
                watch.waiting(where());
                if (!pause(backoff)) {
                    return;
                }
                backoff = com.ash.drishti.api.BrokerWaiting.next(backoff);
                continue;
            }
            long started = System.nanoTime();
            try {
                consume();
            } catch (Exception e) {
                if (running) {
                    health.set("DOWN: " + e.getClass().getSimpleName() + ": " + e.getMessage() + causes(e) + " (reconnecting)");
                }
            } finally {
                closeConnection();
            }
            if (System.nanoTime() - started > 60_000_000_000L) {
                backoff = 1_000;
            }
            if (!running || !pause(backoff)) {
                return;
            }
            backoff = Math.min(30_000, backoff * 2);
        }
    }

    /** True when the broker answered and refused the connection (a TLS failure anywhere in the chain), not when it is absent. */
    static boolean refused(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof javax.net.ssl.SSLException || c instanceof java.security.cert.CertificateException
                    || c instanceof java.security.GeneralSecurityException) {
                return true;
            }
        }
        return false;
    }

    /** The chain of causes, as {@code ; caused by SSLHandshakeException: PKIX path building failed ...}: a TLS failure is in there. */
    static String causes(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable c = e.getCause();
        for (int i = 0; c != null && i < 5; i++, c = c.getCause()) {
            if (c.getMessage() != null && !sb.toString().contains(c.getMessage()) && !String.valueOf(e.getMessage()).contains(c.getMessage())) {
                sb.append("; caused by ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            }
        }
        return sb.toString();
    }

    private void consume() throws JMSException {
        health.set("WAITING: connecting to " + where() + " - live updates paused");
        ActiveMQConnectionFactory cf = factory();
        // a message the live state could not keep is redelivered until it is kept (the client's default gives up after
        // 6 and moves it to ActiveMQ.DLQ); max-redeliveries sets a limit when a dead-letter queue is wanted
        cf.getRedeliveryPolicy().setMaximumRedeliveries(Integer.parseInt(context.setting("max-redeliveries", "-1")));
        Connection c = cf.createConnection();
        connection = c;
        if (c instanceof org.apache.activemq.ActiveMQConnection amq) {
            // the failover transport reconnects silently: listen to it, so health says when the broker is gone
            amq.addTransportListener(new org.apache.activemq.transport.TransportListener() {
                @Override
                public void onCommand(Object command) {}

                @Override
                public void onException(java.io.IOException error) {
                    if (refused(error)) {         // the broker is there and refuses us (TLS: an untrusted CA, a wrong host name, no client
                        health.set("DOWN: " + error.getClass().getSimpleName() + ": " + error.getMessage() + causes(error) + " (reconnecting)");
                        return;                   // certificate): a fault to fix, never the calm wait for an absent broker
                    }
                    health.set(com.ash.drishti.api.BrokerWaiting.lost(where()));
                    watch.waiting(where());
                }

                @Override
                public void transportInterupted() {
                    health.set(com.ash.drishti.api.BrokerWaiting.lost(where()));
                    watch.waiting(where());
                }

                @Override
                public void transportResumed() {
                    health.set("UP");
                    watch.connected(where());
                }
            });
        }
        c.setClientID(context.setting("client-id", "drishti-" + sourceName));
        Session session = c.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        List<MessageConsumer> consumers = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String d : context.setting("destinations", "").split(",")) {
            String spec = d.trim();
            if (spec.isEmpty()) {
                continue;
            }
            boolean topic = spec.startsWith("topic:");
            String name = spec.replaceFirst("^(queue|topic):", "");
            Destination dest = topic ? session.createTopic(name) : session.createQueue(name);
            consumers.add(topic ? session.createDurableSubscriber((Topic) dest, "drishti-" + sourceName + "-" + name) : session.createConsumer(dest));
            names.add(name);
        }
        if (consumers.isEmpty()) {
            throw new IllegalStateException("activemq plugin needs settings.destinations");
        }
        c.start();
        health.set("UP");
        watch.connected(where());
        while (running) {
            boolean any = false;
            for (int i = 0; i < consumers.size(); i++) {
                Message m = consumers.get(i).receive(consumers.size() == 1 ? 500 : 50);
                if (m != null) {
                    any = true;
                    String text = body(m);
                    if (text == null && !flag(m, "deleted")) {                // a Map, Object or Stream message: never an entity's delete
                        m.acknowledge();
                        continue;
                    }
                    boolean delete = flag(m, "deleted") || text == null || text.isBlank();
                    long at = m.getJMSTimestamp() > 0 ? m.getJMSTimestamp() : System.currentTimeMillis();
                    lastUpdate = java.time.Instant.ofEpochMilli(at);
                    long seq = sequence.updateAndGet(x -> Math.max(x + 1, System.currentTimeMillis() * 1000));
                    if (intake.accept(new LiveMessage(property(m, "id"), delete ? null : text, new LivePosition(names.get(i), 0, seq), lastUpdate))) {
                        m.acknowledge();                      // after the kernel kept it (disk-cache.durability, sync by default)
                    } else {
                        try {
                            Thread.sleep(1000);               // the live state could not keep it: not acknowledged,
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        session.recover();                    // delivered again, a second later
                    }
                }
            }
            if (!any && consumers.size() > 1) {
                Thread.onSpinWait();
            }
        }
    }

    private static String body(Message m) throws JMSException {
        if (m instanceof TextMessage t) {
            return t.getText();
        }
        if (m instanceof BytesMessage b) {
            byte[] bytes = new byte[(int) b.getBodyLength()];
            b.readBytes(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String property(Message m, String name) {
        try {
            return m.propertyExists(name) ? m.getStringProperty(name) : null;
        } catch (JMSException e) {
            return null;
        }
    }

    private static boolean flag(Message m, String name) {
        try {
            return m.propertyExists(name) && Boolean.parseBoolean(m.getStringProperty(name));
        } catch (JMSException e) {
            return false;
        }
    }

    private void closeConnection() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (JMSException ignored) {
                // already broken
            }
        }
    }

    @Override
    public void close() {
        running = false;
        closeConnection();
        if (loop != null) {
            loop.interrupt();
            try {
                loop.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
