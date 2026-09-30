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

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.MessageSourceContract;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The RabbitMQ connector against RabbitMQ 4.1 in Docker. Skipped where Docker is not reachable. */
@Testcontainers(disabledWithoutDocker = true)
class RabbitMqSourcePluginTest extends MessageSourceContract {

    @Container
    static final GenericContainer<?> BROKER = new GenericContainer<>("rabbitmq:4.1-alpine").withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    private static String uri() {
        return "amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getMappedPort(5672) + "/%2f";
    }

    @Override
    protected SourcePlugin start(Path state) throws Exception {
        Map<String, String> s = new HashMap<>(shared(state));
        s.put("uri", uri());
        s.put("queues", TRADES + "," + ENTITIES);
        RabbitMqSourcePlugin p = new RabbitMqSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    @Override
    protected void send(String destination, String id, String body, boolean deleted) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setUri(uri());
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            ch.queueDeclare(destination, true, false, false, null);
            Map<String, Object> headers = new HashMap<>();
            if (id != null) {
                headers.put("id", id);
            }
            if (deleted) {
                headers.put("deleted", "true");
            }
            ch.basicPublish("", destination, new AMQP.BasicProperties.Builder().headers(headers).deliveryMode(2).build(),
                    body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
