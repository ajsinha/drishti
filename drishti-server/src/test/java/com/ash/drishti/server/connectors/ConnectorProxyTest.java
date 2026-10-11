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
package com.ash.drishti.server.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.proxy.SiteProxy;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.ConnectionProbe;
import com.ash.drishti.engine.source.SettingCatalogue;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.plugin.feeds.FeedSourcePlugin;
import com.ash.drishti.testkit.FakeProxy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * What Admin &rarr; Connectors says about a connector's outbound proxy: the effective one and where it came from, the password
 * never in the answer, a literal password and a malformed proxy refused on save, and "Test connection" going through the proxy and
 * saying so. A fake proxy and a stand-in feed server on local ports; no network.
 */
class ConnectorProxyTest {

    private static HttpServer feed;

    @BeforeAll
    static void start() throws Exception {
        byte[] csv = Files.readAllBytes(Path.of("../plugins/drishti-plugin-feeds/src/test/resources/recorded/ecb-estr.csv"));
        feed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        feed.createContext("/", ex -> {
            ex.sendResponseHeaders(200, csv.length);
            ex.getResponseBody().write(csv);
            ex.close();
        });
        feed.start();
    }

    @AfterAll
    static void stop() {
        feed.stop(0);
    }

    @AfterEach
    void reset() {
        SiteProxy.reset();
    }

