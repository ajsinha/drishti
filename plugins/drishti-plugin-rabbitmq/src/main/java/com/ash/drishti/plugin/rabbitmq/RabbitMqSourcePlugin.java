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

import com.ash.drishti.messaging.MessageStateSource;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Live entities from RabbitMQ (AMQP 0-9-1). Settings: {@code uri} ({@code amqp://guest:guest@localhost:5672/%2f}),
 * {@code queues} ({@code trades,quotes}), {@code declare} (declare the queues durable; default true),
 * {@code bind.<queue>} ({@code exchange:routing.key} to bind a declared queue), {@code prefetch} (100), and the shared
 * message settings (see {@link MessageStateSource}). The client's automatic recovery reconnects and re-subscribes
 * after an outage; a supervisor keeps trying until the first connection succeeds. Each message is acknowledged after
 * it is kept in the local state store with {@code state.durability} ({@code sync} by default: synced to disk, so neither a
 * crash nor a power loss loses an acknowledged message); a message the store cannot keep is requeued.
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
        loop = Thread.ofVirtual().name("drishti-rabbitmq-" + sourceName).start(this::firstConnection);
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
