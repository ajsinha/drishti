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

import com.ash.drishti.ai.llm.Canonical.Choice;
import com.ash.drishti.ai.llm.Canonical.CompletionUsage;
import com.ash.drishti.ai.llm.Canonical.ResponseDrishti;
import com.ash.drishti.ai.llm.Canonical.ToolCall;
import java.util.List;
import java.util.UUID;

/** A chat completion, typed (the console's {@code ChatCompletion}): OpenAI's response plus the {@code drishti} extension. */
public record ChatCompletion(String id, String object, long created, String model, List<Choice> choices, CompletionUsage usage, ResponseDrishti drishti) {

    public ChatCompletion {
        id = id == null || id.isBlank() ? "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24) : id;
        object = object == null ? "chat.completion" : object;
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    /** The first choice's message text. */
    public String text() {
        return choices.isEmpty() ? "" : choices.get(0).message().text();
    }

    public List<ToolCall> toolCalls() {
        return choices.isEmpty() || choices.get(0).message().toolCalls() == null ? List.of() : choices.get(0).message().toolCalls();
    }

    public String finishReason() {
        return choices.isEmpty() || choices.get(0).finishReason() == null ? "stop" : choices.get(0).finishReason();
    }

    public ChatCompletion withDrishti(ResponseDrishti d) {
        return new ChatCompletion(id, object, created, model, choices, usage, d);
    }
}
