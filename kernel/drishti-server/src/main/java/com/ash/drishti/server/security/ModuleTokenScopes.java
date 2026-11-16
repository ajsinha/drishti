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
package com.ash.drishti.server.security;

import com.ash.drishti.packs.Modules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.core.env.Environment;

/**
 * What a module adds to the personal API tokens' declarations (docs/architecture/DOMAIN_MODULES.md section 8): each module's jar may ship
 * {@code META-INF/drishti/token-scopes.yaml} ({@code module:}, {@code read-posts:} and {@code scopes:}, the same shapes as
 * {@code drishti.security.token-read-posts} and {@code token-scopes}). While the module is on, its read-only POSTs join the core's and its
 * scopes are offered (a scope the core also names gets the module's patterns added); while it is off, none of them exists. Spring cannot
 * split one list across files, so the module's entries live in its own file rather than in {@code application.yaml}.
 */
public final class ModuleTokenScopes {

    /** The file a module's jar ships. */
    public static final String RESOURCE = "META-INF/drishti/token-scopes.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ModuleTokenScopes() {
    }

    /** The settings with the entries of every module that is on in this environment. */
    public static SecurityProperties merged(SecurityProperties props, Environment env) {
        var registry = env == null ? null : Modules.of(env);
        Predicate<String> on = id -> registry != null && registry.module(id).map(s -> s.on()).orElse(false);
        return merged(props, on, ModuleTokenScopes.class.getClassLoader());
    }

    /** The settings with the entries of the modules {@code on} accepts, from the files {@code loader} finds. */
    public static SecurityProperties merged(SecurityProperties props, Predicate<String> on, ClassLoader loader) {
        List<String> readPosts = new ArrayList<>(props.tokenReadPosts());
        Map<String, SecurityProperties.TokenScope> scopes = new LinkedHashMap<>(props.tokenScopes());
        try {
            for (URL u : Collections.list(loader.getResources(RESOURCE))) {
                JsonNode root;
                try (InputStream in = u.openStream()) {
                    root = YAML.readTree(in);
                }
                if (root == null || !on.test(root.path("module").asText(""))) {
                    continue;
                }
                root.path("read-posts").forEach(p -> readPosts.add(p.asText()));
                root.path("scopes").fields().forEachRemaining(e -> {
                    List<String> allow = new ArrayList<>();
                    e.getValue().path("allow").forEach(p -> allow.add(p.asText()));
                    String description = e.getValue().path("description").asText("");
                    scopes.merge(e.getKey(), new SecurityProperties.TokenScope(description, allow), (core, mod) -> {
                        List<String> both = new ArrayList<>(core.allow());
                        both.addAll(mod.allow());
                        return new SecurityProperties.TokenScope(core.description().isEmpty() ? mod.description() : core.description(), both);
                    });
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the modules' " + RESOURCE, e);
        }
        return props.withTokens(readPosts, scopes);
    }
}
