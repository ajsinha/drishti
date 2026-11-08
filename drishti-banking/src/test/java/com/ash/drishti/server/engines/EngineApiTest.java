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
package com.ash.drishti.server.engines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An engine switched off and on over the API, on a whole server (ADMIN_GUIDE.md sections 5, 6, 8 and 11): the engines' files are the
 * jar's copies (no site folder in the test's working directory), so a change is live; off, the engine's API answers 404 DRS-1020 naming it
 * and the about answer says it runs nowhere; a dependency refuses with words (409 DRS-1021); only an administrator may change an engine;
 * the change is written to the engine's file in the engines folder and kept in History.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.identity.iterations=1000",
        "drishti.engines.dir=${drishti.test.dir}/engines-${random.uuid}", "drishti.engines.watch-seconds=0", "drishti.bi.dir=../config/bi", "drishti.tables.root=../config/tables",
        "drishti.bi.query.store-dir=${drishti.test.dir}/engines-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class EngineApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired EngineRegistry registry;

    private String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    private org.springframework.test.web.servlet.ResultActions change(String admin, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/engines").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void anEngineTurnedOffAnswers404InWordsAndOnAgainAnswers() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        mvc.perform(get("/public/about")).andExpect(status().isOk())
                .andExpect(jsonPath("$.engines[?(@.id == 'frtb-sa')].here").value(true));
        mvc.perform(get("/api/v1/admin/engines").header("Authorization", as("tina", "trader"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/engines").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.engines[?(@.id == 'var')].state").value("running"))
                .andExpect(jsonPath("$.engines[?(@.id == 'var')].source").value("shipped copy"))
                .andExpect(jsonPath("$.engines[?(@.id == 'frtb-sa')].needs[0]").value("var"));

        change(admin, "{\"reason\":\"moved\",\"changes\":[{\"engine\":\"var\",\"on\":false}]}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRS-1021"))
                .andExpect(jsonPath("$.detail").value(containsString("VaR is needed by FRTB-SA and MR cube on this server. Turn them off first, or all three together.")));
        change(admin, "{\"reason\":\"\",\"changes\":[{\"engine\":\"frtb-sa\",\"on\":false}]}").andExpect(status().isBadRequest());
        try {
            change(admin, "{\"reason\":\"capital moves to node var-1\",\"changes\":[{\"engine\":\"frtb-sa\",\"on\":false}]}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcomes[0].outcome").value("live"));
            mvc.perform(get("/api/v1/risk/frtb/sa").param("scope", "EXAMPLE").header("Authorization", as("tina", "trader")))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-1020")).andExpect(jsonPath("$.engine").value("frtb-sa"))
                    .andExpect(jsonPath("$.detail").value(containsString("FRTB-SA is not running on this server, and no other server of this cluster runs it")));
            mvc.perform(get("/api/v1/risk/sensitivities").param("scope", "BANK").header("Authorization", as("tina", "trader")))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("DRS-1020"));
            mvc.perform(get("/public/about")).andExpect(jsonPath("$.engines[?(@.id == 'frtb-sa')].cluster").value(false));
            String file = Files.readString(registry.files().file(registry.entry("frtb-sa")));
            assertThat(file).contains("enabled: ${DRISHTI_FRTB_ENABLED:false}").contains("by: \"drishti-dev-admin\"")
                    .contains("reason: \"capital moves to node var-1\"").contains("default-risk-kind: default-risk");
        } finally {
            change(admin, "{\"reason\":\"back\",\"changes\":[{\"engine\":\"frtb-sa\",\"on\":true}]}").andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/risk/frtb/sa").param("scope", "EXAMPLE").header("Authorization", as("tina", "trader")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("DRS-1020"));
        mvc.perform(get("/api/v1/admin/engines/history").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].engine").value("frtb-sa")).andExpect(jsonPath("$.changes[0].change").value("on"))
                .andExpect(jsonPath("$.changes[1].why").value("capital moves to node var-1"))
                .andExpect(jsonPath("$.changes[2].outcome").value("refused"));
        mvc.perform(get("/api/v1/admin/engines/frtb-sa/impact").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hides.words[0]").value("FRSA")).andExpect(jsonPath("$.hides.menus[1]").value("play-frtb"));
    }

    @Test
    void varOffWithItsDependentsHidesItsBiDatasetsAndItsApi() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        String reader = as("tina", "trader");
        mvc.perform(get("/api/v1/bi/items/file:table:banking.market_risk_var_latest").header("Authorization", admin)).andExpect(status().isOk());
        try {
            change(admin, "{\"reason\":\"a counterparty node\",\"withDependents\":true,\"changes\":[{\"engine\":\"var\",\"on\":false}]}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.outcomes[*].engine", containsInAnyOrder("frtb-sa", "mr-cube", "var")))
                    .andExpect(jsonPath("$.outcomes[2].engine").value("var"));                // the dependents first, then var
            mvc.perform(get("/api/v1/risk/var/status").header("Authorization", reader)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DRS-1020")).andExpect(jsonPath("$.engine").value("var"));
            mvc.perform(get("/api/v1/bi/items/file:table:banking.market_risk_var_latest").header("Authorization", admin)).andExpect(status().isNotFound());
        } finally {
            change(admin, "{\"reason\":\"back\",\"withNeeds\":true,\"changes\":[{\"engine\":\"frtb-sa\",\"on\":true},{\"engine\":\"mr-cube\",\"on\":true}]}")
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/bi/items/file:table:banking.market_risk_var_latest").header("Authorization", admin)).andExpect(status().isOk());
        assertThat(registry.running()).contains("var", "frtb-sa", "mr-cube");
    }
}
