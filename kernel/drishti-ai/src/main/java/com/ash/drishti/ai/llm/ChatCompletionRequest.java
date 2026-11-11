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

import com.ash.drishti.ai.llm.Canonical.ChatMessage;
import com.ash.drishti.ai.llm.Canonical.DrishtiRequest;
import com.ash.drishti.ai.llm.Canonical.ResponseFormat;
import com.ash.drishti.ai.llm.Canonical.ToolDefinition;
import java.util.List;
import java.util.Map;

/**
 * POST /v1/chat/completions, typed (the console's {@code ChatCompletionRequest}). The fields are OpenAI's, in camelCase; {@code drishti} is the extension.
 * {@code stream}, {@code n} and the sampling controls are optional (null: the provider's default).
 */
public record ChatCompletionRequest(String model, List<ChatMessage> messages, List<ToolDefinition> tools, String toolChoice, Boolean parallelToolCalls,
        ResponseFormat responseFormat, Double temperature, Double topP, Integer maxCompletionTokens, Integer maxTokens, List<String> stop, Integer seed, Integer n,
        Boolean stream, String user, Map<String, String> metadata, String reasoningEffort, Map<String, Object> extraBody, DrishtiRequest drishti) {

    public ChatCompletionRequest {
        model = model == null || model.isBlank() ? "default" : model;
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static ChatCompletionRequest of(String model, List<ChatMessage> messages) {
        return new ChatCompletionRequest(model, messages, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ChatCompletionRequest of(String model, String system, String user) {
        return of(model, system == null || system.isEmpty() ? List.of(ChatMessage.user(user)) : List.of(ChatMessage.system(system), ChatMessage.user(user)));
    }

    /** The request built from keyword fields, the Java twin of {@code ChatCompletionRequest.coerce(**fields)}: keys are the record's field names. */
    @SuppressWarnings("unchecked")
    public static ChatCompletionRequest coerce(Map<String, Object> f) {
        return new ChatCompletionRequest((String) f.get("model"), (List<ChatMessage>) f.get("messages"), (List<ToolDefinition>) f.get("tools"), (String) f.get("toolChoice"),
                (Boolean) f.get("parallelToolCalls"), (ResponseFormat) f.get("responseFormat"), (Double) f.get("temperature"), (Double) f.get("topP"),
                (Integer) f.get("maxCompletionTokens"), (Integer) f.get("maxTokens"), (List<String>) f.get("stop"), (Integer) f.get("seed"), (Integer) f.get("n"),
                (Boolean) f.get("stream"), (String) f.get("user"), (Map<String, String>) f.get("metadata"), (String) f.get("reasoningEffort"),
                (Map<String, Object>) f.get("extraBody"), (DrishtiRequest) f.get("drishti"));
    }

    public ChatCompletionRequest withMessages(List<ChatMessage> m) {
        return new ChatCompletionRequest(model, m, tools, toolChoice, parallelToolCalls, responseFormat, temperature, topP, maxCompletionTokens, maxTokens, stop, seed, n,
                stream, user, metadata, reasoningEffort, extraBody, drishti);
    }

    public ChatCompletionRequest withDrishti(DrishtiRequest d) {
        return new ChatCompletionRequest(model, messages, tools, toolChoice, parallelToolCalls, responseFormat, temperature, topP, maxCompletionTokens, maxTokens, stop, seed, n,
                stream, user, metadata, reasoningEffort, extraBody, d);
    }

    public ChatCompletionRequest withModel(String m) {
        return new ChatCompletionRequest(m, messages, tools, toolChoice, parallelToolCalls, responseFormat, temperature, topP, maxCompletionTokens, maxTokens, stop, seed, n,
                stream, user, metadata, reasoningEffort, extraBody, drishti);
    }

    /** {@code maxCompletionTokens}, else {@code maxTokens}, else null. */
    public Integer maxOutputTokens() {
        return maxCompletionTokens != null ? maxCompletionTokens : maxTokens;
    }

    public boolean wantsTools() {
        return tools != null && !tools.isEmpty();
    }
}
