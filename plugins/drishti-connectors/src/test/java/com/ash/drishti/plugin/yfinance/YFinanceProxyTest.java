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
package com.ash.drishti.plugin.yfinance;

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

/** The yfinance connector through an outbound proxy (a fake one on a local port): via, no-proxy, the site default and {@code proxy: none}. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YFinanceProxyTest {

    private FakeYahooServer fake;

    @BeforeAll
    void up() throws Exception {
        fake = new FakeYahooServer();
    }

    @AfterAll
    void down() {
        fake.close();
    }

    @AfterEach
    void reset() {
        SiteProxy.reset();
    }

    private YFinanceSourcePlugin plugin(Map<String, String> extra) {
        Map<String, String> s = new HashMap<>(Map.of("base-url", fake.baseUrl(), "timeout-seconds", "5"));
        s.putAll(extra);
        YFinanceSourcePlugin p = new YFinanceSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private static void read(YFinanceSourcePlugin p, String id) throws Exception {
        assertThat(p.fetch(EntityRef.of("quote", id)).orElseThrow().data().get("PRICE").asDouble()).isEqualTo(191.5);
    }

    @Test
    void requestsGoThroughTheProxyWithItsCredentials() throws Exception {
        try (FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            YFinanceSourcePlugin p = plugin(Map.of("proxy.url", proxy.url(), "proxy.username", "svc", "proxy.password", "pw"));
            read(p, "QUOTE-acme");
            assertThat(proxy.forwarded()).isEqualTo(1);
            assertThat(p.health()).startsWith("UP via proxy").doesNotContain("pw");
        }
    }

    @Test
    void aNoProxyHostBypassesIt() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            read(plugin(Map.of("proxy.url", proxy.url(), "proxy.no-proxy", "127.0.0.1")), "QUOTE-beta");
            assertThat(proxy.served()).isZero();
        }
    }

    @Test
    void proxyNoneOverridesTheSiteDefault() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            SiteProxy.configure(Map.of("proxy.url", proxy.url()), false);
            read(plugin(Map.of()), "QUOTE-gamma");
            assertThat(proxy.forwarded()).isEqualTo(1);
            read(plugin(Map.of("proxy", "none")), "QUOTE-delta");
            assertThat(proxy.forwarded()).isEqualTo(1);
        }
    }
}
