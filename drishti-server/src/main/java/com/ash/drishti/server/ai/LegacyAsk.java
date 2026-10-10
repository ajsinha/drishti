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

import com.ash.drishti.ai.ProviderConfig;
import com.ash.drishti.server.explain.AskProperties;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ask's original settings ({@code drishti.explain.ask.endpoint}, {@code api}, {@code model}, {@code api-key}) as a provider
 * of the AI layer named {@code ask}, so existing configurations keep working. Deprecated: new set-ups write a provider file.
 */
public final class LegacyAsk {

    public static final String PROVIDER = "ask";
    public static final String MODEL = "ask";

    private LegacyAsk() {}

    /** Whether Ask still carries its own endpoint. */
    public static boolean configured(AskProperties a) {
        return !a.endpoint().isBlank();
    }

    public static ProviderConfig provider(AskProperties a) {
        URI u = URI.create(a.endpoint());
        String base = u.getScheme() + "://" + u.getAuthority();
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        Map<String, String> settings = new LinkedHashMap<>();
        boolean anthropic = "anthropic".equals(a.api());
        settings.put(anthropic ? "messages-path" : "completions-path", path);
        if (anthropic) {
            settings.put("anthropic-version", a.anthropicVersion());
        }
        return new ProviderConfig(PROVIDER, anthropic ? "anthropic" : "openai", true, base, a.apiKey(), a.timeout(), 0, MODEL, settings,
                List.of(new ProviderConfig.ModelConfig(MODEL, a.model().isEmpty() ? "default" : a.model(), "chat", null, null, Set.of(), 0, 0)));
    }
}
