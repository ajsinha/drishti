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
package com.ash.drishti.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The platform metrics in the registry ({@code /actuator/prometheus} exports it: {@code drishti.cache.entries} is
 * {@code drishti_cache_entries}), the alert list for administrators, and the open alerts on Health (the same properties as HealthApiTest, so
 * the two share one server).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class PlatformMetricsApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired PlatformMetrics metrics;
    @Autowired PlatformAlerts alerts;
    @Autowired MeterRegistry meters;

    private String as(String role) {
        return "Bearer " + tokens.mint("u-" + role, List.of(role), 60);
    }

    @Test
    void theGaugesAreSampledIntoTheRegistry() {
        metrics.sample(0);
        assertThat(meters.get("drishti.cache.entries").tag("cache", "var.dates").gauge().value()).isGreaterThanOrEqualTo(0);   // the dates open
        assertThat(meters.get("drishti.cache.limit.bytes").tag("cache", "var.answers").gauge().value()).isPositive();
        assertThat(meters.get("drishti.memory.budget.bytes").tag("pool", "all").gauge().value()).isPositive();
        assertThat(meters.get("drishti.memory.budget.used.bytes").tag("pool", "all").gauge()).isNotNull();
        assertThat(meters.get("drishti.memory.budget.squeezes").functionCounter()).isNotNull();
        assertThat(meters.get("drishti.source.up").tag("source", "demo").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("drishti.source.stale").tag("source", "demo").gauge().value()).isZero();
        assertThat(meters.get("drishti.feeds.attention").tag("state", "late").gauge()).isNotNull();
        assertThat(meters.get("drishti.direct.max.bytes").gauge().value()).isPositive();
        assertThat(meters.get("drishti.disk.free.bytes").gauge().value()).isPositive();
        assertThat(meters.get("drishti.disk.guard.bytes").gauge().value()).isPositive();
        assertThat(meters.get("drishti.alerts.open").tag("severity", "critical").gauge().value()).isGreaterThanOrEqualTo(0);
        assertThat(meters.find("drishti.view.percentile").tag("phi", "0.95").gauge()).isNotNull();       // the CIO lens's p95
        if (Files.isReadable(Path.of("/proc/self/maps"))) {
            assertThat(meters.get("drishti.mapped.files").gauge().value()).isPositive();
        }
    }

    @Test
    void theAlertListIsForAdministratorsAndHealthCarriesTheOpenOnes() throws Exception {
        alerts.evaluate();
        mvc.perform(get("/api/v1/admin/alerts").header("Authorization", as("admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.platform.enabled").value(true))
                .andExpect(jsonPath("$.platform.member").isNotEmpty())
                .andExpect(jsonPath("$.platform.rules").isArray())
                .andExpect(jsonPath("$.platform.open").isArray())
                .andExpect(jsonPath("$.platform.recent").isArray())
                .andExpect(jsonPath("$.rules").isArray());                       // the one alert engine's overview: everyone's rules beside the platform's
        mvc.perform(get("/api/v1/admin/alerts").header("Authorization", as("trader"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/health").header("Authorization", as("admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.enabled").value(true))
                .andExpect(jsonPath("$.alerts.open").isArray());
    }
}
