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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code type: groq | together | fireworks | deepseek | xai | openrouter | perplexity | huggingface | vllm | lmstudio | mistral | openai_compatible}: the OpenAI
 * Chat Completions wire format on the JDK client, with the presets of {@code sajha/ai/llm/providers/openai_compat.py}. A preset is a base URL, the environment
 * variable a key is read from and a model catalogue ({@code ai/llm_catalog.json}); everything else is this class. Settings: {@code base-url}, {@code completions-path} /
 * {@code chat-path} (/chat/completions), {@code embeddings-path}, {@code max-tokens-param} ({@code max_tokens}, or {@code max_completion_tokens}), {@code live-models}
 * (list the server's own models with {@code GET models-path}; on by default for the keyless presets), {@code models-path} (/models), {@code header.<Name>}, {@code proxy.*},
 * {@code tls.*}, {@code retries}. The Azure variant is {@link AzureOpenAiProvider}.
 */
public class OpenAiCompatProvider extends HttpProvider {

    protected final JsonNode preset;
    private final String chatPath;
    private final String embedPath;
    private final String tokensParam;
    private final String modelsPath;

    OpenAiCompatProvider(ProviderConfig c) {
        this(c, "/chat/completions", "/embeddings", "max_tokens");
    }

    protected OpenAiCompatProvider(ProviderConfig c, String chat, String embed, String tokens) {
        super(c, requireBase(c), Set.of(Capability.TOOL_CALLS, Capability.JSON_MODE));
        this.preset = LlmCatalog.preset(c.type());
        this.chatPath = c.setting("completions-path", c.setting("chat-path", chat));
        this.embedPath = c.setting("embeddings-path", embed);
        this.tokensParam = c.setting("max-tokens-param", preset != null && preset.has("max-tokens-param") ? preset.get("max-tokens-param").asText() : tokens);
        this.modelsPath = c.setting("models-path", "/models");
    }

    private static String requireBase(ProviderConfig c) {
        if (c.baseUrl().isEmpty()) {
            throw new LlmException(LlmException.Kind.CONFIG, "base-url is required for this type (the server's address, for example http://localhost:8000/v1)");
        }
        return c.baseUrl();
    }

    protected Map<String, String> authHeaders() {
        return config.apiKey().isEmpty() ? Map.of() : Map.of("Authorization", "Bearer " + config.apiKey());
    }

    protected String chatUrl(ModelInfo info) {
        return http.base() + chatPath;
    }

    protected String embeddingsUrl(ModelInfo info) {
        return http.base() + embedPath;
    }

    protected String wireId(ModelInfo info) {
        return info.id();
    }

    private boolean keyless() {
        return preset == null || !preset.path("requires-key").asBoolean(true);
    }

    @Override
    public Readiness check() {
        if (!keyless() && config.apiKey().isEmpty()) {
            StringBuilder names = new StringBuilder();
            preset.path("key-env").forEach(n -> names.append(names.isEmpty() ? "" : " or ").append(n.asText()));
            return new Readiness(false, "no api key: set " + (names.isEmpty() ? "api-key" : names) + " (or api-key: ${ENV_NAME})");
        }
        return new Readiness(true, "configured; no call is made to find out");
    }

    @Override
    public Readiness probe() {
        Readiness r = check();
        if (!r.ok()) {
            return r;
        }
        try {
            http.call("GET", http.base() + modelsPath, null, authHeaders(), java.time.Duration.ofSeconds(5));
            return new Readiness(true, "the model list answered");
        } catch (LlmException e) {
            return new Readiness(false, e.getMessage());
        }
    }

    @Override
    public List<LiveModel> liveModels() {
        String live = config.setting("live-models", "");
        boolean on = live.isEmpty() ? keyless() && preset != null : "true".equalsIgnoreCase(live);
        if (!on) {
            return List.of();
        }
        JsonNode j = http.call("GET", http.base() + modelsPath, null, authHeaders(), java.time.Duration.ofSeconds(5));
        List<LiveModel> out = new ArrayList<>();
        for (JsonNode m : j.path("data")) {
            String id = m.path("id").asText("");
            if (!id.isEmpty()) {
                out.add(new LiveModel(id, id.contains("embed") ? "embedding" : "chat"));
            }
        }
        return out;
    }

    @Override
    protected ChatResponse chat(ModelInfo info, ChatRequest r) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        body.put("model", wireId(info));
        ArrayNode msgs = body.putArray("messages");
        String sys = systemText(r);
        if (!sys.isEmpty()) {
            msgs.addObject().put("role", "system").put("content", sys);
        }
        for (ChatMessage m : turns(r)) {
            msgs.addObject().put("role", assistant(m) ? "assistant" : "user").put("content", m.content());
        }
        body.put(tokensParam, maxTokens(r, info, 1024));
        body.put("stream", false);
        Double t = temperature(r, info);
        if (t != null) {
            body.put("temperature", t);
        }
        if (r.jsonMode()) {
            body.putObject("response_format").put("type", "json_object");
        }
        if (!r.tools().isEmpty()) {
            body.set("tools", functionTools(r));
        }
        JsonNode j = http.call("POST", chatUrl(info), body, requestHeaders(), timeout(r));
        JsonNode choice = j.path("choices").path(0);
        JsonNode msg = choice.path("message");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : msg.path("tool_calls")) {
            calls.add(new ToolCall(c.path("id").asText(""), c.path("function").path("name").asText(""), c.path("function").path("arguments").asText("{}")));
        }
        if (!msg.path("content").isTextual() && calls.isEmpty()) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint's answer has no message content");
        }
        int in = j.path("usage").path("prompt_tokens").asInt(0);
        int out = j.path("usage").path("completion_tokens").asInt(0);
        return new ChatResponse(msg.path("content").asText(""), calls, j.path("model").asText(info.id()), choice.path("finish_reason").asText(""),
                new Usage(in, out, info.cost(in, out)));
    }

    /** Authentication plus the file's headers, per request (Azure's Entra token changes). */
    protected Map<String, String> requestHeaders() {
        return new LinkedHashMap<>(authHeaders());
    }

    @Override
    protected List<float[]> embed(ModelInfo info, List<String> texts) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        body.put("model", wireId(info));
        ArrayNode in = body.putArray("input");
        texts.forEach(in::add);
        JsonNode j = http.call("POST", embeddingsUrl(info), body, requestHeaders(), null);
        List<JsonNode> rows = new ArrayList<>();
        j.path("data").forEach(rows::add);
        rows.sort(java.util.Comparator.comparingInt(n -> n.path("index").asInt(0)));
        ArrayNode arrays = JsonHttp.JSON.createArrayNode();
        rows.forEach(n -> arrays.add(n.path("embedding")));
        return vectors(arrays, texts.size());
    }

    /** One factory per OpenAI-compatible type. */
    public static ProviderFactory factory(String type) {
        return new ProviderFactory() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public LlmProvider create(ProviderConfig config) {
                return new OpenAiCompatProvider(config);
            }
        };
    }
}
