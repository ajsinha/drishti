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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
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
 * Iceberg's object store over TLS: S3FileIO with the shared {@code tls.*} settings against an HTTPS endpoint that also asks for a
 * client certificate (a stand-in S3 in the test process, answering HEAD), and the start-up errors. No Docker.
 */
class IcebergTlsTest {

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

    /** True when the object store answers a HEAD for a key, through a TlsS3FileIO configured as the connector would. */
    private static boolean exists(Map<String, String> tls) {
        TlsMaterial m = TlsContexts.build(TlsSettings.parse(tls, "tls.", System::getenv));
        String id = TlsS3FileIO.register(m);
        try (TlsS3FileIO io = new TlsS3FileIO()) {
            Map<String, String> props = new LinkedHashMap<>();
            props.put("s3.endpoint", "https://localhost:" + server.getAddress().getPort());
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

    @Test
    void aPrivateCaAndAClientCertificateReachTheObjectStore() {
        assertThat(exists(pki.clientSettings())).isTrue();
    }

    @Test
    void withoutAClientCertificateTheObjectStoreRefuses() {
        assertThatThrownBy(() -> exists(pki.trustOnlySettings())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anUntrustedObjectStoreIsRefusedWithThePkixReason() {
        Map<String, String> s = new LinkedHashMap<>(pki.clientSettings());
        s.putAll(pki.wrongCaSettings());
        assertThatThrownBy(() -> exists(s)).hasStackTraceContaining("PKIX path building failed");
    }

    @Test
    void hostNameCheckingCannotBeTurnedOffForTheAwsClient() {
        Map<String, String> s = new LinkedHashMap<>(pki.clientSettings());
        s.put("tls.verify-hostname", "false");
        assertThatThrownBy(() -> exists(s)).isInstanceOf(TlsException.class).hasMessageContaining("tls.verify-hostname: false is not supported");
    }

    @Test
    void startUpChecksNameTheSetting() {
        assertThatThrownBy(() -> IcebergLake.of(Map.of("catalog", "hadoop", "root", "/tmp/x", "tls.ca-file", "/x.pem")))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.* applies to a REST catalog's object store");
        assertThatThrownBy(() -> IcebergLake.of(Map.of("catalog", "rest", "uri", "http://catalog:8181", "tls.ca-file", "/x.pem", "s3.endpoint", "http://minio:9000")))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.* is set but s3.endpoint is not https://");
        assertThatThrownBy(() -> IcebergLake.of(Map.of("catalog", "rest", "uri", "http://catalog:8181", "tls.ca-file", "/etc/drishti/nope.pem",
                "s3.endpoint", "https://minio:9000")))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.ca-file '/etc/drishti/nope.pem'");
    }
}
