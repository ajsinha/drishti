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
package com.ash.drishti.plugin.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.proxy.ProxyException;
import com.ash.drishti.api.proxy.SiteProxy;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.testkit.FakeProxy;
import com.ash.drishti.testkit.TlsFixture;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The REST connector through an outbound proxy, against a fake forward proxy on a local port (HTTP forwarding and CONNECT) that
 * counts what it serves and checks {@code Proxy-Authorization}. No real network.
 */
class RestProxyTest {

    private static HttpServer http;
    private static HttpsServer https;
    private static TlsFixture pki;

    @BeforeAll
    static void start() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/api/", ex -> reply(ex));
        http.start();
        pki = new TlsFixture();
        https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(pki.serverContext(false)));
        https.createContext("/api/", ex -> reply(ex));
        https.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        byte[] body = "{\"tradeId\":\"T1\",\"mtm\":5}".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @AfterAll
    static void stop() {
        http.stop(0);
        https.stop(0);
        pki.delete();
    }

    @AfterEach
    void reset() {
        SiteProxy.reset();
    }

    private static String httpUrl() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/api";
    }

    private static String httpsUrl() {
        return "https://localhost:" + https.getAddress().getPort() + "/api";
    }

    private static RestSourcePlugin start(String baseUrl, Map<String, String> more) {
        Map<String, String> s = new LinkedHashMap<>(more);
        s.put("base-url", baseUrl);
        s.put("timeout-ms", "5000");
        JsonCodec codec = new JsonCodec();
        RestSourcePlugin p = new RestSourcePlugin();
        p.start(new SourceContext() {
            public Map<String, String> settings() {
                return s;
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return Executors.newSingleThreadScheduledExecutor();
            }
        });
        return p;
    }

    private static boolean fetches(RestSourcePlugin p) throws Exception {
        return p.fetch(EntityRef.of("trade", "T1")).orElseThrow().data().get("mtm").asDouble() == 5;
    }

    @Test
    void aPlainRequestGoesThroughTheProxy() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            RestSourcePlugin p = start(httpUrl(), Map.of("proxy.url", proxy.url()));
            assertThat(fetches(p)).isTrue();
            assertThat(proxy.forwarded()).isEqualTo(1);
            assertThat(proxy.requests()).singleElement().asString().startsWith("GET " + httpUrl());
            assertThat(p.health()).isEqualTo("UP via proxy 127.0.0.1:" + proxy.port() + " (connector)");
        }
    }

    @Test
    void basicCredentialsAnswerTheProxysChallenge() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "s3cr3t")) {
            RestSourcePlugin p = start(httpUrl(), Map.of("proxy.url", proxy.url(), "proxy.username", "svc", "proxy.password", "s3cr3t"));
            assertThat(fetches(p)).isTrue();
            assertThat(proxy.forwarded()).isEqualTo(1);
            assertThat(proxy.authorizations()).last().isEqualTo("Basic c3ZjOnMzY3IzdA==");      // base64("svc:s3cr3t")
            assertThat(p.health()).doesNotContain("s3cr3t").doesNotContain("c3ZjOnMzY3IzdA==");
        }
    }

    @Test
    void wrongCredentialsEndInA407NamingTheStatus() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "right")) {
            RestSourcePlugin p = start(httpUrl(), Map.of("proxy.url", proxy.url(), "proxy.username", "svc", "proxy.password", "wrong"));
            assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "T1"))).isInstanceOf(IOException.class).hasMessageContaining("407");
            assertThat(proxy.served()).isZero();
            assertThat(proxy.rejected()).isPositive();
        }
    }

    @Test
    void anHttpsTargetIsTunnelledWithConnectAndKeepsItsOwnTls() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "s3cr3t")) {
            Map<String, String> s = new LinkedHashMap<>(pki.trustOnlySettings());
            s.put("proxy.url", proxy.url());
            s.put("proxy.username", "svc");
            s.put("proxy.password", "s3cr3t");
            RestSourcePlugin p = start(httpsUrl(), s);
            assertThat(fetches(p)).isTrue();
            assertThat(proxy.connects()).isEqualTo(1);
            assertThat(proxy.requests()).singleElement().asString().startsWith("CONNECT localhost:" + https.getAddress().getPort());
            assertThat(p.health()).startsWith("UP").contains("via proxy");
        }
    }

    @Test
    void theTargetCertificateIsStillCheckedThroughTheTunnel() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            Map<String, String> s = new LinkedHashMap<>(pki.wrongCaSettings());
            s.put("proxy.url", proxy.url());
            RestSourcePlugin p = start(httpsUrl(), s);
            assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "T1"))).hasStackTraceContaining("PKIX path building failed");
            assertThat(proxy.connects()).isEqualTo(1);
        }
    }

    @Test
    void aProxyThatRefusesConnectFailsTheRequest() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().refuseConnect()) {
            Map<String, String> s = new LinkedHashMap<>(pki.trustOnlySettings());
            s.put("proxy.url", proxy.url());
            RestSourcePlugin p = start(httpsUrl(), s);
            assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "T1"))).isInstanceOf(IOException.class);
            assertThat(proxy.requests()).hasSize(1);
        }
    }

    @Test
    void aNoProxyHostGoesDirect() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            RestSourcePlugin p = start(httpUrl(), Map.of("proxy.url", proxy.url(), "proxy.no-proxy", "127.0.0.0/8"));
            assertThat(fetches(p)).isTrue();
            assertThat(proxy.served()).isZero();
        }
        try (FakeProxy proxy = FakeProxy.start()) {
            RestSourcePlugin p = start(httpUrl(), Map.of("proxy.url", proxy.url(), "proxy.no-proxy", "10.0.0.0/8,.corp.example"));
            assertThat(fetches(p)).isTrue();
            assertThat(proxy.forwarded()).isEqualTo(1);
        }
    }

    @Test
    void theSiteDefaultIsInheritedAndProxyNoneOverridesIt() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            SiteProxy.configure(Map.of("proxy.url", proxy.url()), false);
            RestSourcePlugin inherits = start(httpUrl(), Map.of());
            assertThat(fetches(inherits)).isTrue();
            assertThat(proxy.forwarded()).isEqualTo(1);
            assertThat(inherits.health()).endsWith("(site default)");
            RestSourcePlugin direct = start(httpUrl(), Map.of("proxy", "none"));
            assertThat(fetches(direct)).isTrue();
            assertThat(proxy.forwarded()).as("proxy: none went direct").isEqualTo(1);
            assertThat(direct.health()).isEqualTo("UP");
        }
    }

    @Test
    void aBadProxySettingFailsTheStartNamingIt() {
        assertThatThrownBy(() -> start(httpUrl(), Map.of("proxy.url", "https://proxy:3128"))).isInstanceOf(ProxyException.class)
                .hasMessageContaining("proxy.url");
        assertThatThrownBy(() -> start(httpUrl(), Map.of("proxy.url", "http://proxy:3128", "proxy.username", "u", "proxy.password", "${NOT_SET_ANYWHERE_XYZ}")))
                .hasMessageContaining("NOT_SET_ANYWHERE_XYZ");
    }
}
