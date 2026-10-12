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
package com.ash.drishti.ai.llm;

import java.util.Set;

/** What a model declares it can do (the console's {@code ModelCapabilities}; costs are per million tokens, the unit sajhamcpserver uses). */
public record ModelCapabilities(boolean chat, boolean tools, boolean structuredOutput, boolean vision, boolean streaming, boolean embedding, int contextWindow,
        int maxOutputTokens, double inputCostPerMtok, double outputCostPerMtok, int dimensions, boolean temperature, boolean forcedToolChoice, Set<String> tags,
        Boolean jsonMode) {

    public ModelCapabilities {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    public static ModelCapabilities chatModel() {
        return new ModelCapabilities(true, false, false, false, true, false, 0, 4096, 0, 0, 0, true, true, Set.of(), null);
    }

    public double cost(int inputTokens, int outputTokens) {
        return (inputTokens * inputCostPerMtok + outputTokens * outputCostPerMtok) / 1_000_000.0;
    }
}
