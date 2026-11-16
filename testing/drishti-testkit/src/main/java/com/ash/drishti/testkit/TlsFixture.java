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
package com.ash.drishti.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A throw-away PKI on disk for a connector's TLS integration test: a CA, a server certificate for {@code localhost} and
 * {@code 127.0.0.1}, a client certificate, and a second unrelated CA to prove that an untrusted server is refused. The files are
 * PEM (and the server key PKCS#8); {@link #clientSettings()} is the connector's {@code tls.*} settings for mutual TLS.
 */
public final class TlsFixture {

    public final Path dir;
    public final TestPki.Identity ca;
    public final TestPki.Identity server;
    public final TestPki.Identity client;
    public final TestPki.Identity otherCa;

    public TlsFixture(long serverValidDays) {
        try {
            dir = Files.createTempDirectory("drishti-tls");
            ca = TestPki.ca("drishti-test-ca");
            otherCa = TestPki.ca("some-other-ca");
            server = TestPki.issue(ca, "localhost", "RSA", serverValidDays);
            client = TestPki.issue(ca, "drishti", "RSA", 20);       // inside the 30-day expiry warning
            ca.writeCertPem(path("ca.pem"));
            otherCa.writeCertPem(path("other-ca.pem"));
            server.writeCertPem(path("server.pem"));
            server.writeKeyPem(path("server.key"), TestPki.KeyFormat.PKCS8, null);
            client.writeCertPem(path("client.pem"));
            client.writeKeyPem(path("client.key"), TestPki.KeyFormat.PKCS8, null);
            client.writeKeystore(path("client.p12"), "PKCS12", "changeit", "drishti", "changeit");
            TestPki.writeTruststore(path("ca.p12"), "PKCS12", "changeit", ca.cert());
            server.writeKeystore(path("server.p12"), "PKCS12", "changeit", "server", "changeit");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public TlsFixture() {
        this(30);
    }

    public Path path(String name) {
        return dir.resolve(name);
    }

    /** Trust the CA and present the client certificate. */
    public Map<String, String> clientSettings() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tls.ca-file", path("ca.pem").toString());
        m.put("tls.cert-file", path("client.pem").toString());
        m.put("tls.key-file", path("client.key").toString());
        return m;
    }

    /** Trust the CA only (a server that does not ask for a client certificate). */
    public Map<String, String> trustOnlySettings() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tls.ca-file", path("ca.pem").toString());
        return m;
    }

    /** Trust a CA that did not sign the server. */
    public Map<String, String> wrongCaSettings() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tls.ca-file", path("other-ca.pem").toString());
        return m;
    }

    /** A server-side context presenting the server certificate; with {@code needClientAuth} it demands a certificate signed by the CA. */
    public javax.net.ssl.SSLContext serverContext(boolean needClientAuth) {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(path("server.p12"))) {
                ks.load(in, "changeit".toCharArray());
            }
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "changeit".toCharArray());
            java.security.KeyStore ts = java.security.KeyStore.getInstance("PKCS12");
            ts.load(null, null);
            ts.setCertificateEntry("ca", ca.cert());
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), needClientAuth ? tmf.getTrustManagers() : null, null);
            return ctx;
        } catch (java.security.GeneralSecurityException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Delete the files. */
    public void delete() {
        try (var s = Files.list(dir)) {
            s.forEach(p -> p.toFile().delete());
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            // best effort: a temp directory
        }
    }
}
