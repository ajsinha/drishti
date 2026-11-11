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
package com.ash.drishti.server.datamap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET /api/v1/datamap (DATAMAP): the loaded packs' kinds and contract joins for an administrator; a user whose role opens only trades sees
 * only trades, and no join to a kind they may not open. Counts and coverage follow in the background ({@code state}).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.trades-only.kinds[0]=trade",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class DataMapApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private JsonNode map(String role) throws Exception {
        String body = mvc.perform(get("/api/v1/datamap").header("Authorization", "Bearer " + tokens.mint("u-" + role, List.of(role), 300)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private static List<String> all(JsonNode list, String field) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    @Test
    void theMapIsTheCallersPacksKindsAndContractJoins() throws Exception {
        JsonNode admin = map("admin");
        assertThat(admin.path("state").asText()).isIn("counting", "checking", "ready");
        List<String> kinds = all(admin.path("kinds"), "kind");
        assertThat(kinds).contains("trade", "counterparty", "netting-set");
        admin.path("joins").forEach(j -> assertThat(kinds).contains(j.path("from").asText(), j.path("to").asText()));
        admin.path("joins").forEach(j -> assertThat(j.path("state").asText()).isIn("pending", "checked", "computed", "unchecked"));

        JsonNode mine = map("trades-only");
        assertThat(all(mine.path("kinds"), "kind")).containsExactly("trade");
        assertThat(mine.path("joins").size()).as("no join to a kind the caller may not open").isZero();
        String tradePack = "";
        for (JsonNode k : admin.path("kinds")) {
            if (k.path("kind").asText().equals("trade")) {
                tradePack = k.path("pack").asText();
            }
        }
        assertThat(all(mine.path("packs"), "name")).as("only the pack of the kinds the caller may open").containsExactly(tradePack);
    }
}
