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
 * {@code drishti.ai.*}: the LLM provider layer (docs/architecture/AI_PROVIDERS.md). Off by default: while {@code enabled} is
 * false no file in {@code dir} is read and no model is called (Ask's deprecated {@code endpoint} setting still works on its own).
 *
 * @param dir folder of provider files, one YAML per provider
 * @param watch {@code auto} (file events, then polling), {@code poll} or {@code off}: how edits to {@code dir} are noticed
 * @param defaultModel logical model used when a call names none ({@code provider/model} or a unique model name)
 */
@ConfigurationProperties("drishti.ai")
public record AiProperties(Boolean enabled, String dir, String watch, Long pollMs, String defaultModel, Policy policy) {

    /** The policy hooks around every call. */
    public record Policy(Boolean maskRedacted, List<String> maskPatterns, Boolean audit, Integer perUserPerMinute, Integer perUserPerDay,
            Double costPerUserPerDay, Map<String, Feature> features) {
        public Policy {
            maskRedacted = maskRedacted == null || maskRedacted;
            maskPatterns = maskPatterns == null ? List.of() : List.copyOf(maskPatterns);
            audit = audit == null || audit;
            perUserPerMinute = perUserPerMinute == null ? 0 : perUserPerMinute;
            perUserPerDay = perUserPerDay == null ? 0 : perUserPerDay;
            costPerUserPerDay = costPerUserPerDay == null ? 0 : costPerUserPerDay;
            features = features == null ? Map.of() : Map.copyOf(features);
        }
    }

    /** Per-feature rules: roles allowed (empty = every signed-in user) and an own per-minute limit. */
    public record Feature(List<String> roles, Integer perUserPerMinute) {
        public Feature {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    public AiProperties {
        enabled = enabled != null && enabled;
        dir = dir == null || dir.isBlank() ? "./config/ai" : dir.strip();
        watch = watch == null || watch.isBlank() ? "auto" : watch.strip();
        pollMs = pollMs == null || pollMs <= 0 ? 5000 : pollMs;
        defaultModel = defaultModel == null ? "" : defaultModel.strip();
        policy = policy == null ? new Policy(null, null, null, null, null, null, null) : policy;
    }
}
