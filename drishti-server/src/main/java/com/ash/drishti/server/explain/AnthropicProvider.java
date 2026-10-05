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
package com.ash.drishti.server.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;

/**
 * The Anthropic Messages API: {@code {model, max_tokens, system, messages:[{role:user,content}]}} in, the {@code text}
 * blocks of {@code content[]} out, joined. No tools are declared.
 */
final class AnthropicProvider extends HttpAskProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    AnthropicProvider(AskProperties cfg) {
        super(cfg);
    }

    @Override
    JsonNode request(String system, String user, int maxTokens) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", cfg.model());
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        body.putArray("messages").addObject().put("role", "user").put("content", user);
        return body;
    }

    @Override
    void headers(HttpRequest.Builder b) {
        b.header("anthropic-version", cfg.anthropicVersion());
        if (!cfg.apiKey().isBlank()) {
            b.header("x-api-key", cfg.apiKey());
        }
    }

    @Override
    String text(JsonNode response) throws Failure {
        StringBuilder out = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                out.append(block.path("text").asText());
            }
        }
        if (out.isEmpty()) {
            throw new Failure("the model endpoint's answer has no text content", false, null);
        }
        return out.toString();
    }
}
