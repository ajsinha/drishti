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
import com.ash.drishti.messaging.MessageStateSource;
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
 * Live entities from ActiveMQ (Classic, OpenWire). Settings: {@code broker-url} (default
 * {@code failover:(tcp://localhost:61616)}, whose failover transport reconnects by itself), {@code user},
 * {@code password}, {@code destinations} ({@code queue:trades,topic:quotes}; a bare name is a queue), {@code client-id},
 * and the shared message settings ({@code kind.<destination>}, {@code id-field.<destination>}, {@code state.*},
 * {@code cache-mb}; see {@link MessageStateSource}). Topics are read through durable subscriptions, so messages sent
 * while Drishti is away are delivered when it returns; each message is acknowledged after it is stored. A supervisor
 * rebuilds the connection if the session breaks for any other reason.
 *
 * <p><b>TLS.</b> A {@code broker-url} with {@code ssl://} (OpenWire over TLS, port 61617; also inside
 * {@code failover:(ssl://a:61617,ssl://b:61617)}) connects over TLS, with the shared {@code tls.*} settings
 * ({@link TlsSettings}): a private CA, a PKCS12/JKS truststore, hostname verification, and a client certificate for mutual
 * TLS. The OpenWire client takes its protocol versions from the JVM ({@code jdk.tls.client.protocols}); {@code tls.protocols}
 * and {@code tls.cipher-suites} are checked but cannot narrow them. AMQP (the {@code amqp+ssl://} scheme) is a different
 * client, not part of this connector.
 */
public final class ActiveMqSourcePlugin extends MessageStateSource {

    private volatile boolean running = true;
    private volatile Connection connection;
    private Thread loop;

    @Override
    protected String plugin() {
        return "activemq";
    }

    @Override
    protected void connect() {
        tls = tlsMaterial();                         // fails the start at once, with the file and the reason, when TLS is misconfigured
        loop = Thread.ofVirtual().name("drishti-activemq-" + sourceName).start(this::supervise);
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
        while (running) {
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
        health.set("DOWN: connecting to " + context.setting("broker-url", "failover:(tcp://localhost:61616)"));
        ActiveMQConnectionFactory cf = factory();
        // a message the state store could not keep is redelivered until it is kept (the client's default gives up after
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
                    health.set("DOWN: " + error.getMessage() + " (reconnecting)");
                }

                @Override
                public void transportInterupted() {
                    health.set("DOWN: connection to the broker lost (reconnecting)");
                }

                @Override
                public void transportResumed() {
                    health.set("UP");
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
        while (running) {
            boolean any = false;
            for (int i = 0; i < consumers.size(); i++) {
                Message m = consumers.get(i).receive(consumers.size() == 1 ? 500 : 50);
                if (m != null) {
                    any = true;
                    if (accept(new Inbound(names.get(i), property(m, "id"), body(m), flag(m, "deleted")))) {
                        m.acknowledge();                      // after it is kept (state.durability, sync by default)
                    } else {
                        try {
                            Thread.sleep(1000);               // the state store could not keep it: not acknowledged,
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
        super.close();
    }
}
