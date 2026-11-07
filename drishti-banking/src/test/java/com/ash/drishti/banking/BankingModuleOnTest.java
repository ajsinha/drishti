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
package com.ash.drishti.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ash.drishti.registry.AccessRegistry;
import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The banking module on (as shipped): loaded, its API under {@code /api/v1/risk}, the old paths answered as the new ones with
 * {@code Deprecation} and a {@code Link} to the new path; a function switched off (the mnemonic switch, decided by the registry) answers
 * {@code 404 DRS-1026} on its APIs, and comes back when switched on.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance", "drishti.sources.plugins.demo.enabled=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class BankingModuleOnTest {

    @Autowired AccessRegistry registry;
    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    String auth() {
        return "Bearer " + tokens.mint("rita", List.of("admin"), 300);
    }

    @Test
    void loadedWithItsApiUnderRisk() throws Exception {
        assertThat(registry.moduleOn("banking")).isTrue();
        MvcResult m = mvc.perform(get("/api/v1/admin/modules").header("Authorization", auth())).andReturn();
        assertThat(m.getResponse().getContentAsString()).contains("\"id\":\"banking\"").contains("\"loaded\":true").contains("development");
        MvcResult now = mvc.perform(get("/api/v1/risk/simm/versions").header("Authorization", auth())).andReturn();
        assertThat(now.getResponse().getStatus()).as(now.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(now.getResponse().getHeader("Deprecation")).isNull();
    }

    @Test
    void theOldPathIsTheNewOneForThisRelease() throws Exception {
        MvcResult old = mvc.perform(get("/api/v1/simm/versions").header("Authorization", auth())).andReturn();
        MvcResult now = mvc.perform(get("/api/v1/risk/simm/versions").header("Authorization", auth())).andReturn();
        assertThat(old.getResponse().getStatus()).isEqualTo(200);
        assertThat(old.getResponse().getContentAsString()).isEqualTo(now.getResponse().getContentAsString());
        assertThat(old.getResponse().getHeader("Deprecation")).isEqualTo("true");
        assertThat(old.getResponse().getHeader("Link")).isEqualTo("</api/v1/risk/simm/versions>; rel=\"successor-version\"");
    }

    @Test
    void aFunctionOffIsGoneFromTheApi() throws Exception {
        try {
            registry.functionSwitches(w -> "SIMM".equals(w) ? Boolean.FALSE : null);
            MvcResult r = mvc.perform(get("/api/v1/risk/simm/versions").header("Authorization", auth())).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString()).contains("DRS-1026").contains("SIMM");
            MvcResult old = mvc.perform(get("/api/v1/simm/versions").header("Authorization", auth())).andReturn();
            assertThat(old.getResponse().getStatus()).isEqualTo(404);
            assertThat(registry.functionOn("SIMM")).isFalse();
            assertThat(registry.functionOn("VAR")).isTrue();
        } finally {
            registry.functionSwitches(null);
        }
        assertThat(mvc.perform(get("/api/v1/risk/simm/versions").header("Authorization", auth())).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
