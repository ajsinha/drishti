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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsSettings;
import com.ash.drishti.testkit.FakeProxy;
import com.ash.drishti.testkit.TlsFixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Iceberg's object store (S3FileIO) through an outbound proxy: a plain endpoint forwarded, an https endpoint tunnelled with CONNECT
 * (keeping its own TLS), the no-proxy list; and the Hadoop S3A and REST catalog properties the connector derives. A fake proxy and
 * a stand-in S3 answering HEAD, in the test process.
 */
class IcebergProxyTest {

    private static HttpServer http;
    private static HttpsServer https;
    private static TlsFixture pki;

    private static void head(HttpExchange ex) throws IOException {
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
        http.createContext("/", IcebergProxyTest::head);
        http.start();
        https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(pki.serverContext(false)));
        https.createContext("/", IcebergProxyTest::head);
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
        com.ash.drishti.api.proxy.SiteProxy.reset();
    }

    private static boolean exists(String endpoint, Map<String, String> tls, ProxySettings proxy) {
        var m = tls.isEmpty() ? null : TlsContexts.build(TlsSettings.parse(tls, "tls.", System::getenv));
        String id = TlsS3FileIO.register(m, proxy);
        try (TlsS3FileIO io = new TlsS3FileIO()) {
            Map<String, String> props = new LinkedHashMap<>();
            props.put("s3.endpoint", endpoint);
            props.put("s3.path-style-access", "true");
            props.put("s3.access-key-id", "k");
            props.put("s3.secret-access-key", "s");
            props.put("client.region", "us-east-1");
            props.put(TlsS3FileIO.ID, id);
            io.initialize(props);
            return io.newInputFile("s3://lake/metadata/v1.json").exists();
        } finally {
            TlsS3FileIO.unregister(id);
        }
    }

    private static ProxySettings via(FakeProxy proxy, String target, Map<String, String> more) {
        Map<String, String> s = new LinkedHashMap<>(more);
        s.put("proxy.url", proxy.url());
        return ProxySettings.resolve(s, target);
    }

    @Test
    void aPlainEndpointIsForwardedWithTheProxyCredentials() {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            String endpoint = "http://127.0.0.1:" + http.getAddress().getPort();
            assertThat(exists(endpoint, Map.of(), via(proxy, endpoint, Map.of("proxy.username", "svc", "proxy.password", "pw")))).isTrue();
            assertThat(proxy.forwarded()).isPositive();
        }
    }

    @Test
    void anHttpsEndpointIsTunnelledAndKeepsItsOwnTls() {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            String endpoint = "https://localhost:" + https.getAddress().getPort();
            assertThat(exists(endpoint, pki.trustOnlySettings(), via(proxy, endpoint, Map.of("proxy.username", "svc", "proxy.password", "pw")))).isTrue();
            assertThat(proxy.connects()).isPositive();
        }
    }

    @Test
    void theHadoopS3aPropertiesCarryTheProxyAndHonourNoProxy() {
        try (FakeProxy proxy = FakeProxy.start()) {
            Map<String, String> s = new LinkedHashMap<>();
            s.put("s3.endpoint", "http://minio.example:9000");
            s.put("proxy.url", proxy.url());
            s.put("proxy.username", "svc");
            s.put("proxy.password", "pw");
            var conf = IcebergLake.hadoop(s);
            assertThat(conf.get("fs.s3a.proxy.host")).isEqualTo("127.0.0.1");
            assertThat(conf.get("fs.s3a.proxy.port")).isEqualTo(String.valueOf(proxy.port()));
            assertThat(conf.get("fs.s3a.proxy.username")).isEqualTo("svc");
            s.put("proxy.no-proxy", ".example");
            assertThat(IcebergLake.hadoop(s).get("fs.s3a.proxy.host")).isNull();
            s.remove("proxy.no-proxy");
            s.put("proxy", "none");
            s.keySet().removeIf(k -> k.startsWith("proxy."));
            assertThat(IcebergLake.hadoop(s).get("fs.s3a.proxy.host")).isNull();
        }
    }
}
