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
package com.ash.drishti.deltalake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.testkit.TlsFixture;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Delta engine's S3 reader against an HTTPS endpoint on a private CA that asks for a client certificate (a stand-in S3 in the
 * test process answering HEAD): trust, mutual TLS, the host name check and the start-up errors. No Docker.
 */
class S3StorageTlsTest {

    private static TlsFixture pki;
    private static HttpsServer server;

    @BeforeAll
    static void start() throws Exception {
        pki = new TlsFixture();
        server = HttpsServer.create(new InetSocketAddress(0), 0);
        javax.net.ssl.SSLContext ctx = pki.serverContext(true);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx) {
            @Override
            public void configure(HttpsParameters params) {
                javax.net.ssl.SSLParameters sp = ctx.getDefaultSSLParameters();
                sp.setNeedClientAuth(true);
                params.setSSLParameters(sp);
            }
        });
        server.createContext("/", ex -> {
            ex.getResponseHeaders().add("Content-Length", "11");
            ex.getResponseHeaders().add("Last-Modified", "Tue, 29 Sep 2026 00:00:00 GMT");
            ex.getResponseHeaders().add("ETag", "\"1\"");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        pki.delete();
    }

    private static Map<String, String> settings(String host, Map<String, String> tls) {
        Map<String, String> s = new LinkedHashMap<>(tls);
        s.put("s3.endpoint", "https://" + host + ":" + server.getAddress().getPort());
        s.put("s3.access-key", "k");
        s.put("s3.secret-key", "s");
        s.put("s3.region", "us-east-1");
        return s;
    }

    private static long length(Map<String, String> settings) throws Exception {
        try (S3Storage s = new S3Storage(S3Settings.from(settings))) {
            return s.status("s3://lake/_delta_log/0.json").getSize();
        }
    }

    @Test
    void aPrivateCaAndAClientCertificateReachTheEndpoint() throws Exception {
        assertThat(length(settings("localhost", pki.clientSettings()))).isEqualTo(11);
        assertThat(S3Settings.from(settings("localhost", pki.clientSettings())).tls().expiryNote(java.time.Instant.now()))
                .contains("TLS certificate CN=drishti (tls.cert-file) expires in");
    }

    @Test
    void withoutAClientCertificateTheEndpointRefuses() {
        assertThatThrownBy(() -> length(settings("localhost", pki.trustOnlySettings()))).isInstanceOf(Exception.class);
    }

    @Test
    void anUntrustedEndpointIsRefusedWithThePkixReason() {
        Map<String, String> s = new LinkedHashMap<>(pki.clientSettings());
        s.putAll(pki.wrongCaSettings());
        assertThatThrownBy(() -> length(settings("localhost", s))).hasStackTraceContaining("PKIX path building failed");
    }

    @Test
    void theHostNameIsCheckedUnlessTurnedOff() throws Exception {
        Map<String, String> tls = new LinkedHashMap<>(pki.clientSettings());
        assertThatThrownBy(() -> length(settings("127.0.0.2", tls))).isInstanceOf(Exception.class);
        tls.put("tls.verify-hostname", "false");
        assertThat(length(settings("127.0.0.2", tls))).isEqualTo(11);
    }

    @Test
    void startUpChecksNameTheSetting() {
        assertThatThrownBy(() -> S3Settings.from(Map.of("tls.ca-file", "/x.pem", "s3.endpoint", "http://minio:9000")))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.* is set but s3.endpoint is not https://");
        assertThatThrownBy(() -> S3Settings.from(Map.of("tls.enabled", "true")))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.enabled is true but s3.endpoint is not https://");
        assertThatThrownBy(() -> S3Settings.from(Map.of("tls.ca-file", "/etc/drishti/nope.pem", "s3.endpoint", "https://minio:9000")))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.ca-file '/etc/drishti/nope.pem'");
        assertThat(S3Settings.from(Map.of("s3.endpoint", "http://minio:9000")).tls()).isNull();
    }
}
