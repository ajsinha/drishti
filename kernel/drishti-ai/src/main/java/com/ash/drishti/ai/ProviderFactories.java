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

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** The provider types installed: those listed in {@code META-INF/services/com.ash.drishti.ai.ProviderFactory}, then any given. */
public final class ProviderFactories {

    private ProviderFactories() {}

    /** The OpenAI-compatible types served by {@link OpenAiCompatProvider}: the presets of sajhamcpserver, Mistral and the generic one. */
    static final List<String> OPENAI_COMPATIBLE = List.of("openai_compatible", "groq", "together", "fireworks", "deepseek", "xai", "openrouter", "perplexity",
            "huggingface", "vllm", "lmstudio", "mistral");

    public static List<ProviderFactory> discover(List<ProviderFactory> extra) {
        List<ProviderFactory> all = new ArrayList<>(extra);
        for (ProviderFactory f : ServiceLoader.load(ProviderFactory.class)) {
            all.add(f);
        }
        OPENAI_COMPATIBLE.forEach(t -> all.add(OpenAiCompatProvider.factory(t)));
        all.add(AzureOpenAiProvider.azureFactory());
        return all;
    }
}
