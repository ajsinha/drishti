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
package com.ash.drishti.api.proxy;

import java.util.Map;

/**
 * The site-wide outbound proxy ({@code drishti.http.proxy.*} in {@code application.yaml}) that every HTTP-based connector, AI
 * provider and MCP client inherits unless it sets its own {@code proxy:} or {@code proxy: none}. The server hands it over once at
 * start (and again if the configuration is refreshed); {@link ProxySettings#resolve} reads it. The keys are the connector's,
 * under {@code proxy.} (url, username, password, no-proxy, enabled).
 *
 * <p>It is process state because the plugin SPI is Spring-free and a plugin starts with only its own settings: the one place
 * the site default can live without every plugin learning about the server's configuration.
 */
public final class SiteProxy {

    private record State(Map<String, String> settings, boolean fromEnvironment) {}

    private static volatile State state = new State(Map.of(), false);

    private SiteProxy() {}

    /**
     * Sets the site default.
     *
     * @param settings the {@code proxy.*} keys (empty for none); an empty {@code proxy.url} means no site proxy
     * @param fromEnvironment honour {@code HTTPS_PROXY}, {@code HTTP_PROXY} and {@code NO_PROXY} when no proxy is configured
     */
    public static void configure(Map<String, String> settings, boolean fromEnvironment) {
        state = new State(Map.copyOf(settings), fromEnvironment);
        ProxySettings.allowBasicThroughTunnels();
    }

    /** Back to no site default (tests). */
    public static void reset() {
        state = new State(Map.of(), false);
    }

    static Map<String, String> settings() {
        return state.settings();
    }

    static boolean fromEnvironment() {
        return state.fromEnvironment();
    }
}
