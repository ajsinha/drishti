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
package com.ash.drishti.server.cube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The MR cube switched off ({@code drishti.cube.mr.enabled: false}): nothing of it is built (no source, no MR endpoints), and every cube
 * endpoint answers {@code 404 DRS-5603} in words, the generic ones and the MR cube's own alike.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance", "drishti.sources.plugins.demo.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false", "drishti.var.official.on-start=false", "drishti.var.official.publish=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.cube.mr.enabled=false",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class CubeOffTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired ApplicationContext ctx;
    final ObjectMapper json = new ObjectMapper();

    private void off(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(404);
        JsonNode b = json.readTree(r.getResponse().getContentAsString());
        assertThat(b.get("code").asText()).isEqualTo("DRS-5603");
        assertThat(b.get("detail").asText()).contains("MR cube is not running on this server");
    }

    @Test
    void nothingOfTheMrCubeIsBuiltAndItsApiSaysItIsOff() throws Exception {
        assertThat(ctx.getBeanNamesForType(com.ash.drishti.server.var.VarCubeSource.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(com.ash.drishti.server.var.VarCubeController.class)).isEmpty();
        String auth = "Bearer " + tokens.mint("rita", List.of("risk"), 300);
        off(mvc.perform(post("/api/v1/cube/mr/query").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"BANK\",\"rows\":[\"DESK\"]}")).andReturn());
        off(mvc.perform(get("/api/v1/cube/mr/versions").header("Authorization", auth)).andReturn());
        off(mvc.perform(get("/api/v1/cube/mr/sensitivities").header("Authorization", auth)).andReturn());
        JsonNode cubes = json.readTree(mvc.perform(get("/api/v1/cube").header("Authorization", auth)).andReturn().getResponse().getContentAsString());
        assertThat(cubes.get("cubes")).isEmpty();
        assertThat(cubes.get("mrEnabled").asBoolean()).isFalse();
    }
}
