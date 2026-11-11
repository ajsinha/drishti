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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.tls.TlsContexts;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.api.tls.TlsSettings;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every trust and identity form of the shared TLS module, every failure message, and a real handshake. */
class TlsContextsTest {

    static TestPki.Identity ca;
    static TestPki.Identity rsa;
    static TestPki.Identity ec;
    @TempDir
    Path dir;

    @BeforeAll
    static void pki() {
        ca = TestPki.ca("test-ca");
        rsa = TestPki.issue(ca, "rsa-client", "RSA", 365);
        ec = TestPki.issue(ca, "ec-client", "EC", 365);
    }

    static Function<String, String> noEnv() {
        return k -> null;
    }

    TlsSettings settings(Map<String, String> m) {
        Map<String, String> all = new HashMap<>(m);
        all.putIfAbsent("tls.enabled", "true");
        return TlsSettings.parse(all, "tls.", noEnv());
    }

    TlsMaterial build(Map<String, String> m) {
        return TlsContexts.build(settings(m), noEnv(), Clock.systemUTC());
    }

    Path ca() throws IOException {
        Path f = dir.resolve("ca.pem");
        ca.writeCertPem(f);
        return f;
    }

    // ---------------------------------------------------------------- identity forms

    @ParameterizedTest
    @EnumSource(TestPki.KeyFormat.class)
    void aPemKeyInEveryFormatLoadsForRsa(TestPki.KeyFormat format) throws Exception {
        if (format == TestPki.KeyFormat.SEC1_EC) {
            return;
        }
        loadsWith(rsa, format);
    }

    @ParameterizedTest
    @EnumSource(TestPki.KeyFormat.class)
    void aPemKeyInEveryFormatLoadsForEc(TestPki.KeyFormat format) throws Exception {
        if (format == TestPki.KeyFormat.PKCS1_RSA) {
            return;
        }
        loadsWith(ec, format);
    }

