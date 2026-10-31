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
package com.ash.drishti.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** GET /api/v1/admin/settings: values in effect with their origins, administrators only, secrets never shown (CONFIGURATION.md, C3). */
class EffectiveSettingsControllerTest {

    private static final String BUNDLED = "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'";
    private static final String LOCAL = "Config resource 'file [application.local.yaml]' via location 'optional:file:./application.local.yaml'";

    private static StandardEnvironment env(Map<String, Object> envVars) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        Map<String, Object> bundled = new LinkedHashMap<>();
        bundled.put("drishti.history.max-days", "366");
        bundled.put("drishti.history.max-rows", "1000");
        bundled.put("server.port", "${DRISHTI_PORT:18480}");
        bundled.put("drishti.calc.enabled", "${DRISHTI_CALC_ENABLED:true}");
        bundled.put("drishti.security.secret", "${DRISHTI_TOKEN_SECRET:}");
        bundled.put("drishti.http.proxy.password", "${DRISHTI_HTTP_PROXY_PASSWORD:}");
        bundled.put("drishti.sources.plugins.jdbc.settings.url", "jdbc:postgresql://ash:hunter2@db:5432/drishti");
        bundled.put("drishti.security.token-read-posts[0]", "/api/v1/command");
        bundled.put("drishti.security.token-read-posts[1]", "/api/v1/search/pivot/**");
        bundled.put("drishti.redact[0]", "TRADER_ID");
        bundled.put("drishti.redact[1]", "counterpartyId");
        bundled.put("unrelated.key", "x");
        env.getPropertySources().addLast(new MapPropertySource(BUNDLED, bundled));
        env.getPropertySources().addFirst(new MapPropertySource(LOCAL, Map.of("drishti.history.max-rows", "500")));
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment", envVars));
        env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of("drishti.history.max-days", "90")));
        return env;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> byKey(Map<String, Object> answer) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) answer.get("settings")) {
            out.put((String) row.get("key"), row);
        }
        return out;
    }

    @Test
    void eachValueInEffectComesWithWhereItCameFrom() {
        Map<String, Object> vars = Map.of("DRISHTI_CALC_ENABLED", "false", "DRISHTI_HISTORY_TIMEOUT", "20s");
        StandardEnvironment env = env(vars);
        Map<String, Map<String, Object>> rows = byKey(EffectiveSettingsController.snapshot(env, name -> (String) vars.get(name)));

        assertThat(rows.get("drishti.history.max-days")).containsEntry("value", "90").containsEntry("origin", "argument");
        assertThat(rows.get("drishti.history.max-rows")).containsEntry("value", "500").containsEntry("origin", "file")
                .containsEntry("source", "application.local.yaml");
        assertThat(rows.get("server.port")).containsEntry("value", "18480").containsEntry("origin", "default")
                .containsEntry("source", "application.yaml");
        assertThat(rows.get("drishti.calc.enabled")).containsEntry("value", "false").containsEntry("origin", "env")
                .containsEntry("source", "DRISHTI_CALC_ENABLED");
        assertThat(rows.get("drishti.redact")).containsEntry("value", "TRADER_ID, counterpartyId");
        assertThat(rows).doesNotContainKey("unrelated.key").doesNotContainKey("drishti.redact[0]");
    }

    @Test
    void secretsAreNeverShown() {
        Map<String, Object> vars = Map.of("DRISHTI_TOKEN_SECRET", "a-very-long-signing-secret-of-32-bytes!");
        Map<String, Map<String, Object>> rows = byKey(EffectiveSettingsController.snapshot(env(vars), name -> (String) vars.get(name)));

        assertThat(rows.get("drishti.security.secret")).containsEntry("value", EffectiveSettingsController.MASK).containsEntry("secret", true)
                .containsEntry("origin", "env");
        assertThat(rows.get("drishti.http.proxy.password")).containsEntry("value", "").containsEntry("secret", true);
        assertThat(rows.get("drishti.security.token-read-posts")).containsEntry("value", EffectiveSettingsController.MASK);
        assertThat(String.valueOf(rows.get("drishti.sources.plugins.jdbc.settings.url").get("value")))
                .isEqualTo("jdbc:postgresql://ash:" + EffectiveSettingsController.MASK + "@db:5432/drishti");
        assertThat(rows.values().toString()).doesNotContain("a-very-long-signing-secret").doesNotContain("hunter2");

        assertThat(EffectiveSettingsController.isSecret("drishti.embed.signing-key")).isTrue();
        assertThat(EffectiveSettingsController.isSecret("drishti.collab.bridges.credentials")).isTrue();
        assertThat(EffectiveSettingsController.isSecret("drishti.bi.ai.max-tokens")).isFalse();
        assertThat(EffectiveSettingsController.isSecret("drishti.var.keep-on-shutdown")).isFalse();
    }

    @Test
    void onlyAdministratorsMayAsk() {
        Entitlements ent = mock(Entitlements.class);
        Principal viewer = new Principal("vic", List.of("viewer"));
        doThrow(new DrishtiException(ErrorCode.FORBIDDEN, "vic is not an administrator")).when(ent).requireAdmin(viewer);
        EffectiveSettingsController c = new EffectiveSettingsController(env(Map.of()), ent);

        assertThatThrownBy(() -> c.effective(viewer)).isInstanceOf(DrishtiException.class);
        assertThat(c.effective(new Principal("root", List.of("admin")))).containsKey("settings");
    }
}
