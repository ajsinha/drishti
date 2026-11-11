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
package com.ash.drishti.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A message connector started before its broker exists recovers without help: it connects when the broker comes up,
 * and again after the broker is killed and started once more on the same port. A broker's test supplies how to start
 * a broker on a port, the plugin, and a producer.
 */
public abstract class BrokerOutage {

    /** A running broker on {@code port}; closing it kills the broker. */
    protected abstract AutoCloseable broker(int port) throws Exception;

    protected abstract SourcePlugin start(int port, Path state) throws Exception;

    protected abstract void send(int port, String destination, String body) throws Exception;

    private static boolean has(SourcePlugin p, String id) {
        try {
            return p.fetch(EntityRef.of("trade", id)).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    @org.junit.jupiter.api.Test
    void startsBeforeTheBrokerAndRecoversAfterItIsKilled() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        SourcePlugin p = start(port, Files.createTempDirectory("drishti-outage"));
        try {
            Thread.sleep(1000);
            assertThat(p.health()).startsWith("DOWN");                               // up, waiting for its broker
            try (AutoCloseable b = broker(port)) {
                MessageSourceContract.eventually(() -> p.health().startsWith("UP"), 90);
                send(port, MessageSourceContract.TRADES, "{\"tradeId\":\"O-1\",\"mtm\":1}");
                MessageSourceContract.eventually(() -> has(p, "O-1"), 30);
            }
            MessageSourceContract.eventually(() -> p.health().startsWith("DOWN"), 60);   // the broker died
            try (AutoCloseable b = broker(port)) {
                send(port, MessageSourceContract.TRADES, "{\"tradeId\":\"O-2\",\"mtm\":2}");
                MessageSourceContract.eventually(() -> has(p, "O-2"), 90);            // reconnected by itself
                assertThat(has(p, "O-1")).isTrue();
            }
        } finally {
            p.close();
        }
    }
}
