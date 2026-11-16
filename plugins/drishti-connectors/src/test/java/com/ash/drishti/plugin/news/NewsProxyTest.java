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
package com.ash.drishti.plugin.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.proxy.SiteProxy;
import com.ash.drishti.testkit.DatedSourceContract;
import com.ash.drishti.testkit.FakeProxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The news connector through an outbound proxy (a fake one on a local port): via, no-proxy, the site default and {@code proxy: none}. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NewsProxyTest {

    private FakeNewsServer fake;

    @BeforeAll
    void up() throws Exception {
        fake = new FakeNewsServer();
    }

    @AfterAll
    void down() {
        fake.close();
    }

    @AfterEach
    void reset() {
        SiteProxy.reset();
    }

    private NewsSourcePlugin plugin(Map<String, String> extra) {
        Map<String, String> s = new HashMap<>(Map.of("base-url", fake.baseUrl(), "timeout-seconds", "5", "aliases.Acme", "Acme Corp"));
        s.putAll(extra);
        NewsSourcePlugin p = new NewsSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private static void read(NewsSourcePlugin p, String name) throws Exception {
        assertThat(p.fetch(EntityRef.of("news", "NEWS-" + name)).orElseThrow().data().get("ITEMS").size()).isPositive();
    }

    @Test
    void requestsGoThroughTheProxyWithItsCredentials() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            NewsSourcePlugin p = plugin(Map.of("proxy.url", proxy.url(), "proxy.username", "svc", "proxy.password", "pw"));
            read(p, "Acme");
            assertThat(proxy.forwarded()).isEqualTo(1);
            assertThat(p.health()).startsWith("UP via proxy").doesNotContain("pw");
        }
    }

    @Test
    void aNoProxyHostBypassesIt() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            read(plugin(Map.of("proxy.url", proxy.url(), "proxy.no-proxy", "127.0.0.1")), "Beta");
            assertThat(proxy.served()).isZero();
        }
    }

    @Test
    void proxyNoneOverridesTheSiteDefault() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            SiteProxy.configure(Map.of("proxy.url", proxy.url()), false);
            read(plugin(Map.of()), "Gamma");
            assertThat(proxy.forwarded()).isEqualTo(1);
            read(plugin(Map.of("proxy", "none")), "Delta");
            assertThat(proxy.forwarded()).isEqualTo(1);
        }
    }
}