    private void loadsWith(TestPki.Identity id, TestPki.KeyFormat format) throws Exception {
        Path cert = dir.resolve("c-" + format + ".pem");
        Path key = dir.resolve("k-" + format + ".pem");
        id.writeCertPem(cert);
        id.writeKeyPem(key, format, "s3cret");
        Map<String, String> m = new HashMap<>(Map.of("tls.cert-file", cert.toString(), "tls.key-file", key.toString(), "tls.ca-file", ca().toString()));
        if (format == TestPki.KeyFormat.ENCRYPTED_PKCS8) {
            m.put("tls.key-password", "s3cret");
        }
        TlsMaterial t = build(m);
        assertThat(t.keyManagers()).isNotNull();
        assertThat(t.clientKey().getAlgorithm()).isEqualTo(id.key().getAlgorithm());
        assertThat(t.clientChain()).hasSize(2);
        assertThat(t.trusted()).hasSize(1);
        assertThat(t.usesJvmDefaultTrust()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TestPki.KeyFormat.class, names = {"PKCS8", "ENCRYPTED_PKCS8"})
    void inlinePemTextWorksInPlaceOfAPath(TestPki.KeyFormat format) throws Exception {
        String cert = String.join("", rsa.chain().stream().map(c -> {
            try {
                return TestPki.pem("CERTIFICATE", c.getEncoded());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList());
        Map<String, String> m = new HashMap<>(Map.of("tls.cert-file", cert, "tls.key-file", TestPki.keyPem(rsa.key(), format, "pw"),
                "tls.ca-file", Files.readString(ca())));
        if (format == TestPki.KeyFormat.ENCRYPTED_PKCS8) {
            m.put("tls.key-password", "pw");
        }
        assertThat(build(m).clientChain()).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = TestPki.KeyFormat.class, names = {"PKCS8"})
    void keystoresPkcs12AndJksLoadWithAliasAndKeyPassword(TestPki.KeyFormat ignored) throws Exception {
        for (String type : new String[] {"PKCS12", "JKS"}) {
            Path ks = dir.resolve("client." + type);
            rsa.writeKeystore(ks, type, "storepw", "drishti", "storepw");
            // type given, type detected, alias given
            for (Map<String, String> m : new Map[] {
                    Map.of("tls.keystore", ks.toString(), "tls.keystore-password", "storepw", "tls.keystore-type", type),
                    Map.of("tls.keystore", ks.toString(), "tls.keystore-password", "storepw"),
                    Map.of("tls.keystore", ks.toString(), "tls.keystore-password", "storepw", "tls.key-alias", "drishti")}) {
                TlsMaterial t = build(m);
                assertThat(t.keyManagers()).isNotNull();
                assertThat(t.clientChain()).hasSize(2);
                assertThat(t.usesJvmDefaultTrust()).isTrue();     // nothing else given: the JVM's authorities
            }
        }
    }

    @Test
    void aJksKeyWithItsOwnKeyPasswordNeedsIt() throws Exception {
        Path ks = dir.resolve("k.jks");
        rsa.writeKeystore(ks, "JKS", "storepw", "k", "keypw");
        assertThat(build(Map.of("tls.keystore", ks.toString(), "tls.keystore-password", "storepw", "tls.key-password", "keypw")).keyManagers()).isNotNull();
        assertThatThrownBy(() -> build(Map.of("tls.keystore", ks.toString(), "tls.keystore-password", "storepw")))
                .isInstanceOf(TlsException.class).hasMessageContaining("wrong key password").hasMessageContaining("tls.key-password");
    }

    // ---------------------------------------------------------------- trust forms

    @Test
    void aCaBundleOfManyCertificatesTrustsAllOfThem() throws Exception {
        TestPki.Identity other = TestPki.ca("other-ca");
        Path bundle = dir.resolve("bundle.pem");
        Files.writeString(bundle, Files.readString(ca()) + "\n# comment\n" + pemOf(other));
        TlsMaterial t = build(Map.of("tls.ca-file", bundle.toString()));
        assertThat(t.trusted()).hasSize(2);
        assertThat(t.keyManagers()).isNull();
    }

    private static String pemOf(TestPki.Identity id) throws Exception {
        return TestPki.pem("CERTIFICATE", id.cert().getEncoded());
    }

    @Test
    void truststoresPkcs12AndJksAndDetectedType() throws Exception {
        for (String type : new String[] {"PKCS12", "JKS"}) {
            Path ts = dir.resolve("trust." + type);
            TestPki.writeTruststore(ts, type, "tspw", ca.cert());
            assertThat(build(Map.of("tls.truststore", ts.toString(), "tls.truststore-password", "tspw", "tls.truststore-type", type)).trusted()).hasSize(1);
            assertThat(build(Map.of("tls.truststore", ts.toString(), "tls.truststore-password", "tspw")).trusted()).hasSize(1);
        }
    }

    @Test
    void pemAndTruststoreAreMergedAndTheJvmDefaultCanJoin() throws Exception {
        TestPki.Identity other = TestPki.ca("other-ca");
        Path ts = dir.resolve("t.p12");
        TestPki.writeTruststore(ts, "PKCS12", "pw", other.cert());
        TlsMaterial both = build(Map.of("tls.ca-file", ca().toString(), "tls.truststore", ts.toString(), "tls.truststore-password", "pw"));
        assertThat(both.trusted()).hasSize(2);
        assertThat(both.usesJvmDefaultTrust()).isFalse();
        TlsMaterial plus = build(Map.of("tls.ca-file", ca().toString(), "tls.trust-jvm-default", "true"));
        assertThat(plus.trusted()).hasSize(1);
        assertThat(plus.usesJvmDefaultTrust()).isTrue();
        assertThat(((javax.net.ssl.X509TrustManager) plus.trustManagers()[0]).getAcceptedIssuers().length).isGreaterThan(10);
    }

    @Test
    void theJvmDefaultIsUsedWhenNothingIsGiven() {
        TlsMaterial t = build(Map.of());
        assertThat(t.usesJvmDefaultTrust()).isTrue();
        assertThat(t.sslParameters().getProtocols()).containsExactly("TLSv1.3", "TLSv1.2");
        assertThat(t.sslParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
    }

    // ---------------------------------------------------------------- protocols, ciphers, hostname, insecure

    @Test
    void protocolsAndCipherSuitesAreApplied() {
        TlsMaterial t = build(Map.of("tls.protocols", "TLSv1.3", "tls.cipher-suites", "TLS_AES_256_GCM_SHA384"));
        assertThat(t.sslParameters().getProtocols()).containsExactly("TLSv1.3");
        assertThat(t.sslParameters().getCipherSuites()).containsExactly("TLS_AES_256_GCM_SHA384");
        assertThatThrownBy(() -> build(Map.of("tls.protocols", "SSLv9"))).isInstanceOf(TlsException.class).hasMessageContaining("SSLv9");
        assertThatThrownBy(() -> build(Map.of("tls.cipher-suites", "NOPE"))).isInstanceOf(TlsException.class).hasMessageContaining("NOPE");
    }

    @Test
    void switchingHostnameVerificationOffWarns() {
        TlsMaterial t = build(Map.of("tls.verify-hostname", "false"));
        assertThat(t.sslParameters().getEndpointIdentificationAlgorithm()).isNull();
        assertThat(t.warnings()).anyMatch(w -> w.contains("verify-hostname is false"));
    }

    @Test
    void trustAllIsRefusedUnlessTheEnvironmentAllowsIt() {
        TlsSettings s = settings(Map.of("tls.insecure-trust-all", "true"));
        assertThatThrownBy(() -> TlsContexts.build(s, noEnv(), Clock.systemUTC())).isInstanceOf(TlsException.class)
                .hasMessageContaining("insecure-trust-all is refused").hasMessageContaining("DRISHTI_ALLOW_INSECURE_TLS=true");
        TlsMaterial t = TlsContexts.build(s, k -> "DRISHTI_ALLOW_INSECURE_TLS".equals(k) ? "true" : null, Clock.systemUTC());
        assertThat(t.warnings()).anyMatch(w -> w.contains("insecure-trust-all is ON"));
    }

    // ---------------------------------------------------------------- passwords

    @Test
    void passwordsComeFromEnvironmentPlaceholdersOrFiles() throws Exception {
        Path ks = dir.resolve("c.p12");
        rsa.writeKeystore(ks, "PKCS12", "envpw", "k", "envpw");
        Path pwFile = dir.resolve("pw");
        Files.writeString(pwFile, "envpw\nsecond line ignored\n");
        Function<String, String> env = k -> "KS_PW".equals(k) ? "envpw" : null;
        TlsSettings viaEnv = TlsSettings.parse(Map.of("tls.enabled", "true", "tls.keystore", ks.toString(), "tls.keystore-password", "${KS_PW}"), "tls.", env);
        assertThat(viaEnv.keystorePassword()).isEqualTo("envpw");
        assertThat(TlsContexts.build(viaEnv, env, Clock.systemUTC()).keyManagers()).isNotNull();
        TlsSettings viaFile = settings(Map.of("tls.keystore", ks.toString(), "tls.keystore-password-file", pwFile.toString()));
        assertThat(viaFile.keystorePassword()).isEqualTo("envpw");
        assertThat(viaFile.toString()).doesNotContain("envpw");
        assertThatThrownBy(() -> settings(Map.of("tls.keystore-password-file", dir.resolve("nope").toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("nope").hasMessageContaining("file not found");
        assertThatThrownBy(() -> TlsSettings.parse(Map.of("tls.keystore-password", "${UNSET_VAR}"), "tls.", noEnv()))
                .isInstanceOf(TlsException.class).hasMessageContaining("UNSET_VAR is not set");
    }

    // ---------------------------------------------------------------- failures

    @Test
    void failureMessagesNameTheFileAndTheReason() throws Exception {
        Path cert = dir.resolve("c.pem");
        Path key = dir.resolve("k.pem");
        rsa.writeCertPem(cert);
        rsa.writeKeyPem(key, TestPki.KeyFormat.PKCS8, null);
        Path ks = dir.resolve("c.p12");
        rsa.writeKeystore(ks, "PKCS12", "right", "k", "right");
        Path garbage = dir.resolve("garbage");
        Files.writeString(garbage, "not a key store");

        assertThatThrownBy(() -> build(Map.of("tls.ca-file", dir.resolve("missing.pem").toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("tls.ca-file").hasMessageContaining("missing.pem").hasMessageContaining("file not found");
        assertThatThrownBy(() -> build(Map.of("tls.ca-file", garbage.toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("no certificate found");
        assertThatThrownBy(() -> build(Map.of("tls.keystore", ks.toString(), "tls.keystore-password", "wrong")))
                .isInstanceOf(TlsException.class).hasMessageContaining("c.p12").hasMessageContaining("wrong password");
        assertThatThrownBy(() -> build(Map.of("tls.keystore", garbage.toString(), "tls.keystore-password", "x")))
                .isInstanceOf(TlsException.class).hasMessageContaining("garbage");
        assertThatThrownBy(() -> build(Map.of("tls.keystore", ks.toString(), "tls.keystore-password", "right", "tls.key-alias", "zzz")))
                .isInstanceOf(TlsException.class).hasMessageContaining("key-alias 'zzz'").hasMessageContaining("[k]");
        assertThatThrownBy(() -> build(Map.of("tls.cert-file", cert.toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("cert-file is set but tls.key-file is not");
        assertThatThrownBy(() -> build(Map.of("tls.key-file", key.toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("key-file is set but tls.cert-file is not");
        assertThatThrownBy(() -> build(Map.of("tls.cert-file", cert.toString(), "tls.key-file", key.toString(), "tls.keystore", ks.toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("give the client identity one way");
        assertThatThrownBy(() -> build(Map.of("tls.cert-file", cert.toString(), "tls.key-file", garbage.toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("no private key found");
    }

    @Test
    void anEncryptedKeyNeedsItsPasswordAndALegacyOneIsExplained() throws Exception {
        Path cert = dir.resolve("c.pem");
        Path key = dir.resolve("k.pem");
        rsa.writeCertPem(cert);
        rsa.writeKeyPem(key, TestPki.KeyFormat.ENCRYPTED_PKCS8, "right");
        assertThatThrownBy(() -> build(Map.of("tls.cert-file", cert.toString(), "tls.key-file", key.toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("the key is encrypted").hasMessageContaining("key-password");
        assertThatThrownBy(() -> build(Map.of("tls.cert-file", cert.toString(), "tls.key-file", key.toString(), "tls.key-password", "wrong")))
                .isInstanceOf(TlsException.class).hasMessageContaining("wrong key password");
        Path legacy = dir.resolve("legacy.pem");
        Files.writeString(legacy, "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,AABBCCDD\n\nAAAA\n-----END RSA PRIVATE KEY-----\n");
        assertThatThrownBy(() -> build(Map.of("tls.cert-file", cert.toString(), "tls.key-file", legacy.toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("openssl pkcs8 -topk8");
    }

    @Test
    void aKeyThatBelongsToAnotherCertificateIsRefused() throws Exception {
        Path cert = dir.resolve("c.pem");
        Path key = dir.resolve("k.pem");
        rsa.writeCertPem(cert);
        TestPki.issue(ca, "someone-else", "RSA", 30).writeKeyPem(key, TestPki.KeyFormat.PKCS8, null);
        assertThatThrownBy(() -> build(Map.of("tls.cert-file", cert.toString(), "tls.key-file", key.toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("does not match the certificate").hasMessageContaining("k.pem").hasMessageContaining("c.pem");
        Path ecKey = dir.resolve("ec.pem");
        ec.writeKeyPem(ecKey, TestPki.KeyFormat.SEC1_EC, null);
        assertThatThrownBy(() -> build(Map.of("tls.cert-file", cert.toString(), "tls.key-file", ecKey.toString())))
                .isInstanceOf(TlsException.class).hasMessageContaining("does not match");
    }

    // ---------------------------------------------------------------- expiry

    @Test
    void anExpiredCertificateWarnsWithTheDateAndASoonExpiringOneWithTheDays() throws Exception {
        Instant now = Instant.now();
        TestPki.Identity expired = TestPki.issue(ca, "old", "RSA", now.minusSeconds(400L * 86400), now.minusSeconds(10L * 86400));
        Path cert = dir.resolve("old.pem");
        Path key = dir.resolve("old.key");
        expired.writeCertPem(cert);
        expired.writeKeyPem(key, TestPki.KeyFormat.PKCS8, null);
        TlsMaterial t = build(Map.of("tls.cert-file", cert.toString(), "tls.key-file", key.toString()));
        String date = now.minusSeconds(10L * 86400).toString().substring(0, 10);
        assertThat(t.warnings()).anyMatch(w -> w.contains("EXPIRED on " + date) && w.contains("tls.cert-file"));
        assertThat(t.expiryNote(now)).contains("expired on " + date);

        TestPki.Identity soon = TestPki.issue(ca, "soon", "EC", 12);
        soon.writeCertPem(cert);
        soon.writeKeyPem(key, TestPki.KeyFormat.PKCS8, null);
        TlsMaterial s = build(Map.of("tls.cert-file", cert.toString(), "tls.key-file", key.toString()));
        assertThat(s.warnings()).anyMatch(w -> w.contains("expires in 11 days") || w.contains("expires in 12 days"));
        assertThat(s.expiryNote(now)).contains("expires in");
        assertThat(s.soonestExpiry()).isPresent();

        writeFine();
    }

    private void writeFine() throws IOException {
        Path f = dir.resolve("fine.pem");
        // a certificate with a long life: no warning, an empty note
        TestPki.Identity long1 = TestPki.issue(ca, "long", "EC", 400);
        long1.writeCertPem(f);
        long1.writeKeyPem(dir.resolve("old.key"), TestPki.KeyFormat.PKCS8, null);
        TlsMaterial t = build(Map.of("tls.cert-file", f.toString(), "tls.key-file", dir.resolve("old.key").toString()));
        assertThat(t.warnings()).isEmpty();
        assertThat(t.expiryNote(Instant.now())).isEmpty();
    }

    // ---------------------------------------------------------------- a real handshake

    @Test
    void aMutualTlsHandshakeSucceedsAndHostnameVerificationCatchesAWrongName() throws Exception {
        TestPki.Identity server = TestPki.issue(ca, "server", "RSA", 30);
        Path sCert = dir.resolve("s.pem");
        Path sKey = dir.resolve("s.key");
        server.writeCertPem(sCert);
        server.writeKeyPem(sKey, TestPki.KeyFormat.PKCS1_RSA, null);
        TlsMaterial srv = build(Map.of("tls.cert-file", sCert.toString(), "tls.key-file", sKey.toString(), "tls.ca-file", ca().toString()));
        Path cCert = dir.resolve("c.pem");
        Path cKey = dir.resolve("c.key");
        ec.writeCertPem(cCert);
        ec.writeKeyPem(cKey, TestPki.KeyFormat.SEC1_EC, null);
        TlsMaterial client = build(Map.of("tls.cert-file", cCert.toString(), "tls.key-file", cKey.toString(), "tls.ca-file", ca().toString()));

        try (SSLServerSocket ss = (SSLServerSocket) srv.sslContext().getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            ss.setNeedClientAuth(true);
            CompletableFuture<String> served = CompletableFuture.supplyAsync(() -> serve(ss, 2));
            int port = ss.getLocalPort();
            // right name: the certificate carries localhost and 127.0.0.1
            try (SSLSocket s = connect(client, InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1}), port)) {
                s.startHandshake();
                assertThat(s.getSession().getPeerPrincipal().getName()).contains("server");
            }
            // wrong name: refused
            try (SSLSocket s = connect(client, InetAddress.getByAddress("wrong.example.org", new byte[] {127, 0, 0, 1}), port)) {
                assertThatThrownBy(s::startHandshake).hasMessageContaining("No subject alternative DNS name matching wrong.example.org");
            }
            served.cancel(true);
        }
        // verify-hostname false lets the wrong name through
        TlsMaterial lax = build(Map.of("tls.cert-file", cCert.toString(), "tls.key-file", cKey.toString(), "tls.ca-file", ca().toString(),
                "tls.verify-hostname", "false"));
        try (SSLServerSocket ss = (SSLServerSocket) srv.sslContext().getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> served = CompletableFuture.supplyAsync(() -> serve(ss, 1));
            try (SSLSocket s = connect(lax, InetAddress.getByAddress("wrong.example.org", new byte[] {127, 0, 0, 1}), ss.getLocalPort())) {
                s.startHandshake();
            }
            served.cancel(true);
        }
    }

    @Test
    void aServerNobodyTrustedIsRefused() throws Exception {
        TestPki.Identity rogueCa = TestPki.ca("rogue");
        TestPki.Identity server = TestPki.issue(rogueCa, "server", "RSA", 30);
        Path sCert = dir.resolve("s.pem");
        Path sKey = dir.resolve("s.key");
        server.writeCertPem(sCert);
        server.writeKeyPem(sKey, TestPki.KeyFormat.PKCS8, null);
        TlsMaterial srv = build(Map.of("tls.cert-file", sCert.toString(), "tls.key-file", sKey.toString()));
        TlsMaterial client = build(Map.of("tls.ca-file", ca().toString()));
        try (SSLServerSocket ss = (SSLServerSocket) srv.sslContext().getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> served = CompletableFuture.supplyAsync(() -> serve(ss, 1));
            try (SSLSocket s = connect(client, InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1}), ss.getLocalPort())) {
                assertThatThrownBy(s::startHandshake).hasMessageContaining("PKIX path building failed");
            }
            served.cancel(true);
        }
    }

    private static SSLSocket connect(TlsMaterial m, InetAddress host, int port) throws IOException {
        SSLSocket s = (SSLSocket) m.socketFactory().createSocket();
        s.setSSLParameters(m.sslParameters());
        s.connect(new InetSocketAddress(host, port), 5000);
        return s;
    }

    private static String serve(SSLServerSocket ss, int connections) {
        for (int i = 0; i < connections; i++) {
            try (SSLSocket c = (SSLSocket) ss.accept()) {
                c.startHandshake();
            } catch (IOException ignored) {
                // the client under test refused: that is the point
            }
        }
        return "done";
    }

    @Test
    void sslContextIsUsableByOtherLibraries() throws Exception {
        SSLContext c = build(Map.of()).sslContext();
        assertThat(c.getProtocol()).isEqualTo("TLS");
    }
}
