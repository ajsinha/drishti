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

import com.ash.drishti.ai.LlmException;
import com.ash.drishti.ai.ProviderConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements {@link LLMProvider} over a wire-level provider ({@code com.ash.drishti.ai.LlmProvider}: the OpenAI-compatible (Spring AI), Anthropic and mock classes,
 * whose HTTP code is unchanged). The wire call is translated to and from the canonical types by {@link Convert}. Mirrors {@code ProviderBase} in {@code core/ai/llm/provider.py}.
 */
public class ProviderBase implements LLMProvider {

    private final ProviderConfig config;
    private final com.ash.drishti.ai.LlmProvider wire;

    public ProviderBase(ProviderConfig config, com.ash.drishti.ai.LlmProvider wire) {
        this.config = config;
        this.wire = wire;
    }

    public ProviderConfig config() {
        return config;
    }

    @Override
    public String name() {
        return config.name();
    }

    @Override
    public String type() {
        return config.type();
    }

    @Override
    public boolean active() {
        return config.enabled();
    }

    @Override
    public List<ModelInfo> models() {
        List<ModelInfo> out = new ArrayList<>();
        wire.models().forEach(m -> out.add(Convert.info(m)));
        return out;
    }

    @Override
    public LLMModel model(String name, String kind) {
        boolean embedding = "embedding".equals(kind) || (kind == null || kind.isBlank()) && models().stream().anyMatch(m -> m.id().equals(name) && "embedding".equals(m.kind()));
        return embedding ? embeddingModel(name) : chatModel(name, Map.of());
    }

    @Override
    public LLMModel chatModel(String modelId, Map<String, Object> options) {
        String id = modelId == null || modelId.isBlank() ? defaultChat() : modelId;
        com.ash.drishti.ai.LlmModel m = wire.chatModel(id).orElseThrow(() -> new LlmException(LlmException.Kind.NOT_FOUND, "provider " + name() + " has no chat model '" + id + "'"));
        ModelInfo info = Convert.info(m.info());
        return new ChatModel(info) {
            @Override
            protected ChatCompletion create(ChatCompletionRequest r) {
                long t0 = System.nanoTime();
                return Convert.fromLegacy(m.chat(Convert.toLegacy(r, info.qualifiedId(), null)), m.info(), (System.nanoTime() - t0) / 1_000_000L);
            }
        };
    }

    @Override
    public LLMModel embeddingModel(String modelId) {
        String id = modelId == null || modelId.isBlank()
                ? wire.models().stream().filter(com.ash.drishti.ai.ModelInfo::embedding).map(com.ash.drishti.ai.ModelInfo::name).findFirst().orElse("")
                : modelId;
        com.ash.drishti.ai.EmbeddingModel m = wire.embeddingModel(id)
                .orElseThrow(() -> new LlmException(LlmException.Kind.NOT_FOUND, "provider " + name() + " has no embedding model '" + id + "'"));
        return new EmbeddingModel(Convert.info(m.info())) {
            @Override
            protected List<float[]> embed(List<String> texts) {
                return m.embed(texts);
            }
        };
    }

    private String defaultChat() {
        if (!config.defaultModel().isBlank()) {
            return config.defaultModel();
        }
        return wire.models().stream().filter(m -> !m.embedding()).map(com.ash.drishti.ai.ModelInfo::name).findFirst().orElse("");
    }

    @Override
    public HealthStatus health() {
        return config.enabled() ? new HealthStatus("ok", "configured; no call is made to find out") : new HealthStatus("down", "enabled: false");
    }

    @Override
    public Map<String, Object> describeConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", config.name());
        m.put("type", config.type());
        m.put("enabled", config.enabled());
        m.put("baseUrl", config.baseUrl());
        m.put("apiKey", config.apiKey().isEmpty() ? "" : "********");
        m.put("timeoutSeconds", config.timeout().toSeconds());
        m.put("defaultModel", config.defaultModel());
        m.put("models", models().stream().map(ModelInfo::id).toList());
        return m;
    }

    @Override
    public void close() {
        wire.close();
    }
}
