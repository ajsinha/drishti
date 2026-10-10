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
package com.ash.drishti.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sample packs ({@code sample: true}; logistics is one): who sees them in each mode. The configured mode is
 * {@code developers}: users with the author or admin power see them, others get them filtered on the server exactly as a
 * pack that is switched off. An administrator's saved setting overrides the property and is applied at once.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance,logistics",
        "drishti.packs.samples=developers", "drishti.packs.samples-file=target/sample-packs-test/mode",
        "drishti.identity.database-url=jdbc:sqlite:target/samplepacks-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/sample-packs-test/added.yaml"})
@AutoConfigureMockMvc
class SamplePacksTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    private void samples(String admin, String mode) throws Exception {
        mvc.perform(put("/api/v1/admin/packs/samples").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"" + mode + "\"}")).andExpect(status().isOk());
    }

    @Test
    void developersSeeSamplesOthersDoNotAndTheAdminSettingOverridesTheProperty() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        String author = as("ann", "author");
        String viewer = as("vera", "viewer");
        samples(admin, "default");                                                                // the property: developers

        mvc.perform(get("/api/v1/packs").header("Authorization", viewer)).andExpect(jsonPath("$[*].name").value(not(hasItem("logistics"))))
                .andExpect(jsonPath("$[*].name").value(hasItem("finance")));
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/command/suggest").param("q", "SHP").header("Authorization", viewer))
                .andExpect(jsonPath("$[*].kind").value(not(hasItem("shipment"))));
        mvc.perform(get("/api/v1/packs/LOGI/overview").header("Authorization", viewer)).andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/packs").header("Authorization", author)).andExpect(jsonPath("$[?(@.name=='logistics')].sample").value(hasItem(true)));
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", author)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/command/suggest").param("q", "SHP").header("Authorization", author))
                .andExpect(jsonPath("$[*].kind").value(hasItem("shipment")));
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/packs").header("Authorization", admin))
                .andExpect(jsonPath("$[?(@.name=='logistics')].sample").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.name=='finance')].sample").value(hasItem(false)));

        samples(admin, "visible");                                                                // saved: everyone
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/packs/samples").header("Authorization", admin)).andExpect(jsonPath("$.mode").value("visible"))
                .andExpect(jsonPath("$.overridden").value(true)).andExpect(jsonPath("$.configured").value("developers"))
                .andExpect(jsonPath("$.samples").value(hasItem("logistics")));

        samples(admin, "hidden");                                                                 // nobody: not even a developer; not loaded
        mvc.perform(get("/api/v1/packs").header("Authorization", author)).andExpect(jsonPath("$[*].name").value(not(hasItem("logistics"))));
        mvc.perform(get("/api/v1/admin/packs").header("Authorization", admin)).andExpect(jsonPath("$[?(@.name=='logistics')].loaded").value(hasItem(false)));
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", author))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value(containsString("pack removed")));

        samples(admin, "default");                                                                // back to the property
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", author)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/views/shipment/SHP-10042").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit").param("subject", "hidden").header("Authorization", admin))
                .andExpect(jsonPath("$[*].action").value(hasItem("pack-samples")));
    }

    @Test
    void onlyAdminsChangeTheModeAndOnlyToAKnownOne() throws Exception {
        mvc.perform(put("/api/v1/admin/packs/samples").header("Authorization", as("ann", "author")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"hidden\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/packs/samples").header("Authorization", as("drishti-dev-admin", "admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"sometimes\"}")).andExpect(status().isBadRequest());
    }
}
