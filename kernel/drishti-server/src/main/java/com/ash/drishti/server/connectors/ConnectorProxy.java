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
package com.ash.drishti.server.connectors;

import com.ash.drishti.api.proxy.ProxyException;
import com.ash.drishti.api.proxy.ProxySettings;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * What Admin &rarr; Connectors shows about a connector's outbound proxy: the effective one (the connector's own {@code proxy:}
 * block, the site default {@code drishti.http.proxy.*}, or the {@code HTTPS_PROXY} / {@code HTTP_PROXY} environment), where it came
 * from, and whether the connector's target goes through it or is on the no-proxy list. The password is never in the result.
 */
final class ConnectorProxy {

    /** The settings that name the address a connector talks to, in the order they are tried. */
    private static final List<String> TARGET_KEYS = List.of("base-url", "url", "endpoint", "s3.endpoint", "uri", "schema-registry.url");

    private ConnectorProxy() {}

    /**
     * The summary for a connector of a plugin that speaks HTTP, or null when the plugin has no proxy section.
     *
     * @param settings the connector's settings as written (placeholders allowed)
     * @param resolve resolves {@code ${ENV}} placeholders, throwing {@link IllegalArgumentException} for an unset variable
     */
    static Map<String, Object> summary(boolean pluginTakesProxy, Map<String, String> settings, UnaryOperator<String> resolve) {
        if (!pluginTakesProxy) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> read = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> e : settings.entrySet()) {
                if (e.getKey().equals("proxy") || e.getKey().startsWith("proxy.") || TARGET_KEYS.contains(e.getKey())) {
                    String v = e.getValue();
                    read.put(e.getKey(), v == null ? "" : e.getKey().endsWith("-file") ? v : resolve.apply(v));
                }
            }
        } catch (IllegalArgumentException x) {
            out.put("error", "a proxy setting refers to an environment variable that is not set (" + x.getMessage() + ")");
            return out;
        }
        String target = targetOf(read);
        try {
            ProxySettings p = ProxySettings.resolve(read, target);
            out.putAll(p.describe());
            String host = hostOf(target);
            out.put("target", host);
            out.put("viaProxy", p.appliesTo(host));
            if (p.active() && !p.appliesTo(host)) {
                out.put("note", "the proxy is configured but " + host + " is on the no-proxy list, so this connector goes direct");
            }
        } catch (ProxyException x) {
            out.put("error", x.getMessage());
        }
        return out;
    }

    static String targetOf(Map<String, String> settings) {
        for (String k : TARGET_KEYS) {
            String v = settings.get(k);
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    private static String hostOf(String target) {
        try {
            return target == null ? null : URI.create(target).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
