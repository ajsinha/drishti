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
package com.ash.drishti.plugin.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.proxy.SiteProxy;
import com.ash.drishti.testkit.FakeProxy;
import com.ash.drishti.testkit.TlsFixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Kafka connector's one HTTP part, the schema registry, through an outbound proxy (a fake one on a local port). */
class SchemaRegistryProxyTest {

    private static HttpServer http;
    private static HttpsServer https;
    private static TlsFixture pki;

    private static void answer(HttpExchange ex) throws IOException {
        byte[] body = "{\"schemaType\":\"JSON\",\"schema\":\"{}\"}".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @BeforeAll
    static void start() throws Exception {
        pki = new TlsFixture();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", SchemaRegistryProxyTest::answer);
        http.start();
        https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(pki.serverContext(false)));
        https.createContext("/", SchemaRegistryProxyTest::answer);
        https.start();
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

    private static Map<String, String> settings(String url, Map<String, String> more) {
        Map<String, String> s = new LinkedHashMap<>(more);
        s.put("schema-registry.url", url);
        return s;
    }

    private static String plain() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @Test
    void aPlainRegistryIsReachedThroughTheProxyWithItsCredentials() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            new SchemaRegistryClient(settings(plain(), Map.of("proxy.url", proxy.url(), "proxy.username", "svc", "proxy.password", "pw"))).schema(1);
            assertThat(proxy.forwarded()).isEqualTo(1);
        }
    }

    @Test
    void anHttpsRegistryIsTunnelledAndKeepsItsOwnTls() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            Map<String, String> more = new LinkedHashMap<>();
            pki.trustOnlySettings().forEach((k, v) -> more.put("schema-registry." + k, v));
            more.put("proxy.url", proxy.url());
            more.put("proxy.username", "svc");
            more.put("proxy.password", "pw");
            new SchemaRegistryClient(settings("https://localhost:" + https.getAddress().getPort(), more)).schema(1);
            assertThat(proxy.connects()).isEqualTo(1);
        }
    }

    @Test
    void wrongCredentialsAreReportedAsTheProxysRefusal() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "right")) {
            SchemaRegistryClient c = new SchemaRegistryClient(settings(plain(), Map.of("proxy.url", proxy.url(), "proxy.username", "svc", "proxy.password", "wrong")));
            assertThatThrownBy(() -> c.schema(1)).isInstanceOf(IOException.class).hasMessageContaining("refused the credentials").hasMessageContaining("407");
        }
    }

    @Test
    void noProxyAndProxyNoneGoDirect() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            new SchemaRegistryClient(settings(plain(), Map.of("proxy.url", proxy.url(), "proxy.no-proxy", "127.0.0.1"))).schema(1);
            SiteProxy.configure(Map.of("proxy.url", proxy.url()), false);
            new SchemaRegistryClient(settings(plain(), Map.of("proxy", "none"))).schema(1);
            assertThat(proxy.served()).isZero();
            new SchemaRegistryClient(settings(plain(), Map.of())).schema(1);
            assertThat(proxy.forwarded()).isEqualTo(1);
        }
    }
}
