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
package com.ash.drishti.plugin.feeds;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.proxy.SiteProxy;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.FakeProxy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A feed read through an outbound proxy (a fake one on a local port): via, no-proxy, the site default and {@code proxy: none}. */
class FeedProxyTest {

    private static HttpServer server;

    @BeforeAll
    static void start() throws Exception {
        byte[] csv = Files.readAllBytes(Path.of("src/test/resources/recorded/ecb-estr.csv"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, csv.length);
            ex.getResponseBody().write(csv);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @AfterEach
    void reset() {
        SiteProxy.reset();
    }

    private static FeedSourcePlugin feed(Map<String, String> extra) {
        Map<String, String> s = new HashMap<>(extra);
        s.put("feed", "ecb-estr");
        s.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/estr.csv");
        FeedSourcePlugin p = new FeedSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    @Test
    void theFeedIsFetchedThroughTheProxyWithItsCredentials() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            FeedSourcePlugin p = feed(Map.of("proxy.url", proxy.url(), "proxy.username", "svc", "proxy.password", "pw"));
            assertThat(proxy.forwarded()).isEqualTo(1);
            assertThat(p.health()).startsWith("UP via proxy").doesNotContain("pw");
        }
    }

    @Test
    void aNoProxyHostBypassesIt() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            assertThat(feed(Map.of("proxy.url", proxy.url(), "proxy.no-proxy", "127.0.0.1")).health()).startsWith("UP").contains("not used for 127.0.0.1: no-proxy");
            assertThat(proxy.served()).isZero();
        }
    }

    @Test
    void proxyNoneOverridesTheSiteDefault() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            SiteProxy.configure(Map.of("proxy.url", proxy.url()), false);
            assertThat(feed(Map.of()).health()).endsWith("(site default)");
            assertThat(proxy.forwarded()).isEqualTo(1);
            assertThat(feed(Map.of("proxy", "none")).health()).isEqualTo("UP");
            assertThat(proxy.forwarded()).isEqualTo(1);
        }
    }

    @Test
    void anUnreachableProxyIsDownNamingIt() throws Exception {
        FeedSourcePlugin p = feed(Map.of("proxy.url", "http://127.0.0.1:1"));
        assertThat(p.health()).startsWith("DOWN").contains("proxy 127.0.0.1:1");
    }
}
