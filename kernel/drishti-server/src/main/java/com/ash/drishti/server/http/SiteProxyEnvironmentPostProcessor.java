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
package com.ash.drishti.server.http;

import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.api.proxy.SiteProxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Hands the site-wide outbound HTTP proxy ({@code drishti.http.proxy.*}) to the shared {@link SiteProxy} before any bean is built,
 * so the first connector, AI provider or MCP client to start already sees it. Off by default: with no {@code url} (and
 * {@code from-environment} false) nothing changes.
 *
 * <ul>
 *   <li>{@code url}: {@code http://host:port}; {@code username}; {@code password} (an {@code ${ENV}} placeholder, or the
 *       environment variable {@code DRISHTI_HTTP_PROXY_PASSWORD} itself; a literal in a configuration file is refused at start);
 *       {@code password-file}; {@code no-proxy} (a list or a comma list); {@code enabled} (false switches it off without removing it);
 *   <li>{@code from-environment}: when no {@code url} is set, honour the standard {@code HTTPS_PROXY}, {@code HTTP_PROXY} and
 *       {@code NO_PROXY} environment variables (default false).
 * </ul>
 * The value is read once at start; changing it needs a restart (a connector's own {@code proxy:} block is live-reloaded like any
 * other connector setting).
 */
public final class SiteProxyEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PREFIX = "drishti.http.proxy.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        refuseLiteralPassword(env);
        Map<String, String> s = new LinkedHashMap<>();
        for (String key : List.of("url", "username", "password", "password-file", "enabled")) {
            String v = env.getProperty(PREFIX + key);
            if (v != null && !v.isBlank()) {
                s.put("proxy." + key, v.strip());
            }
        }
        List<String> skip = Binder.get(env).bind(PREFIX + "no-proxy", Bindable.listOf(String.class)).orElse(List.of());
        if (!skip.isEmpty()) {
            s.put("proxy.no-proxy", String.join(",", skip));
        }
        boolean fromEnv = Boolean.parseBoolean(env.getProperty(PREFIX + "from-environment", "false"));
        SiteProxy.configure(s, fromEnv);
        if (!s.isEmpty() || fromEnv) {
            ProxySettings p = ProxySettings.resolve(Map.of(), "https://site-default.invalid");   // fails the start on a malformed setting
            System.getLogger(SiteProxyEnvironmentPostProcessor.class.getName()).log(System.Logger.Level.INFO,
                    "site HTTP proxy: {0}", p.active() ? p.toString() : fromEnv ? "from the environment (HTTPS_PROXY / HTTP_PROXY / NO_PROXY), none set" : "off");
        }
    }

    /** A password written in application.yaml is a secret in a file: only a placeholder is allowed (environment variables are fine). */
    private static void refuseLiteralPassword(ConfigurableEnvironment env) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (!(ps instanceof EnumerablePropertySource<?>) || !ps.getName().startsWith("Config resource")) {
                continue;
            }
            Object raw = ps.getProperty(PREFIX + "password");
            if (raw instanceof String t && !t.isBlank() && !t.strip().matches("\\$\\{[A-Za-z_][A-Za-z0-9_.]*(:[^}]*)?}")) {
                throw new IllegalStateException(PREFIX + "password must be a ${ENV_VAR} placeholder (or set " + PREFIX
                        + "password-file); passwords are never written into a configuration file");
            }
        }
    }
}
