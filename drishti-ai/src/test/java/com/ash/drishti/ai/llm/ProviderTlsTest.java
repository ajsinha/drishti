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
package com.ash.drishti.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.ai.FakeLlmServer;
import com.ash.drishti.ai.LlmException;
import com.ash.drishti.ai.llm.Canonical.ChatMessage;
import com.ash.drishti.testkit.TestPki;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The shared {@code tls.*} settings of the connectors ({@code docs/connectors/TLS.md}, {@code TlsHttp}) for every provider type, against fake HTTPS servers with a test CA
 * ({@link TestPki}): a private CA is trusted only when configured, mutual TLS is refused without the client certificate, the other trust and identity forms (PEM text, PKCS12
 * and JKS stores, an encrypted key with a password file), the expiry note, the plain-address error, and everything through an HTTP proxy that tunnels with CONNECT. The console
 * has the same tests in {@code tests/test_ai_tls.py}. No real network.
 */
class ProviderTlsTest {

    @TempDir
    static Path dir;
    static TestPki.Identity ca;
    static TestPki.Identity otherCa;
    static TestPki.Identity server;
    static TestPki.Identity client;
    static TestPki.Identity soon;
    static Map<String, String> files = new LinkedHashMap<>();

    @BeforeAll
    static void pki() throws Exception {
        ca = TestPki.ca("Test CA");
        otherCa = TestPki.ca("Other CA");
        server = TestPki.issue(ca, "server", "RSA", 365);
        client = TestPki.issue(ca, "client", "RSA", 365);
        soon = TestPki.issue(ca, "expiring-client", "RSA", 10);
        ca.writeCertPem(dir.resolve("ca.pem"));
        otherCa.writeCertPem(dir.resolve("other.pem"));
        client.writeCertPem(dir.resolve("cli.pem"));
        client.writeKeyPem(dir.resolve("cli.key"), TestPki.KeyFormat.PKCS8, null);
        client.writeKeyPem(dir.resolve("cli-enc.key"), TestPki.KeyFormat.ENCRYPTED_PKCS8, "s3cret");
        soon.writeCertPem(dir.resolve("soon.pem"));
        soon.writeKeyPem(dir.resolve("soon.key"), TestPki.KeyFormat.PKCS8, null);
        client.writeKeystore(dir.resolve("cli.p12"), "PKCS12", "s3cret", "client", "s3cret");
        TestPki.writeTruststore(dir.resolve("trust.p12"), "PKCS12", "s3cret", ca.cert());
        TestPki.writeTruststore(dir.resolve("trust.jks"), "JKS", "s3cret", ca.cert());
        Files.writeString(dir.resolve("pw"), "s3cret\n");
        for (String n : List.of("ca.pem", "other.pem", "cli.pem", "cli.key", "cli-enc.key", "soon.pem", "soon.key", "cli.p12", "trust.p12", "trust.jks", "pw")) {
            files.put(n, dir.resolve(n).toString());
        }
    }

    static FakeLlmServer https(String api, boolean mtls) throws Exception {
        return new FakeLlmServer(api, FakeLlmServer.sslContext(server, dir, mtls ? ca : null), mtls);
    }

