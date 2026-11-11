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

import com.ash.drishti.api.proxy.NoProxy;
import com.ash.drishti.api.proxy.ProxyException;
import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.api.proxy.SiteProxy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** How the effective proxy is chosen (connector, site default, environment), what it refuses, and what it never shows. */
class ProxySettingsTest {

    private static final Map<String, String> NO_ENV = Map.of();

    @AfterEach
    void reset() {
        SiteProxy.reset();
    }

    private static ProxySettings resolve(Map<String, String> settings, String target, Map<String, String> env) {
        return ProxySettings.resolve(settings, target, env::get);
    }

    @Test
    void noSettingsAndNoSiteDefaultMeansDirect() {
        ProxySettings p = resolve(Map.of(), "https://api.example.com", NO_ENV);
        assertThat(p.active()).isFalse();
        assertThat(p.source()).isEqualTo(ProxySettings.Source.NONE);
        assertThat(p.note()).isEmpty();
        assertThat(p.annotate("UP")).isEqualTo("UP");
    }

    @Test
    void aConnectorProxyBlockIsRead() {
        ProxySettings p = resolve(Map.of("proxy.url", "http://proxy.corp:3128", "proxy.username", "svc", "proxy.password", "pw",
                "proxy.no-proxy", "intranet.example, 10.0.0.0/8"), "https://api.example.com", NO_ENV);
        assertThat(p.active()).isTrue();
        assertThat(p.source()).isEqualTo(ProxySettings.Source.CONNECTOR);
        assertThat(p.uri()).isEqualTo(URI.create("http://proxy.corp:3128"));
        assertThat(p.username()).isEqualTo("svc");
        assertThat(p.appliesTo("api.example.com")).isTrue();
        assertThat(p.appliesTo("db.intranet.example")).isFalse();
        assertThat(p.appliesTo("10.2.3.4")).isFalse();
        assertThat(p.note()).isEqualTo("via proxy proxy.corp:3128 (connector)");
    }

    @Test
    void passwordsComeFromTheEnvironmentAndNeverAppearInDescriptionsOrToString() {
        ProxySettings p = resolve(Map.of("proxy.url", "proxy.corp:3128", "proxy.username", "svc", "proxy.password", "${PX_PW}"),
                null, Map.of("PX_PW", "s3cr3t-value"));
        assertThat(p.password()).isEqualTo("s3cr3t-value");
        assertThat(p.describe().toString()).doesNotContain("s3cr3t-value").contains("***");
        assertThat(p.toString()).doesNotContain("s3cr3t-value");
        assertThat(p.annotate("UP")).doesNotContain("s3cr3t-value");
        assertThat(p.describe()).containsEntry("url", "http://proxy.corp:3128").containsEntry("password", "***");
    }

    @Test
    void proxyNoneOverridesTheSiteDefault() {
        SiteProxy.configure(Map.of("proxy.url", "http://site-proxy:8080"), false);
        assertThat(resolve(Map.of(), "https://x.example", NO_ENV).source()).isEqualTo(ProxySettings.Source.SITE);
        ProxySettings none = resolve(Map.of("proxy", "none"), "https://x.example", NO_ENV);
        assertThat(none.active()).isFalse();
        assertThat(none.source()).isEqualTo(ProxySettings.Source.DISABLED);
        assertThat(resolve(Map.of("proxy.enabled", "false", "proxy.url", "http://p:1"), "https://x.example", NO_ENV).active()).isFalse();
    }

    @Test
    void aConnectorProxyBeatsTheSiteDefault() {
        SiteProxy.configure(Map.of("proxy.url", "http://site-proxy:8080"), false);
        ProxySettings p = resolve(Map.of("proxy.url", "http://own:3128"), "https://x.example", NO_ENV);
        assertThat(p.host()).isEqualTo("own");
        assertThat(p.source()).isEqualTo(ProxySettings.Source.CONNECTOR);
    }

    @Test
    void theSiteDefaultCarriesItsOwnNoProxyList() {
        SiteProxy.configure(Map.of("proxy.url", "http://site-proxy:8080", "proxy.no-proxy", ".corp.example"), false);
        ProxySettings p = resolve(Map.of(), "https://x.example", NO_ENV);
        assertThat(p.appliesTo("git.corp.example")).isFalse();
        assertThat(p.appliesTo("x.example")).isTrue();
    }

