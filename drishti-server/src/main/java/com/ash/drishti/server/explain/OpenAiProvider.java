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
 * An OpenAI-compatible chat completions endpoint: {@code {model, messages:[{role,content}], max_tokens}} in, the text at
 * {@code choices[0].message.content} out. No tools, no functions, no streaming.
 */
final class OpenAiProvider extends HttpAskProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    OpenAiProvider(AskProperties cfg) {
        super(cfg);
    }

    @Override
    JsonNode request(String system, String user, int maxTokens) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", cfg.model());
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        return body;
    }

    @Override
    void headers(HttpRequest.Builder b) {
        if (!cfg.apiKey().isBlank()) {
            b.header("Authorization", "Bearer " + cfg.apiKey());
        }
    }

    @Override
    String text(JsonNode response) throws Failure {
        JsonNode t = response.path("choices").path(0).path("message").path("content");
        if (!t.isTextual()) {
            throw new Failure("the model endpoint's answer has no choices[0].message.content", false, null);
        }
        return t.asText();
    }
}
