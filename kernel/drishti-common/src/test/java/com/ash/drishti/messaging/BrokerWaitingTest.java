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
package com.ash.drishti.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The calm broker-absent state: the health words, the endpoints read from a setting, the look at a port, the one-line transitions. */
class BrokerWaitingTest {

    @Test
    void readsEndpointsFromBootstrapListsAndBrokerUrls() {
        assertThat(BrokerWaiting.endpoints("a:9092, b:9093")).containsExactly("a:9092", "b:9093");
        assertThat(BrokerWaiting.endpoints("failover:(tcp://h1:61616,tcp://h2:61616)?initialReconnectDelay=1000")).containsExactly("h1:61616", "h2:61616");
        assertThat(BrokerWaiting.endpoints("amqp://guest:guest@rabbit.local:5672/%2f")).containsExactly("rabbit.local:5672");
        assertThat(BrokerWaiting.endpoints("ssl://[::1]:61617")).containsExactly("[::1]:61617");
        assertThat(BrokerWaiting.endpoints(null)).isEmpty();
    }

    @Test
    void waitingIsItsOwnHealthWord() {
        assertThat(BrokerWaiting.is(BrokerWaiting.message("h:9092"))).isTrue();
        assertThat(BrokerWaiting.is(BrokerWaiting.lost("h:9092"))).isTrue();
        assertThat(BrokerWaiting.is("UP")).isFalse();
        assertThat(BrokerWaiting.is("DOWN: x")).isFalse();
        assertThat(BrokerWaiting.is(null)).isFalse();
        assertThat(BrokerWaiting.message("h:9092")).contains("broker not reachable at h:9092").contains("live updates paused");
    }

    @Test
    void aClosedPortIsNotReachableAndAnOpenOneIs() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
            assertThat(BrokerWaiting.reachable(List.of("127.0.0.1:" + port), 500)).isTrue();
        }
        assertThat(BrokerWaiting.reachable(List.of("127.0.0.1:" + port), 500)).isFalse();
        assertThat(BrokerWaiting.reachable(List.of(), 500)).as("nothing to look at: the client decides").isTrue();
    }

    @Test
    void backOffDoublesUpToThirtySeconds() {
        assertThat(BrokerWaiting.next(1_000)).isEqualTo(2_000);
        assertThat(BrokerWaiting.next(20_000)).isEqualTo(30_000);
        assertThat(BrokerWaiting.next(30_000)).isEqualTo(30_000);
    }
}
