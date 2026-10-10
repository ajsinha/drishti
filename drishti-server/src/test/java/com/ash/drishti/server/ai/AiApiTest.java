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
package com.ash.drishti.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.ai.LlmRegistry;
import com.ash.drishti.ai.McpRegistry;
import com.ash.drishti.server.security.TokenVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The AI layer end to end through the server with the mock provider and the mock MCP server: no network. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=market-risk", "drishti.sources.connectors-dir=target/test-data/ai-api-connectors",
        "drishti.ai.enabled=true", "drishti.ai.dir=target/test-data/ai-api/ai", "drishti.ai.default-model=mock/m", "drishti.ai.watch=off",
        "drishti.ai.policy.features.[admin-only].roles[0]=full", "drishti.security.redact[0]=salary",
        "drishti.mcp.enabled=true", "drishti.mcp.dir=target/test-data/ai-api/mcp", "drishti.mcp.watch=off",
        "drishti.mcp.tool-roles.[demo/secret]=nobody",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true", "drishti.security.roles.full.admin=true",
        "drishti.security.roles.plain.kinds[0]=*",
        "drishti.identity.database-url=jdbc:sqlite:target/aiapi-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class AiApiTest {

    @BeforeAll
    static void files() throws Exception {
        Path ai = Path.of("target/test-data/ai-api/ai");
        Path mcp = Path.of("target/test-data/ai-api/mcp");
        Files.createDirectories(ai);
        Files.createDirectories(mcp);
        Files.writeString(ai.resolve("mock.yaml"), "type: mock\nmodels:\n  - name: m\nmock:\n  rules:\n    - match: ping\n      reply: pong\n");
        Files.writeString(mcp.resolve("demo.yaml"), "transport: mock\nmock:\n  tools:\n    - name: add\n      reply: \"sum ${a}\"\n    - name: secret\n");
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired LlmRegistry registry;
    @Autowired McpRegistry mcpRegistry;

    private String auth(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    @Test
    void adminSeesProvidersModelsHealthAndMcpServersAndOthersDoNot() throws Exception {
        mvc.perform(get("/api/v1/admin/ai").header("Authorization", auth("ann", "full"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.providers[0].name").value("mock"))
                .andExpect(jsonPath("$.providers[0].models[0].ref").value("mock/m")).andExpect(jsonPath("$.providers[0].health.status").exists())
                .andExpect(jsonPath("$.mcpServers[0].name").value("demo"));
        mvc.perform(get("/api/v1/admin/ai").header("Authorization", auth("bob", "plain"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/mcp/demo/tools").header("Authorization", auth("ann", "full"))).andExpect(jsonPath("$[0].name").value("add"));
    }

    @Test
    void testCallAndFeatureApiGoThroughThePolicyAndMaskRedactedFields() throws Exception {
        mvc.perform(post("/api/v1/admin/ai/test").header("Authorization", auth("ann", "full")).contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"ping\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("pong"));
        mvc.perform(post("/api/v1/ai/chat").header("Authorization", auth("bob", "plain")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"salary: 90000 here\",\"feature\":\"x\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("echo: salary: ••• here"));
        mvc.perform(post("/api/v1/ai/chat").header("Authorization", auth("bob", "plain")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"hi\",\"model\":\"nope\"}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-4014"));
        mvc.perform(post("/api/v1/admin/ai/test").header("Authorization", auth("bob", "plain")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mcpToolCallsAreMaskedAndRoleGated() throws Exception {
        mvc.perform(post("/api/v1/ai/mcp/demo/tools/add").header("Authorization", auth("bob", "plain")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"arguments\":{\"a\":3}}")).andExpect(status().isOk()).andExpect(jsonPath("$.text").value("sum 3"));
        mvc.perform(post("/api/v1/ai/mcp/demo/tools/secret").header("Authorization", auth("bob", "plain")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-4013"));
    }

    @Test
    void liveReloadPicksUpANewProviderFile() throws Exception {
        Files.writeString(Path.of("target/test-data/ai-api/ai/extra.yaml"), "type: mock\nmodels:\n  - name: x\n");
        registry.reload();
        assertThat(registry.models()).extracting(m -> m.ref()).contains("extra/x", "mock/m");
        Files.delete(Path.of("target/test-data/ai-api/ai/extra.yaml"));
        registry.reload();
        assertThat(registry.models()).extracting(m -> m.ref()).doesNotContain("extra/x");
        assertThat(mcpRegistry.entries()).hasSize(1);
    }
}
