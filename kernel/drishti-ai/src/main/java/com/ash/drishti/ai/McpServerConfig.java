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

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One {@code config/mcp/<name>.yaml}. {@code headers} hold the authentication (each value an {@code ${ENV}} placeholder,
 * resolved here, never printed); {@code allowedTools} empty = every tool the server lists; {@code settings} carry the rest
 * flat (the shared {@code tls.*} keys, mock scripts).
 */
public record McpServerConfig(String name, String transport, boolean enabled, String url, Map<String, String> headers, Duration timeout,
        List<String> allowedTools, Map<String, String> settings) {

    public McpServerConfig {
        url = url == null ? "" : url.strip();
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(30) : timeout;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    public boolean toolAllowed(String tool) {
        return allowedTools.isEmpty() || allowedTools.contains(tool);
    }

    @Override
    public String toString() {
        return "McpServerConfig[" + name + ", transport=" + transport + ", enabled=" + enabled + ", url=" + url + ", headers="
                + headers.keySet() + "]";
    }
}
