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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.testkit.DatedSourceContract;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** An enabled var-pv-stream connector whose broker is not there waits calmly: health WAITING, one log line, no error; missing settings: not configured. */
class VarPvStreamBrokerAbsentTest {

    @Test
    void aBrokerThatIsNotThereIsWaitingNotDown() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(log);
        Level before = root.getLevel();
        root.setLevel(Level.INFO);
        VarPvStreamPlugin p = new VarPvStreamPlugin();
        try {
            p.start(DatedSourceContract.context(Map.of("bootstrap-servers", "127.0.0.1:" + port, "topic", "pv", "source-name", "pv-waiting-test")));
            Thread.sleep(2_500);
            assertThat(p.health()).startsWith("WAITING").contains("broker not reachable at").contains("live updates paused");
        } finally {
            p.close();
            root.detachAppender(log);
            root.setLevel(before);
        }
        assertThat(log.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN) && e.getLoggerName().startsWith("com.ash.drishti"))
                .noneMatch(e -> e.getThrowableProxy() != null && e.getLoggerName().startsWith("com.ash.drishti"));
        assertThat(log.list.stream().filter(e -> e.getFormattedMessage().contains("pv-waiting-test: broker not reachable")).count()).isEqualTo(1);
    }

    @Test
    void missingSettingsAreNotConfigured() {
        VarPvStreamPlugin p = new VarPvStreamPlugin();
        assertThatThrownBy(() -> p.start(DatedSourceContract.context(Map.of("topic", "pv")))).isInstanceOf(PluginNotConfigured.class);
        p.close();
    }
}
