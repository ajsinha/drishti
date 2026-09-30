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
import com.ash.drishti.testkit.BrokerOutage;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.MessageSourceContract;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The rabbitmq connector through a broker outage, against real brokers in Docker. Skipped where Docker is not reachable. */
@Testcontainers(disabledWithoutDocker = true)
class RabbitMqOutageTest extends BrokerOutage {

    @Override
    protected AutoCloseable broker(int port) {
        GenericContainer<?> c = new GenericContainer<>("rabbitmq:4.1-alpine").withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(5672))));
        c.start();
        return c::stop;
    }

    @Override
    protected SourcePlugin start(int port, Path state) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("kind." + MessageSourceContract.TRADES, "trade", "id-field." + MessageSourceContract.TRADES,
                "tradeId", "state.dir", state.toString(), "source-name", "outage-test"));
        s.put("uri", "amqp://guest:guest@localhost:" + port + "/%2f");
        s.put("recovery-interval-ms", "500");
        s.put("queues", MessageSourceContract.TRADES);
        RabbitMqSourcePlugin p = new RabbitMqSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    @Override
    protected void send(int port, String destination, String body) throws Exception {
        com.rabbitmq.client.ConnectionFactory f = new com.rabbitmq.client.ConnectionFactory();
        f.setUri("amqp://guest:guest@localhost:" + port + "/%2f");
        try (com.rabbitmq.client.Connection c = f.newConnection(); com.rabbitmq.client.Channel ch = c.createChannel()) {
            ch.queueDeclare(destination, true, false, false, null);
            ch.basicPublish("", destination, null, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
