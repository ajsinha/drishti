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
package com.ash.drishti.plugin.feeds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.TlsFixture;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A feed read from an internal HTTPS mirror signed by a private CA (the {@code url} override with {@code tls.*}); no network. */
class FeedTlsTest {

    private static TlsFixture pki;
    private static HttpsServer server;

    @BeforeAll
    static void start() throws Exception {
        pki = new TlsFixture();
        byte[] csv = Files.readAllBytes(Path.of("src/test/resources/recorded/ecb-estr.csv"));
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(pki.serverContext(false)));
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, csv.length);
            ex.getResponseBody().write(csv);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        pki.delete();
    }

    private static FeedSourcePlugin feed(Map<String, String> tls) {
        Map<String, String> s = new HashMap<>(tls);
        s.put("feed", "ecb-estr");
        s.put("url", "https://localhost:" + server.getAddress().getPort() + "/estr.csv");
        FeedSourcePlugin p = new FeedSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    @Test
    void aMirrorOnAPrivateCaIsReadWhenTheCaIsTrusted() {
        FeedSourcePlugin p = feed(pki.trustOnlySettings());
        assertThat(p.health()).isEqualTo("UP");
        assertThat(p.manifest().kinds()).containsExactly("rate-fixing");
    }

    @Test
    void anUntrustedMirrorIsDownWithTheReason() {
        FeedSourcePlugin p = feed(pki.wrongCaSettings());
        assertThat(p.health()).startsWith("DOWN").contains("PKIX path building failed");
    }

    @Test
    void tlsSettingsWithAFileUrlAreRefused() {
        Map<String, String> s = new HashMap<>(pki.trustOnlySettings());
        s.put("feed", "ecb-estr");
        s.put("url", Path.of("src/test/resources/recorded/ecb-estr.csv").toUri().toString());
        assertThatThrownBy(() -> new FeedSourcePlugin().start(DatedSourceContract.context(s))).isInstanceOf(TlsException.class)
                .hasMessageContaining("tls.* is set but url is not https://");
    }
}
