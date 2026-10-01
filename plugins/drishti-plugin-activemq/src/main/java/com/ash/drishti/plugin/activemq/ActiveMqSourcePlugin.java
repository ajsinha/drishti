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
import org.apache.activemq.ActiveMQConnectionFactory;

/**
 * Live entities from ActiveMQ (Classic, OpenWire). Settings: {@code broker-url} (default
 * {@code failover:(tcp://localhost:61616)}, whose failover transport reconnects by itself), {@code user},
 * {@code password}, {@code destinations} ({@code queue:trades,topic:quotes}; a bare name is a queue), {@code client-id},
 * and the shared message settings ({@code kind.<destination>}, {@code id-field.<destination>}, {@code state.*},
 * {@code cache-mb}; see {@link MessageStateSource}). Topics are read through durable subscriptions, so messages sent
 * while Drishti is away are delivered when it returns; each message is acknowledged after it is stored. A supervisor
 * rebuilds the connection if the session breaks for any other reason.
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
        loop = Thread.ofVirtual().name("drishti-activemq-" + sourceName).start(this::supervise);
    }

    private void supervise() {
        long backoff = 1_000;
        while (running) {
            long started = System.nanoTime();
            try {
                consume();
            } catch (Exception e) {
                if (running) {
                    health.set("DOWN: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " (reconnecting)");
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

    private void consume() throws JMSException {
        health.set("DOWN: connecting to " + context.setting("broker-url", "failover:(tcp://localhost:61616)"));
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(context.setting("user", null), context.setting("password", null),
                context.setting("broker-url", "failover:(tcp://localhost:61616)?initialReconnectDelay=1000&maxReconnectDelay=30000"));
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
                    accept(new Inbound(names.get(i), property(m, "id"), body(m), flag(m, "deleted")));
                    m.acknowledge();                          // after it is stored: a broker outage redelivers (the store has no write-ahead log)
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
