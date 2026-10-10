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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.TlsFixture;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The S3 connector against an HTTPS endpoint on a private CA that also asks for a client certificate: a stand-in S3 in the test
 * process (it answers the bucket listing and one object, which is all the connector asks of it), no Docker.
 */
class S3TlsTest {

    private static final String LISTING = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
            + "<Name>lake</Name><Prefix>risk/</Prefix><KeyCount>1</KeyCount><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated>"
            + "<Contents><Key>risk/trade/T-1.json</Key><LastModified>2026-09-29T00:00:00.000Z</LastModified><ETag>\"1\"</ETag><Size>28</Size>"
            + "<StorageClass>STANDARD</StorageClass></Contents></ListBucketResult>";

    private static TlsFixture pki;
    private static HttpsServer server;

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
        server.createContext("/", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            boolean listing = q != null && q.contains("list-type=2");
            byte[] body = (listing ? (q.contains("delimiter") ? LISTING.replace("<Contents>", "<Skip>").replaceAll("<Skip>.*</Contents>", "") : LISTING)
                    : "{\"tradeId\":\"T-1\",\"mtm\":100}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", listing ? "application/xml" : "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        pki.delete();
    }

    private static S3SourcePlugin plugin(String host, Map<String, String> tls) {
        Map<String, String> s = new LinkedHashMap<>(tls);
        s.put("bucket", "lake");
        s.put("prefix", "risk");
        s.put("endpoint", "https://" + host + ":" + server.getAddress().getPort());
        s.put("access-key", "k");
        s.put("secret-key", "s");
        s.put("rescan-seconds", "3600");
        S3SourcePlugin p = new S3SourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    @Test
    void aPrivateCaAndAClientCertificateReachTheEndpoint() throws Exception {
        S3SourcePlugin p = plugin("localhost", pki.clientSettings());
        try {
            assertThat(p.health()).startsWith("UP").containsPattern("TLS certificate CN=drishti \\(tls.cert-file\\) expires in \\d+ days");
            assertThat(p.fetch(com.ash.drishti.api.EntityRef.of("trade", "T-1"))).isPresent();
        } finally {
            p.close();
        }
    }

    @Test
    void withoutAClientCertificateTheEndpointRefusesAndHealthSaysWhy() {
        S3SourcePlugin p = plugin("localhost", pki.trustOnlySettings());
        try {
            assertThat(p.health()).startsWith("DOWN");
        } finally {
            p.close();
        }
    }

    @Test
    void anUntrustedEndpointIsDownWithThePkixReason() {
        Map<String, String> s = new LinkedHashMap<>(pki.clientSettings());
        s.putAll(pki.wrongCaSettings());
        S3SourcePlugin p = plugin("localhost", s);
        try {
            assertThat(p.health()).startsWith("DOWN").contains("PKIX path building failed");
        } finally {
            p.close();
        }
    }

    @Test
    void theStartUpChecksNameTheSetting() {
        assertThatThrownBy(() -> S3SourcePlugin.tlsMaterial(Map.of("tls.enabled", "true"), "http://minio:9000"))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.enabled is true but endpoint is not https://");
        assertThatThrownBy(() -> S3SourcePlugin.tlsMaterial(Map.of("tls.ca-file", "/x.pem"), "http://minio:9000"))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.* is set but endpoint is not https://");
        assertThatThrownBy(() -> S3SourcePlugin.tlsMaterial(Map.of("tls.verify-hostname", "false"), "https://minio:9000"))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.verify-hostname: false is not supported by the s3 connector");
        assertThatThrownBy(() -> S3SourcePlugin.tlsMaterial(Map.of("tls.ca-file", "/etc/drishti/nope.pem"), "https://minio:9000"))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.ca-file '/etc/drishti/nope.pem'");
        assertThat(S3SourcePlugin.tlsMaterial(Map.of(), "http://minio:9000")).isNull();
        assertThat(S3SourcePlugin.tlsMaterial(Map.of(), "")).isNull();
    }
}
