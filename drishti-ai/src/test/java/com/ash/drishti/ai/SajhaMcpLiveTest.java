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
package com.ash.drishti.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs only when {@code DRISHTI_SAJHA_MCP_URL} (for example http://127.0.0.1:18991/mcp) and {@code DRISHTI_SAJHA_MCP_KEY} name a running
 * SAJHA MCP server: lists its tools and calls one harmless calculator. Skipped otherwise, so the build never needs it
 * (docs/architecture/MCP_CLIENT.md, "Connecting to SAJHA").
 */
class SajhaMcpLiveTest {

    @TempDir Path dir;

    @Test
    void listsToolsAndCallsAHarmlessOne() throws Exception {
        String url = System.getenv("DRISHTI_SAJHA_MCP_URL");
        assumeTrue(url != null && !url.isBlank(), "no SAJHA server named");
        Files.writeString(dir.resolve("sajha.yaml"), "transport: streamable-http\nurl: " + url + "\ntimeout: 20s\nheaders:\n  X-API-Key: ${DRISHTI_SAJHA_MCP_KEY}\n");
        McpRegistry reg = new McpRegistry(McpRegistry.discover(java.util.List.of()), () -> McpServerConfigLoader.load(dir, System::getenv));
        reg.reload();
        McpClient c = reg.server("sajha").orElseThrow().client();
        var tools = c.tools();
        System.out.println("SAJHA tools: " + tools.size() + ", e.g. " + tools.stream().limit(5).map(McpTypes.Tool::name).toList());
        assertThat(tools).extracting(McpTypes.Tool::name).contains("calc_percentage_change");
        McpTypes.Result r = c.callTool("calc_percentage_change", Map.of("old_value", 80, "new_value", 100));
        System.out.println("calc_percentage_change -> " + r.text().replaceAll("\\s+", " "));
        assertThat(r.error()).isFalse();
        assertThat(r.text()).contains("25");
        c.close();
    }
}
