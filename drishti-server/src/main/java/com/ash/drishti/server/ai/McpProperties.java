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

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.mcp.*}: the MCP client layer (docs/architecture/MCP_CLIENT.md). Off by default; while off no file in {@code dir}
 * is read and no server is contacted.
 *
 * @param serverRoles roles allowed per server name (absent = every signed-in user)
 * @param toolRoles roles allowed per {@code server/tool}
 * @param serverFeatures features allowed per server
 */
@ConfigurationProperties("drishti.mcp")
public record McpProperties(Boolean enabled, String dir, String watch, Long pollMs, Boolean audit, Map<String, List<String>> serverRoles,
        Map<String, List<String>> toolRoles, Map<String, List<String>> serverFeatures) {

    public McpProperties {
        enabled = enabled != null && enabled;
        dir = dir == null || dir.isBlank() ? "./config/mcp" : dir.strip();
        watch = watch == null || watch.isBlank() ? "auto" : watch.strip();
        pollMs = pollMs == null || pollMs <= 0 ? 5000 : pollMs;
        audit = audit == null || audit;
        serverRoles = serverRoles == null ? Map.of() : Map.copyOf(serverRoles);
        toolRoles = toolRoles == null ? Map.of() : Map.copyOf(toolRoles);
        serverFeatures = serverFeatures == null ? Map.of() : Map.copyOf(serverFeatures);
    }
}
