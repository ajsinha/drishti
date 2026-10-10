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
import com.ash.drishti.messaging.MessageStateSource;
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
 * Live entities from RabbitMQ (AMQP 0-9-1). Settings: {@code uri} ({@code amqp://guest:guest@localhost:5672/%2f}),
 * {@code queues} ({@code trades,quotes}), {@code declare} (declare the queues durable; default true),
 * {@code bind.<queue>} ({@code exchange:routing.key} to bind a declared queue), {@code prefetch} (100), and the shared
 * message settings (see {@link MessageStateSource}). The client's automatic recovery reconnects and re-subscribes
 * after an outage; a supervisor keeps trying until the first connection succeeds. Each message is acknowledged after
 * it is kept in the local state store with {@code state.durability} ({@code sync} by default: synced to disk, so neither a
 * crash nor a power loss loses an acknowledged message); a message the store cannot keep is requeued.
 *
 * <p><b>TLS.</b> An {@code amqps://} {@code uri} (port 5671) connects over TLS, with the shared {@code tls.*} settings
 * ({@link TlsSettings}): a private CA, a PKCS12/JKS truststore, hostname verification, and a client certificate for mutual
 * TLS. {@code auth-mechanism: external} then logs in with that certificate (RabbitMQ's {@code rabbitmq_auth_mechanism_ssl}
 * plugin) instead of a user name and password.
 */
public final class RabbitMqSourcePlugin extends MessageStateSource {

    private volatile boolean running = true;
    private volatile Connection connection;
    private Thread loop;

    @Override
    protected String plugin() {
        return "rabbitmq";
    }

    @Override
    protected void connect() {
        tls = tlsMaterial();                         // fails the start at once, with the file and the reason, when TLS is misconfigured
        loop = Thread.ofVirtual().name("drishti-rabbitmq-" + sourceName).start(this::firstConnection);
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
        while (running) {
            try {
                open();
                return;
            } catch (Exception e) {
                health.set("DOWN: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " (retrying)");
                closeConnection();
            }
            if (!pause(backoff)) {
                return;
            }
            backoff = Math.min(30_000, backoff * 2);
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
                boolean kept = accept(new Inbound(queue, h.get("id") == null ? p.getMessageId() : String.valueOf(h.get("id")), body,
                        Boolean.parseBoolean(String.valueOf(h.get("deleted")))));
                if (kept) {
                    ch.basicAck(delivery.getEnvelope().getDeliveryTag(), false);   // after it is kept (state.durability, sync by default)
                } else {
                    try {
                        Thread.sleep(1000);                   // the state store could not keep it: back on the queue, a second later
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
            return "DOWN: connection lost (recovering)";
        }
        return super.health();
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
        super.close();
    }
}
