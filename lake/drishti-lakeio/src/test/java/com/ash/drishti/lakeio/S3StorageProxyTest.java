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
package com.ash.drishti.lakeio;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.proxy.SiteProxy;
import com.ash.drishti.testkit.FakeProxy;
import com.ash.drishti.testkit.TlsFixture;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Delta engine's S3 reader through an outbound proxy (a fake one on a local port): plain forwarding, CONNECT for an https
 * endpoint (with its own TLS), the no-proxy list, the site default and {@code proxy: none}. A stand-in S3 answers HEAD.
 */
class S3StorageProxyTest {

    private static HttpServer http;
    private static HttpsServer https;
    private static TlsFixture pki;

    private static void head(com.sun.net.httpserver.HttpExchange ex) throws java.io.IOException {
        ex.getResponseHeaders().add("Content-Length", "11");
        ex.getResponseHeaders().add("Last-Modified", "Tue, 29 Sep 2026 00:00:00 GMT");
        ex.getResponseHeaders().add("ETag", "\"1\"");
        ex.sendResponseHeaders(200, -1);
        ex.close();
    }

    @BeforeAll
    static void start() throws Exception {
        pki = new TlsFixture();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", S3StorageProxyTest::head);
        http.start();
        https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(pki.serverContext(false)));
        https.createContext("/", S3StorageProxyTest::head);
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

    private static Map<String, String> settings(String endpoint, Map<String, String> more) {
        Map<String, String> s = new LinkedHashMap<>(more);
        s.put("s3.endpoint", endpoint);
        s.put("s3.access-key", "k");
        s.put("s3.secret-key", "s");
        s.put("s3.region", "us-east-1");
        return s;
    }

    private static String plain() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    private static String secure() {
        return "https://localhost:" + https.getAddress().getPort();
    }

    private static long length(Map<String, String> settings) throws Exception {
        try (S3Storage s = new S3Storage(S3Settings.from(settings))) {
            return s.status("s3://lake/_delta_log/0.json").size();
        }
    }

    @Test
    void aPlainEndpointIsReachedThroughTheProxyWithItsCredentials() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            assertThat(length(settings(plain(), Map.of("proxy.url", proxy.url(), "proxy.username", "svc", "proxy.password", "pw")))).isEqualTo(11);
            assertThat(proxy.forwarded()).isPositive();
            assertThat(S3Settings.from(settings(plain(), Map.of("proxy.url", proxy.url()))).proxy().note()).contains("(connector)");
        }
    }

    @Test
    void anHttpsEndpointIsTunnelledAndKeepsItsOwnTls() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            Map<String, String> more = new LinkedHashMap<>(pki.trustOnlySettings());
            more.put("proxy.url", proxy.url());
            more.put("proxy.username", "svc");
            more.put("proxy.password", "pw");
            assertThat(length(settings(secure(), more))).isEqualTo(11);
            assertThat(proxy.connects()).isPositive();
            Map<String, String> wrong = new LinkedHashMap<>(pki.wrongCaSettings());
            wrong.put("proxy.url", proxy.url());
            wrong.put("proxy.username", "svc");
            wrong.put("proxy.password", "pw");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> length(settings(secure(), wrong))).hasStackTraceContaining("PKIX path building failed");
        }
    }

    @Test
    void aNoProxyHostBypassesIt() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            assertThat(length(settings(plain(), Map.of("proxy.url", proxy.url(), "proxy.no-proxy", "127.0.0.0/8")))).isEqualTo(11);
            assertThat(proxy.served()).isZero();
        }
    }

    @Test
    void proxyNoneOverridesTheSiteDefault() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            SiteProxy.configure(Map.of("proxy.url", proxy.url()), false);
            assertThat(length(settings(plain(), Map.of()))).isEqualTo(11);
            int viaSite = proxy.forwarded();
            assertThat(viaSite).isPositive();
            assertThat(length(settings(plain(), Map.of("proxy", "none")))).isEqualTo(11);
            assertThat(proxy.forwarded()).isEqualTo(viaSite);
        }
    }
}
