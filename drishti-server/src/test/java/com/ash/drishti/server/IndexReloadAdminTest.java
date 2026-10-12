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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Admin &rarr; Connectors &rarr; Index: status, reload, audit, and who may. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.sources.connectors.finance-lake.settings.root=../plugins/drishti-plugin-delta/src/test/resources/lake",
        "drishti.sources.connectors.finance-lake.settings.domain=desk", "drishti.sources.connectors.finance-lake.settings.index.days=5"})
@AutoConfigureMockMvc
class IndexReloadAdminTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    @Test
    void anAdminSeesTheIndexReloadsADayAndTheAuditNamesWhoAndWhat() throws Exception {
        String admin = "Bearer " + tokens.mint("ada", List.of("admin"), 300);
        mvc.perform(get("/api/v1/views/trade/T-1").header("Authorization", admin).header("X-Drishti-As-Of", "2026-09-29"));
        mvc.perform(get("/api/v1/admin/connectors/finance-lake/index").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.auto.everySeconds").value(600));
        mvc.perform(post("/api/v1/admin/connectors/finance-lake/index/reload").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"not-a-date\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/connectors/finance-lake/index/reload").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"no-such-kind\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/connectors/finance-lake/index/reload").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"all\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.queued").isNumber());
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", admin)).andExpect(jsonPath("$[*].action").value(hasItem("index-reload")));
        mvc.perform(post("/api/v1/admin/connectors/nope/index/reload").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isNotFound());
    }

    @Test
    void othersMayNeitherSeeNorReload() throws Exception {
        String viewer = "Bearer " + tokens.mint("vic", List.of("viewer"), 300);
        mvc.perform(get("/api/v1/admin/connectors/finance-lake/index").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/connectors/finance-lake/index/reload").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
    }
}
