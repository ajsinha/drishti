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
import java.util.Set;

/**
 * One provider file, parsed. {@code settings} carries every other key flat (for example {@code completions-path}, and the
 * shared {@code tls.*} keys), the way connector settings do. The API key is already resolved from its {@code ${ENV}}
 * placeholder and is never part of {@link #toString()}.
 *
 * @param name the provider name (the file name without its extension, unless the file says {@code name:})
 * @param defaultModel the logical chat model used when a call names none
 */
public record ProviderConfig(String name, String type, boolean enabled, String baseUrl, String apiKey, Duration timeout, int retries,
        String defaultModel, Map<String, String> settings, List<ModelConfig> models) {

    /** One model entry. */
    public record ModelConfig(String name, String id, String kind, Integer maxTokens, Double temperature, Set<Capability> capabilities,
            double costPer1kInput, double costPer1kOutput) {

        public ModelConfig {
            kind = kind == null || kind.isBlank() ? "chat" : kind.strip().toLowerCase(java.util.Locale.ROOT);
            capabilities = capabilities == null || capabilities.isEmpty()
                    ? ("embedding".equals(kind) ? Set.of(Capability.EMBEDDINGS) : Set.of(Capability.CHAT)) : Set.copyOf(capabilities);
        }
    }

    public ProviderConfig {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        apiKey = apiKey == null ? "" : apiKey;
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(60) : timeout;
        retries = Math.max(0, retries);
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        models = models == null ? List.of() : List.copyOf(models);
    }

    public String setting(String key, String dflt) {
        String v = settings.get(key);
        return v == null || v.isBlank() ? dflt : v;
    }

    @Override
    public String toString() {
        return "ProviderConfig[" + name + ", type=" + type + ", enabled=" + enabled + ", baseUrl=" + baseUrl + ", apiKey="
                + (apiKey.isEmpty() ? "none" : "set") + ", models=" + models.size() + "]";
    }
}
