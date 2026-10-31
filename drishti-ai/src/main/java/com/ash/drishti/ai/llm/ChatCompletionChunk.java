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

import com.ash.drishti.ai.llm.Canonical.ChoiceDelta;
import com.ash.drishti.ai.llm.Canonical.ChunkChoice;
import com.ash.drishti.ai.llm.Canonical.CompletionUsage;
import com.ash.drishti.ai.llm.Canonical.ResponseDrishti;
import java.util.List;

/** One streamed piece of a chat completion (the console's {@code ChatCompletionChunk}). */
public record ChatCompletionChunk(String id, String object, long created, String model, List<ChunkChoice> choices, CompletionUsage usage, ResponseDrishti drishti) {

    public ChatCompletionChunk {
        object = object == null ? "chat.completion.chunk" : object;
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    /** The text this chunk adds, "" when none. */
    public String delta() {
        return choices.isEmpty() || choices.get(0).delta() == null || choices.get(0).delta().content() == null ? "" : choices.get(0).delta().content();
    }

    /** A completion as chunks: the role, the text, the finish (and the usage), the way a stream arrives. Streaming is emulated from one completion (docs/admin/AI.md). */
    public static List<ChatCompletionChunk> fromCompletion(ChatCompletion c) {
        ChoiceDelta role = new ChoiceDelta("assistant", "", null, null);
        ChoiceDelta text = new ChoiceDelta(null, c.text(), null, null);
        return List.of(
                new ChatCompletionChunk(c.id(), null, c.created(), c.model(), List.of(new ChunkChoice(0, role, null)), null, null),
                new ChatCompletionChunk(c.id(), null, c.created(), c.model(), List.of(new ChunkChoice(0, text, null)), null, null),
                new ChatCompletionChunk(c.id(), null, c.created(), c.model(), List.of(new ChunkChoice(0, new ChoiceDelta(null, null, null, null), c.finishReason())), c.usage(),
                        c.drishti()));
    }
}
