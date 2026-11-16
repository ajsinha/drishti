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

/**
 * A chat call, independent of any provider. {@code model} is a logical name ({@code provider/model} or a unique model name;
 * null = the registry default); null numbers mean the model's configured defaults.
 *
 * @param system the fixed instructions (also accepted as a {@code SYSTEM} message)
 * @param messages the conversation, oldest first
 * @param tools functions the model may ask for (needs the TOOL_CALLS capability)
 * @param jsonMode ask for a JSON object answer (needs the JSON_MODE capability)
 * @param timeout how long to wait for the whole answer; null = the provider's configured timeout
 */
public record ChatRequest(String model, String system, List<ChatMessage> messages, List<ToolSpec> tools, Double temperature,
        Integer maxTokens, boolean jsonMode, Duration timeout) {

    public ChatRequest {
        system = system == null ? "" : system;
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** A system instruction and one user turn. */
    public static ChatRequest of(String model, String system, String user, Integer maxTokens, Duration timeout) {
        return new ChatRequest(model, system, List.of(ChatMessage.user(user)), List.of(), null, maxTokens, false, timeout);
    }

    public ChatRequest withModel(String m) {
        return new ChatRequest(m, system, messages, tools, temperature, maxTokens, jsonMode, timeout);
    }

    public ChatRequest withText(String newSystem, List<ChatMessage> newMessages) {
        return new ChatRequest(model, newSystem, newMessages, tools, temperature, maxTokens, jsonMode, timeout);
    }

    /** All text the prompt carries, for size decisions. */
    public int promptChars() {
        int n = system.length();
        for (ChatMessage m : messages) {
            n += m.content().length();
        }
        return n;
    }
}