    static ProviderBase prov(String type, String url, Map<String, String> tls) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", type);
        cfg.put("base-url", url);
        cfg.put("timeout", "5s");
        cfg.put("catalogue", false);
        cfg.put("models", List.of(Map.of("name", "m")));
        if (!"ollama".equals(type) && !"bedrock".equals(type)) {
            cfg.put("api-key", "${THE_KEY}");
        }
        if ("openai".equals(type)) {
            cfg.put("completions-path", "/chat/completions");
        }
        if ("bedrock".equals(type)) {
            cfg.putAll(Map.of("aws-access-key-id", "${AKID}", "aws-secret-access-key", "${ASECRET}", "region", "us-east-1"));
        }
        if (!tls.isEmpty()) {
            cfg.put("tls", new LinkedHashMap<>(tls));
        }
        return HttpProvidersTest.build(type, cfg, HttpProvidersTest.ENV);
    }

    static ProviderBase groq(String url, Map<String, String> tls) {
        return prov("groq", url, tls);
    }

    static String ask(ProviderBase p) {
        return p.chatModel("m", Map.of()).chatCompletionsCreate(ChatCompletionRequest.of("m", List.of(ChatMessage.user("ping")))).text();
    }

    static Map<String, String> tls(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void aPrivateCaIsTrustedOnlyWhenItIsConfigured() throws Exception {
        try (var s = https("openai", false)) {
            assertThatThrownBy(() -> ask(groq(s.url(), Map.of()))).isInstanceOf(LlmException.class).hasMessageContaining("could not be reached");
            assertThat(ask(groq(s.url(), tls("ca-file", files.get("ca.pem"))))).isEqualTo("echo: ping");
            assertThatThrownBy(() -> ask(groq(s.url(), tls("ca-file", files.get("other.pem"))))).isInstanceOf(LlmException.class);
        }
    }

    @Test
    void theOtherTrustFormsPemTextPkcs12JksAndTheJvmAuthoritiesMerged() throws Exception {
        try (var s = https("openai", false)) {
            assertThat(ask(groq(s.url(), tls("ca-file", Files.readString(dir.resolve("ca.pem")))))).isEqualTo("echo: ping");
            assertThat(ask(groq(s.url(), tls("truststore", files.get("trust.p12"), "truststore-password-file", files.get("pw"))))).isEqualTo("echo: ping");
            assertThat(ask(groq(s.url(), tls("truststore", files.get("trust.jks"), "truststore-password-file", files.get("pw"))))).isEqualTo("echo: ping");
            assertThat(ask(groq(s.url(), tls("ca-file", files.get("ca.pem"), "trust-jvm-default", "true")))).isEqualTo("echo: ping");
        }
    }

    @Test
    void mutualTlsIsRefusedWithoutTheClientCertificateAndAcceptedWithIt() throws Exception {
        try (var s = https("openai", true)) {
            assertThatThrownBy(() -> ask(groq(s.url(), tls("ca-file", files.get("ca.pem"))))).isInstanceOf(LlmException.class);
            assertThat(ask(groq(s.url(), tls("ca-file", files.get("ca.pem"), "cert-file", files.get("cli.pem"), "key-file", files.get("cli.key"))))).isEqualTo("echo: ping");
            assertThat(ask(groq(s.url(), tls("ca-file", files.get("ca.pem"), "cert-file", files.get("cli.pem"), "key-file", files.get("cli-enc.key"), "key-password-file",
                    files.get("pw"))))).isEqualTo("echo: ping");
            assertThat(ask(groq(s.url(), tls("ca-file", files.get("ca.pem"), "keystore", files.get("cli.p12"), "keystore-password-file", files.get("pw"))))).isEqualTo("echo: ping");
        }
    }

    @Test
    void tlsSettingsAgainstAPlainAddressAreAnErrorNotADowngrade() {
        assertThatThrownBy(() -> groq("http://127.0.0.1:1", tls("ca-file", files.get("ca.pem")))).hasMessageContaining("not https://");
    }

    @Test
    void aCertificateCloseToExpiryShowsInHealthAndDescribeConfig() throws Exception {
        try (var s = https("openai", false)) {
            ProviderBase p = groq(s.url(), tls("ca-file", files.get("ca.pem"), "cert-file", files.get("soon.pem"), "key-file", files.get("soon.key")));
            HealthStatus h = p.health();
            assertThat(h.ok()).isTrue();
            assertThat(h.detail()).contains("TLS certificate").contains("expires in").contains("tls.cert-file");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> certs = (List<Map<String, String>>) p.describeConfig().get("tls");
            assertThat(certs).extracting(c -> c.get("source")).contains("tls.ca-file", "tls.cert-file");
            assertThat(groq(s.url(), tls("ca-file", files.get("ca.pem"))).health().detail()).doesNotContain("expires in");
        }
    }

    /** An HTTP proxy that only tunnels (CONNECT host:port, then bytes both ways); it records the tunnels. */
    static final class ConnectProxy implements AutoCloseable {
        final ServerSocket socket;
        final List<String> tunnels = new CopyOnWriteArrayList<>();

        ConnectProxy() throws IOException {
            socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket c = socket.accept();
                        Thread.ofVirtual().start(() -> serve(c));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        private void serve(Socket c) {
            try {
                var in = c.getInputStream();
                StringBuilder head = new StringBuilder();
                while (!head.toString().endsWith("\r\n\r\n")) {
                    int b = in.read();
                    if (b < 0) {
                        return;
                    }
                    head.append((char) b);
                }
                String first = head.toString().split("\r\n")[0];
                if (!first.startsWith("CONNECT ")) {
                    c.getOutputStream().write("HTTP/1.1 405 no\r\nContent-Length: 0\r\n\r\n".getBytes());
                    return;
                }
                String target = first.split(" ")[1];
                tunnels.add(target);
                Socket up = new Socket(target.substring(0, target.lastIndexOf(':')), Integer.parseInt(target.substring(target.lastIndexOf(':') + 1)));
                c.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes());
                Thread.ofVirtual().start(() -> pipe(c, up));
                pipe(up, c);
            } catch (IOException e) {
                // the client went away
            }
        }

        private static void pipe(Socket a, Socket b) {
            try {
                a.getInputStream().transferTo(b.getOutputStream());
            } catch (IOException e) {
                // closed
            } finally {
                try {
                    a.close();
                    b.close();
                } catch (IOException e) {
                    // closed
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void theTlsSettingsHoldThroughAnHttpProxyTunnel() throws Exception {
        try (var s = https("openai", true); var px = new ConnectProxy()) {
            Map<String, String> good = tls("ca-file", files.get("ca.pem"), "cert-file", files.get("cli.pem"), "key-file", files.get("cli.key"));
            Map<String, Object> cfg = new LinkedHashMap<>(Map.of("type", "groq", "base-url", s.url(), "api-key", "${THE_KEY}", "timeout", "5s", "catalogue", false, "models",
                    List.of(Map.of("name", "m")), "proxy", Map.of("url", px.url())));
            cfg.put("tls", good);
            assertThat(ask(HttpProvidersTest.build("groq", cfg, HttpProvidersTest.ENV))).isEqualTo("echo: ping");
            assertThat(px.tunnels).containsExactly(s.url().substring("https://".length()));
            cfg.put("tls", tls("ca-file", files.get("ca.pem")));
            assertThatThrownBy(() -> ask(HttpProvidersTest.build("groq", cfg, HttpProvidersTest.ENV))).isInstanceOf(LlmException.class);
            cfg.put("tls", tls("cert-file", files.get("cli.pem"), "key-file", files.get("cli.key")));
            assertThatThrownBy(() -> ask(HttpProvidersTest.build("groq", cfg, HttpProvidersTest.ENV))).isInstanceOf(LlmException.class);
            assertThat(px.tunnels).hasSize(3);
        }
    }

    @ParameterizedTest
    @CsvSource({"gemini,gemini", "cohere,cohere", "ollama,ollama", "azure_openai,azure", "mistral,openai", "openai,openai"})
    void everyProviderTypeTakesTheSameTlsBlock(String type, String api) throws Exception {
        try (var s = https(api, false)) {
            assertThatThrownBy(() -> ask(prov(type, s.url(), Map.of()))).isInstanceOf(LlmException.class);
            assertThat(ask(prov(type, s.url(), tls("ca-file", files.get("ca.pem"))))).isEqualTo("echo: ping");
        }
    }

    @Test
    void bedrockThroughTheAwsSdkTakesTheCaAndTheClientCertificate() throws Exception {
        try (var s = https("bedrock", false); var m = https("bedrock", true)) {
            assertThatThrownBy(() -> ask(prov("bedrock", s.url(), Map.of()))).isInstanceOf(LlmException.class);
            assertThat(ask(prov("bedrock", s.url(), tls("ca-file", files.get("ca.pem"))))).isEqualTo("echo: ping");
            assertThatThrownBy(() -> ask(prov("bedrock", m.url(), tls("ca-file", files.get("ca.pem"))))).isInstanceOf(LlmException.class);
            assertThat(ask(prov("bedrock", m.url(), tls("ca-file", files.get("ca.pem"), "cert-file", files.get("cli.pem"), "key-file", files.get("cli.key"))))).isEqualTo("echo: ping");
            assertThatThrownBy(() -> prov("bedrock", s.url(), tls("ca-file", files.get("ca.pem"), "verify-hostname", "false"))).hasMessageContaining("verify-hostname");
            assertThat(Instant.now()).isNotNull();
            List<String> unused = new ArrayList<>();
            assertThat(unused).isEmpty();
        }
    }
}
