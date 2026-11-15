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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.testkit.DatedSourceContract;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** An enabled RabbitMqSourcePlugin connector whose broker is not there is calm: waiting with the reason, no error, no stack trace; settings missing: not configured. */
class RabbitMqBrokerAbsentTest {

    @TempDir
    Path state;

    static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void aBrokerThatIsNotThereIsWaitingNotDown() throws Exception {
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(log);
        Level before = root.getLevel();
        root.setLevel(Level.INFO);
        int port = closedPort();
        com.ash.drishti.live.LiveSource p = new com.ash.drishti.live.LiveSource(new RabbitMqSourcePlugin(),
                com.ash.drishti.live.KindContracts.NONE, java.util.Set.of(), null);   // as the kernel runs it
        try {
            Map<String, String> s = new HashMap<>(Map.of("uri", "amqp://guest:guest@127.0.0.1:" + port + "/%2f", "queues", "trades", "kind", "trade"));
            s.put("disk-cache.dir", state.toString());
            s.put("source-name", "waiting-test");
            p.start(DatedSourceContract.context(s));
            Thread.sleep(2_500);                                   // several looks, one line
            assertThat(p.health()).startsWith("WAITING").contains("broker not reachable at").contains("live updates paused");
        } finally {
            p.close();
            root.detachAppender(log);
            root.setLevel(before);
        }
        assertThat(log.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN) && e.getLoggerName().startsWith("com.ash.drishti"))
                .noneMatch(e -> e.getThrowableProxy() != null && e.getLoggerName().startsWith("com.ash.drishti"));
        assertThat(log.list.stream().filter(e -> e.getFormattedMessage().contains("waiting-test: broker not reachable")).count()).as("one line when it starts waiting").isEqualTo(1);
    }

    @Test
    void missingSettingsAreNotConfiguredNotAFailure() throws Exception {
        RabbitMqSourcePlugin p = new RabbitMqSourcePlugin();
        Map<String, String> s = new HashMap<>(Map.of("uri", "amqp://guest:guest@127.0.0.1:5672/%2f"));
        s.put("disk-cache.dir", state.toString());
        assertThatThrownBy(() -> p.start(DatedSourceContract.context(s))).isInstanceOf(PluginNotConfigured.class);
        p.close();
    }
}
