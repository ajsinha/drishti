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
package com.ash.drishti.server.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The launcher's stop: no key, no endpoint; the key of the pid file from this machine stops the server (after the answer); a wrong key or
 * another machine never does.
 */
class LauncherStopControllerTest {

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/launcher/stop");
        r.setRemoteAddr(address);
        return r;
    }

    @Test
    void withoutAKeyNothingStopsTheServer() {
        CountDownLatch stopped = new CountDownLatch(1);
        LauncherStopController c = new LauncherStopController("", stopped::countDown);
        assertThat(c.stop(from("127.0.0.1"), "anything").getStatusCode().value()).isEqualTo(403);
        assertThat(c.stop(from("127.0.0.1"), "").getStatusCode().value()).isEqualTo(403);
        assertThat(stopped.getCount()).isEqualTo(1);
    }

    @Test
    void aWrongKeyOrAnotherMachineIsRefused() {
        CountDownLatch stopped = new CountDownLatch(1);
        LauncherStopController c = new LauncherStopController("k3y-of-the-pid-file", stopped::countDown);
        assertThat(c.stop(from("127.0.0.1"), "wrong").getStatusCode().value()).isEqualTo(403);
        assertThat(c.stop(from("127.0.0.1"), null).getStatusCode().value()).isEqualTo(403);
        assertThat(c.stop(from("10.0.0.7"), "k3y-of-the-pid-file").getStatusCode().value()).isEqualTo(403);
        for (String h : LauncherStopController.FORWARDING) {                       // relayed by a proxy on this machine: never
            MockHttpServletRequest r = from("127.0.0.1");
            r.addHeader(h, "127.0.0.1");
            assertThat(c.stop(r, "k3y-of-the-pid-file").getStatusCode().value()).as(h).isEqualTo(403);
        }
        assertThat(stopped.getCount()).isEqualTo(1);
    }

    @Test
    void theKeyFromThisMachineStopsTheServer() throws Exception {
        CountDownLatch stopped = new CountDownLatch(2);
        LauncherStopController c = new LauncherStopController("k3y-of-the-pid-file", stopped::countDown);
        assertThat(c.stop(from("127.0.0.1"), "k3y-of-the-pid-file").getStatusCode().value()).isEqualTo(202);
        assertThat(c.stop(from("0:0:0:0:0:0:0:1"), "k3y-of-the-pid-file").getStatusCode().value()).isEqualTo(202);
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(LauncherStopController.loopback("[::1]")).isTrue();
        assertThat(LauncherStopController.loopback("192.168.1.5")).isFalse();
    }
}
