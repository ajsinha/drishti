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
package com.ash.drishti.server.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.proxy.ProxyException;
import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.api.proxy.SiteProxy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** {@code drishti.http.proxy.*} in application.yaml becomes the site default; off by default; a literal password is refused at start. */
class SiteProxyEnvironmentPostProcessorTest {

    private static final String FILE = "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'";

    @AfterEach
    void reset() {
        SiteProxy.reset();
    }

    private static MockEnvironment env(Map<String, Object> fromFile, Map<String, String> others) {
        MockEnvironment env = new MockEnvironment();
        others.forEach(env::setProperty);
        env.getPropertySources().addFirst(new MapPropertySource(FILE, new LinkedHashMap<>(fromFile)));
        return env;
    }

    private static void run(MockEnvironment env) {
        new SiteProxyEnvironmentPostProcessor().postProcessEnvironment(env, null);
    }

    @Test
    void offByDefault() {
        run(env(Map.of("drishti.http.proxy.url", "", "drishti.http.proxy.password", "${DRISHTI_HTTP_PROXY_PASSWORD:}"), Map.of()));
        assertThat(ProxySettings.resolve(Map.of(), "https://x.example").active()).isFalse();
    }

    @Test
    void theConfiguredProxyIsTheSiteDefaultForEveryConnector() {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("drishti.http.proxy.url", "http://proxy.corp:3128");
        yaml.put("drishti.http.proxy.username", "svc");
        yaml.put("drishti.http.proxy.password", "${PX_PW}");
        yaml.put("drishti.http.proxy.no-proxy[0]", ".corp.example");
        yaml.put("drishti.http.proxy.no-proxy[1]", "10.0.0.0/8");
        run(env(yaml, Map.of("PX_PW", "s3cr3t")));
        ProxySettings p = ProxySettings.resolve(Map.of(), "https://api.example.com");
        assertThat(p.source()).isEqualTo(ProxySettings.Source.SITE);
        assertThat(p.host()).isEqualTo("proxy.corp");
        assertThat(p.password()).isEqualTo("s3cr3t");
        assertThat(p.appliesTo("git.corp.example")).isFalse();
        assertThat(p.appliesTo("10.9.9.9")).isFalse();
        assertThat(p.appliesTo("api.example.com")).isTrue();
        assertThat(p.describe().toString()).doesNotContain("s3cr3t");
    }

    @Test
    void anEnvironmentVariableMayHoldThePasswordButAFileMayNot() {
        run(env(Map.of("drishti.http.proxy.url", "http://p:1", "drishti.http.proxy.username", "u"), Map.of("drishti.http.proxy.password", "from-env-var")));
        assertThat(ProxySettings.resolve(Map.of(), null).password()).isEqualTo("from-env-var");
        assertThatThrownBy(() -> run(env(Map.of("drishti.http.proxy.url", "http://p:1", "drishti.http.proxy.username", "u",
                "drishti.http.proxy.password", "hunter2"), Map.of()))).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("drishti.http.proxy.password must be a ${ENV_VAR} placeholder").hasMessageNotContaining("hunter2");
    }

    @Test
    void enabledFalseKeepsTheSettingsButSwitchesItOff() {
        run(env(Map.of("drishti.http.proxy.url", "http://p:1", "drishti.http.proxy.enabled", "false"), Map.of()));
        assertThat(ProxySettings.resolve(Map.of(), null).active()).isFalse();
    }

    @Test
    void aMalformedSiteProxyFailsTheStart() {
        assertThatThrownBy(() -> run(env(Map.of("drishti.http.proxy.url", "https://p:1"), Map.of()))).isInstanceOf(ProxyException.class)
                .hasMessageContaining("drishti.http.proxy.url");
    }
}