    private ConnectorManager manager(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("packs/risk"));
        Files.writeString(dir.resolve("packs/risk/pack.yaml"), "pack: risk\nkinds: [rate-fixing]\n");
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"));
        MockEnvironment env = new MockEnvironment().withProperty("drishti.packs.dir", dir.resolve("packs").toString())
                .withProperty("drishti.packs.loaded", "risk").withProperty("PX_PW", "s3cr3t-pw");
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(1), null, Map.of(), dir.resolve("connectors").toString(), "poll",
                Duration.ofMillis(50), Duration.ZERO, true);
        FeedSourcePlugin proto = new FeedSourcePlugin();
        return new ConnectorManager(files, new SourceRegistry(List.of(proto), props, new JsonCodec()), props, new PackRegistry(env), env,
                new SettingCatalogue(List.of(proto)), new ConnectionProbe(List.of(proto), new JsonCodec()), 2, 10);
    }

    private static String file(String proxyBlock) {
        return "plugin: feed\nkinds: [rate-fixing]\nsettings:\n  feed: ecb-estr\n  url: http://127.0.0.1:" + feed.getAddress().getPort() + "/estr.csv\n" + proxyBlock;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> proxyOf(Map<String, Object> detail) {
        return (Map<String, Object>) detail.get("proxy");
    }

    @Test
    void theDetailShowsTheConnectorsProxyMaskedAndWhereItCameFrom(@TempDir Path dir) throws Exception {
        ConnectorManager m = manager(dir);
        m.files().write("quotes", file("  proxy:\n    url: http://proxy.corp:3128\n    username: svc\n    password: ${PX_PW}\n    no-proxy: [.corp.example]\n"));
        Map<String, Object> d = m.detail("quotes");
        assertThat(proxyOf(d)).containsEntry("enabled", true).containsEntry("source", "connector").containsEntry("url", "http://proxy.corp:3128")
                .containsEntry("username", "svc").containsEntry("password", "***").containsEntry("viaProxy", true);
        assertThat(d.toString()).doesNotContain("s3cr3t-pw");
    }

    @Test
    void theSiteDefaultIsInheritedAndProxyNoneSaysSo(@TempDir Path dir) throws Exception {
        ConnectorManager m = manager(dir);
        SiteProxy.configure(Map.of("proxy.url", "http://site-proxy:8080", "proxy.no-proxy", "127.0.0.1"), false);
        m.files().write("quotes", file(""));
        m.files().write("direct", file("  proxy: none\n"));
        assertThat(proxyOf(m.detail("quotes"))).containsEntry("source", "site").containsEntry("sourceLabel", "site default")
                .containsEntry("viaProxy", false).containsKey("note");                         // 127.0.0.1 is on the site's no-proxy list
        assertThat(proxyOf(m.detail("direct"))).containsEntry("enabled", false).containsEntry("source", "disabled")
                .containsEntry("sourceLabel", "disabled by the connector");
    }

    @Test
    void aConnectorOfAPluginWithoutHttpHasNoProxySection(@TempDir Path dir) throws Exception {
        ConnectorManager m = manager(dir);
        m.files().write("plain", "plugin: feed\nsettings:\n  feed: ecb-estr\n");
        assertThat(proxyOf(m.detail("plain"))).isNotNull();            // feed speaks HTTP
        assertThat(m.plugins()).filteredOn(p -> "feed".equals(p.get("name"))).singleElement().satisfies(p -> assertThat(p.get("proxy")).isEqualTo(true));
    }

    @Test
    void savingRefusesALiteralPasswordAndAMalformedProxy(@TempDir Path dir) throws Exception {
        ConnectorManager m = manager(dir);
        assertThatThrownBy(() -> m.save("quotes", file("  proxy:\n    url: http://p:3128\n    username: svc\n    password: hunter2\n"), null, false))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("settings.proxy.password").hasMessageContaining("credential is never written");
        assertThatThrownBy(() -> m.save("quotes", file("  proxy:\n    url: http://svc:hunter2@p:3128\n"), null, false))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("password inside a URL");
        assertThatThrownBy(() -> m.save("quotes", file("  proxy:\n    url: https://p:3128\n"), null, false))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("https://");
        assertThatThrownBy(() -> m.save("quotes", file("  proxy:\n    username: svc\n"), null, false))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("proxy.url is missing");
        assertThat(m.files().exists("quotes")).isFalse();
    }

    @Test
    void testConnectionGoesThroughTheProxySaysSoAndNeverShowsThePassword(@TempDir Path dir) throws Exception {
        ConnectorManager m = manager(dir);
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "s3cr3t-pw")) {
            Map<String, Object> r = m.test("quotes", file("  proxy:\n    url: " + proxy.url() + "\n    username: svc\n    password: ${PX_PW}\n"));
            assertThat(r).containsEntry("ok", true);
            assertThat(proxy.forwarded()).isEqualTo(1);
            assertThat(r.get("via").toString()).startsWith("through the connector proxy 127.0.0.1:" + proxy.port());
            assertThat(r.get("health").toString()).contains("via proxy");
            assertThat(proxyOf(r)).containsEntry("password", "***");
            assertThat(r.toString()).doesNotContain("s3cr3t-pw");
        }
    }

    @Test
    void testConnectionWithAWrongLoginExplainsTheProxyRefusal(@TempDir Path dir) throws Exception {
        ConnectorManager m = manager(dir);
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "another")) {
            Map<String, Object> r = m.test("quotes", file("  proxy:\n    url: " + proxy.url() + "\n    username: svc\n    password: ${PX_PW}\n"));
            assertThat(r.get("health").toString()).startsWith("DOWN").contains("407");
            assertThat(r.toString()).doesNotContain("s3cr3t-pw");
        }
    }

    @Test
    void testConnectionWithAnUnreachableProxyHintsAtTheProxy(@TempDir Path dir) throws Exception {
        ConnectorManager m = manager(dir);
        Map<String, Object> r = m.test("quotes", file("  proxy:\n    url: http://127.0.0.1:1\n"));
        assertThat(r.get("health").toString()).contains("cannot connect to the proxy 127.0.0.1:1");
    }

    @Test
    void theHintNamesTheProxySettings() {
        assertThat(ConnectorManager.hint("the proxy a:1 refused the credentials (HTTP 407 Proxy Authentication Required)")).contains("proxy.username");
        assertThat(ConnectorManager.hint("Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 403 Forbidden\"")).contains("CONNECT");
    }
}
