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

/** The activemq connector through a broker outage, against real brokers in Docker. Skipped where Docker is not reachable. */
@Testcontainers(disabledWithoutDocker = true)
class ActiveMqOutageTest extends BrokerOutage {

    @Override
    protected AutoCloseable broker(int port) {
        GenericContainer<?> c = new GenericContainer<>("apache/activemq-classic:6.1.7").withExposedPorts(61616).waitingFor(Wait.forListeningPort())
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(61616))));
        c.start();
        return c::stop;
    }

    @Override
    protected SourcePlugin start(int port, Path state) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("kind." + MessageSourceContract.TRADES, "trade", "id-field." + MessageSourceContract.TRADES,
                "tradeId", "state.dir", state.toString(), "source-name", "outage-test"));
        s.put("broker-url", "failover:(tcp://localhost:" + port + ")?initialReconnectDelay=200&maxReconnectDelay=1000");
        s.put("user", "admin");
        s.put("password", "admin");
        s.put("destinations", "queue:" + MessageSourceContract.TRADES);
        ActiveMqSourcePlugin p = new ActiveMqSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    @Override
    protected void send(int port, String destination, String body) throws Exception {
        try (jakarta.jms.Connection c = new org.apache.activemq.ActiveMQConnectionFactory("admin", "admin", "tcp://localhost:" + port).createConnection();
             jakarta.jms.Session s = c.createSession(false, jakarta.jms.Session.AUTO_ACKNOWLEDGE)) {
            s.createProducer(s.createQueue(destination)).send(s.createTextMessage(body));
        }
    }
}