    @Test
    void theEnvironmentIsHonouredOnlyWhenTheSiteAsks() {
        Map<String, String> env = Map.of("HTTPS_PROXY", "http://envproxy:3128", "HTTP_PROXY", "http://plain:8080", "NO_PROXY", "localhost,.local");
        assertThat(resolve(Map.of(), "https://x.example", env).active()).isFalse();
        SiteProxy.configure(Map.of(), true);
        ProxySettings https = resolve(Map.of(), "https://x.example", env);
        assertThat(https.source()).isEqualTo(ProxySettings.Source.ENVIRONMENT);
        assertThat(https.host()).isEqualTo("envproxy");
        assertThat(https.appliesTo("localhost")).isFalse();
        assertThat(https.appliesTo("a.local")).isFalse();
        assertThat(https.describe().get("sourceLabel")).isEqualTo("environment (HTTPS_PROXY)");
        assertThat(resolve(Map.of(), "http://x.example", env).host()).isEqualTo("plain");
        // a site proxy wins over the environment
        SiteProxy.configure(Map.of("proxy.url", "http://site:1"), true);
        assertThat(resolve(Map.of(), "https://x.example", env).host()).isEqualTo("site");
    }

    @Test
    void credentialsInTheEnvironmentUrlAreSplitOutAndMasked() {
        SiteProxy.configure(Map.of(), true);
        ProxySettings p = resolve(Map.of(), "https://x.example", Map.of("https_proxy", "http://bob:p%40ss@envproxy:3128"));
        assertThat(p.username()).isEqualTo("bob");
        assertThat(p.password()).isEqualTo("p@ss");
        assertThat(p.describe().toString()).doesNotContain("p@ss").doesNotContain("p%40ss");
    }

    @Test
    void badSettingsAreRefusedNamingTheKey() {
        assertThatThrownBy(() -> resolve(Map.of("proxy.username", "u"), null, NO_ENV)).isInstanceOf(ProxyException.class)
                .hasMessageContaining("proxy.url is missing");
        assertThatThrownBy(() -> resolve(Map.of("proxy.url", "http://p:1", "proxy.username", "u"), null, NO_ENV))
                .hasMessageContaining("proxy.username is given without proxy.password");
        assertThatThrownBy(() -> resolve(Map.of("proxy.url", "http://u:pw@p:1"), null, NO_ENV))
                .hasMessageContaining("must not carry credentials").hasMessageNotContaining("pw@");
        assertThatThrownBy(() -> resolve(Map.of("proxy.url", "https://p:1"), null, NO_ENV)).hasMessageContaining("https://")
                .hasMessageContaining("CONNECT");
        assertThatThrownBy(() -> resolve(Map.of("proxy.url", "socks5://p:1"), null, NO_ENV)).hasMessageContaining("not supported");
        assertThatThrownBy(() -> resolve(Map.of("proxy", "yes please"), null, NO_ENV)).hasMessageContaining("proxy: none");
        assertThatThrownBy(() -> resolve(Map.of("proxy.url", "http://p:1", "proxy.password", "${MISSING_VAR}", "proxy.username", "u"), null, NO_ENV))
                .hasMessageContaining("MISSING_VAR");
    }

    @Test
    void noProxyMatching() {
        NoProxy n = new NoProxy(List.of("localhost", ".corp.example", "*.dev.example", "10.0.0.0/8", "192.168.1.5", "fd00::/8", "svc:8080"));
        assertThat(n.matches("localhost")).isTrue();
        assertThat(n.matches("LOCALHOST")).isTrue();
        assertThat(n.matches("git.corp.example")).isTrue();
        assertThat(n.matches("corp.example")).isTrue();
        assertThat(n.matches("a.dev.example")).isTrue();
        assertThat(n.matches("dev.example")).isTrue();      // as curl does: the bare domain too
        assertThat(n.matches("notcorp.example")).isFalse();
        assertThat(n.matches("10.200.1.1")).isTrue();
        assertThat(n.matches("11.0.0.1")).isFalse();
        assertThat(n.matches("192.168.1.5")).isTrue();
        assertThat(n.matches("192.168.1.6")).isFalse();
        assertThat(n.matches("fd12::1")).isTrue();
        assertThat(n.matches("[fd12::1]")).isTrue();
        assertThat(n.matches("svc")).isTrue();
        assertThat(n.matches("example.com")).isFalse();
        assertThat(new NoProxy(List.of("*")).matches("anything.example")).isTrue();
        assertThatThrownBy(() -> new NoProxy(List.of("10.0.0.0/99"))).isInstanceOf(ProxyException.class);
    }

    @Test
    void aYamlListInAnAiFileArrivesIndexed() {
        ProxySettings p = resolve(Map.of("proxy.url", "http://p:1", "proxy.no-proxy.0", "a.example", "proxy.no-proxy.1", "10.0.0.0/8"), null, NO_ENV);
        assertThat(p.appliesTo("a.example")).isFalse();
        assertThat(p.appliesTo("10.1.1.1")).isFalse();
        assertThat(p.appliesTo("b.example")).isTrue();
    }

    @Test
    void thePasswordSettingIsASecretInTheFormSpecs() {
        assertThat(ProxySettings.settingSpecs()).anyMatch(s -> s.name().equals("proxy.password") && s.secret())
                .anyMatch(s -> s.name().equals("proxy") && !s.secret());
    }
}
