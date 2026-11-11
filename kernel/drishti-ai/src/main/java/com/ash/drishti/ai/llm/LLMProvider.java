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

import java.util.List;
import java.util.Map;

/**
 * A vendor or runtime: a factory for its {@link LLMModel}s, its catalogue, health, configuration and close. The same design as {@code LLMProvider} in the console's
 * {@code core/ai/llm/base.py} (sajhamcpserver's {@code sajha/ai/llm/base.py}). Python's {@code from_settings} classmethod is {@link LLMProviderFactory#fromSettings}
 * here (an interface cannot require a static method), found by type through {@link LLMProviders}.
 */
public interface LLMProvider extends AutoCloseable {

    /** The provider's name (its file name): the first half of {@code provider/model}. */
    String name();

    /** The provider type that built it ({@code openai}, {@code anthropic}, {@code mock}). */
    String type();

    /** Enabled, and built without error. */
    boolean active();

    /** Every model of this provider (like {@code GET /v1/models}). */
    List<ModelInfo> models();

    /** A model by id; {@code kind} (chat or embedding) defaults to what the catalogue says. */
    LLMModel model(String name, String kind);

    default LLMModel model(String name) {
        return model(name, "");
    }

    /** A chat model (the provider's default when {@code modelId} is empty). {@code options} are provider-specific. */
    LLMModel chatModel(String modelId, Map<String, Object> options);

    default LLMModel chatModel(String modelId) {
        return chatModel(modelId, Map.of());
    }

    /** An embedding model (the provider's default when {@code modelId} is empty). */
    LLMModel embeddingModel(String modelId);

    /** Cheap and bounded: no call is made. */
    HealthStatus health();

    /** The effective configuration with secrets masked. */
    Map<String, Object> describeConfig();

    @Override
    void close();
}
