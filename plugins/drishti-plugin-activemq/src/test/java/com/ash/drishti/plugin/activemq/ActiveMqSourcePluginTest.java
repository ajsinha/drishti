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
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.MessageSourceContract;
import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The ActiveMQ connector against ActiveMQ Classic 6.1 in Docker. Skipped where Docker is not reachable. */
@Testcontainers(disabledWithoutDocker = true)
class ActiveMqSourcePluginTest extends MessageSourceContract {

    @Container
    static final GenericContainer<?> BROKER = new GenericContainer<>("apache/activemq-classic:6.1.7").withExposedPorts(61616)
            .waitingFor(Wait.forListeningPort());

    private static String url() {
        return "tcp://" + BROKER.getHost() + ":" + BROKER.getMappedPort(61616);
    }

    @Override
    protected SourcePlugin start(Path state) throws Exception {
        Map<String, String> s = new HashMap<>(shared(state));
        s.put("broker-url", "failover:(" + url() + ")?initialReconnectDelay=200&maxReconnectDelay=2000");
        s.put("user", "admin");
        s.put("password", "admin");
        s.put("destinations", "queue:" + TRADES + ",queue:" + ENTITIES);
        ActiveMqSourcePlugin p = new ActiveMqSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    @Override
    protected void send(String destination, String id, String body, boolean deleted) throws Exception {
        try (Connection c = new ActiveMQConnectionFactory("admin", "admin", url()).createConnection();
             Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
             MessageProducer p = s.createProducer(s.createQueue(destination))) {
            TextMessage m = s.createTextMessage(body);
            if (id != null) {
                m.setStringProperty("id", id);
            }
            if (deleted) {
                m.setStringProperty("deleted", "true");
            }
            p.send(m);
        }
    }
}
