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
package com.ash.drishti.plugin.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.proxy.SiteProxy;
import com.ash.drishti.testkit.DatedSourceContract;
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

/** The S3 connector through an outbound proxy (a fake one on a local port) to a stand-in S3: via, CONNECT, no-proxy, site default, {@code proxy: none}. */
class S3ProxyTest {

    private static final String LISTING = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
            + "<Name>lake</Name><Prefix>risk/</Prefix><KeyCount>1</KeyCount><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated>"
            + "<Contents><Key>risk/trade/T-1.json</Key><LastModified>2026-09-29T00:00:00.000Z</LastModified><ETag>\"1\"</ETag><Size>28</Size>"
            + "<StorageClass>STANDARD</StorageClass></Contents></ListBucketResult>";

    private static HttpServer http;
    private static HttpsServer https;
    private static TlsFixture pki;

    private static void answer(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getRawQuery();
        boolean listing = q != null && q.contains("list-type=2");
        byte[] body = (listing ? LISTING : "{\"tradeId\":\"T-1\"}").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", listing ? "application/xml" : "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @BeforeAll
    static void start() throws Exception {
        pki = new TlsFixture();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", S3ProxyTest::answer);
        http.start();
        https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(pki.serverContext(false)));
        https.createContext("/", S3ProxyTest::answer);
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

    private static S3SourcePlugin plugin(String endpoint, Map<String, String> more) {
        Map<String, String> s = new LinkedHashMap<>(more);
        s.put("bucket", "lake");
        s.put("prefix", "risk");
        s.put("endpoint", endpoint);
        s.put("access-key", "k");
        s.put("secret-key", "s");
        s.put("rescan-seconds", "3600");
        S3SourcePlugin p = new S3SourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private static String plain() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @Test
    void aPlainEndpointIsReachedThroughTheProxyWithItsCredentials() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            S3SourcePlugin p = plugin(plain(), Map.of("proxy.url", proxy.url(), "proxy.username", "svc", "proxy.password", "pw"));
            try {
                assertThat(proxy.forwarded()).isPositive();
                assertThat(p.health()).startsWith("UP via proxy").doesNotContain("pw");
            } finally {
                p.close();
            }
        }
    }

    @Test
    void anHttpsEndpointIsTunnelledWithConnectAndKeepsItsOwnTls() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            Map<String, String> more = new LinkedHashMap<>(pki.trustOnlySettings());
            more.put("proxy.url", proxy.url());
            more.put("proxy.username", "svc");
            more.put("proxy.password", "pw");
            S3SourcePlugin p = plugin("https://localhost:" + https.getAddress().getPort(), more);
            try {
                assertThat(proxy.connects()).as(p.health() + " rejected=" + proxy.rejected() + " reqs=" + proxy.requests() + " auth=" + proxy.authorizations()).isPositive();
                assertThat(p.health()).startsWith("UP").contains("via proxy");
            } finally {
                p.close();
            }
        }
    }

    @Test
    void aNoProxyHostBypassesIt() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            S3SourcePlugin p = plugin(plain(), Map.of("proxy.url", proxy.url(), "proxy.no-proxy", "127.0.0.1"));
            try {
                assertThat(proxy.served()).isZero();
                assertThat(p.health()).startsWith("UP").contains("not used for 127.0.0.1");
            } finally {
                p.close();
            }
        }
    }

    @Test
    void proxyNoneOverridesTheSiteDefault() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            SiteProxy.configure(Map.of("proxy.url", proxy.url()), false);
            S3SourcePlugin inherits = plugin(plain(), Map.of());
            int viaSite = proxy.forwarded();
            assertThat(viaSite).isPositive();
            assertThat(inherits.health()).contains("(site default)");
            inherits.close();
            S3SourcePlugin direct = plugin(plain(), Map.of("proxy", "none"));
            assertThat(proxy.forwarded()).isEqualTo(viaSite);
            assertThat(direct.health()).isEqualTo("UP");
            direct.close();
        }
    }
}
