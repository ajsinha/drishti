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
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.testkit.TlsFixture;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The REST connector over HTTPS against a JDK HTTPS server in the same process (no Docker): a private CA, mutual TLS, a wrong
 * CA, a host name the certificate does not carry, and the start-up errors.
 */
class RestTlsTest {

    private static TlsFixture pki;
    private static HttpsServer server;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        pki = new TlsFixture();
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        javax.net.ssl.SSLContext ctx = pki.serverContext(true);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx) {
            @Override
            public void configure(HttpsParameters params) {
                javax.net.ssl.SSLParameters sp = ctx.getDefaultSSLParameters();
                sp.setNeedClientAuth(true);
                params.setSSLParameters(sp);
            }
        });
        server.createContext("/api/", ex -> {
            byte[] body = "{\"tradeId\":\"T1\",\"mtm\":5}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        pki.delete();
    }

    private static RestSourcePlugin start(String host, Map<String, String> tls) {
        Map<String, String> s = new LinkedHashMap<>(tls);
        s.put("base-url", "https://" + host + ":" + port + "/api");
        s.put("timeout-ms", "3000");
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

    @Test
    void aPrivateCaAndAClientCertificateAreAccepted() throws Exception {
        RestSourcePlugin p = start("localhost", pki.clientSettings());
        assertThat(p.fetch(EntityRef.of("trade", "T1")).orElseThrow().data().get("mtm").asDouble()).isEqualTo(5);
        assertThat(p.health()).startsWith("UP").containsPattern("TLS certificate CN=drishti \\(tls.cert-file\\) expires in \\d+ days");
    }

    @Test
    void aKeystoreAndTruststoreInPkcs12WorkToo() throws Exception {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("tls.truststore", pki.path("ca.p12").toString());
        s.put("tls.truststore-password", "changeit");
        s.put("tls.keystore", pki.path("client.p12").toString());
        s.put("tls.keystore-password", "changeit");
        assertThat(start("localhost", s).fetch(EntityRef.of("trade", "T1"))).isPresent();
    }

    @Test
    void withoutAClientCertificateTheServerRefuses() throws Exception {
        RestSourcePlugin p = start("localhost", pki.trustOnlySettings());
        assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "T1"))).isInstanceOf(IOException.class);
    }

    @Test
    void anUntrustedServerIsRefusedNamingTheCertificatePath() {
        Map<String, String> s = new LinkedHashMap<>(pki.clientSettings());
        s.putAll(pki.wrongCaSettings());
        RestSourcePlugin p = start("localhost", s);
        assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "T1"))).hasStackTraceContaining("PKIX path building failed");
    }

    @Test
    void aWrongHostNameFailsUnlessVerificationIsOff() throws Exception {
        // a certificate for another name: issue one for "other.example" and serve it on a second port
        TlsFixture other = new TlsFixture();
        HttpsServer s2 = HttpsServer.create(new InetSocketAddress(0), 0);
        javax.net.ssl.SSLContext ctx = other.serverContext(false);
        s2.setHttpsConfigurator(new HttpsConfigurator(ctx));
        s2.createContext("/api/", ex -> {
            byte[] body = "{\"mtm\":1}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        s2.start();
        try {
            // the certificate carries 127.0.0.1; 127.0.0.2 is the same machine under an address the certificate does not carry
            Map<String, String> trust = other.trustOnlySettings();
            int p2 = s2.getAddress().getPort();
            Map<String, String> s = new LinkedHashMap<>(trust);
            s.put("base-url", "https://127.0.0.1:" + p2 + "/api");
            assertThat(fetch(s)).isPresent();
            s.put("base-url", "https://127.0.0.2:" + p2 + "/api");
            assertThatThrownBy(() -> fetch(s)).isInstanceOf(IOException.class);
            s.put("tls.verify-hostname", "false");
            assertThat(fetch(s)).isPresent();
        } finally {
            s2.stop(0);
            other.delete();
        }
    }

    private static java.util.Optional<com.ash.drishti.api.EntityDocument> fetch(Map<String, String> settings) throws Exception {
        JsonCodec codec = new JsonCodec();
        RestSourcePlugin p = new RestSourcePlugin();
        p.start(new SourceContext() {
            public Map<String, String> settings() {
                return settings;
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return Executors.newSingleThreadScheduledExecutor();
            }
        });
        return p.fetch(EntityRef.of("trade", "T1"));
    }

    @Test
    void tlsSettingsAgainstAPlainUrlAreRefused() {
        Map<String, String> plain = new LinkedHashMap<>();
        plain.put("base-url", "http://h:80/api");
        plain.put("tls.enabled", "true");
        assertThatThrownBy(() -> fetch(plain)).isInstanceOf(TlsException.class).hasMessageContaining("tls.enabled is true but base-url is not https://");
        Map<String, String> ca = new LinkedHashMap<>();
        ca.put("base-url", "http://h:80/api");
        ca.put("tls.ca-file", "/x.pem");
        assertThatThrownBy(() -> fetch(ca)).isInstanceOf(TlsException.class).hasMessageContaining("tls.* is set but base-url is not https://");
    }

    @Test
    void aMissingCaFileFailsTheStartNamingTheFile() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("base-url", "https://h/api");
        s.put("tls.ca-file", "/etc/drishti/nope.pem");
        assertThatThrownBy(() -> fetch(s)).isInstanceOf(TlsException.class).hasMessageContaining("tls.ca-file '/etc/drishti/nope.pem'");
    }
}
