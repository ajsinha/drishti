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

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * One model behind an OpenAI-style interface. The same design, in Java camelCase, as {@code LLMModel} in the console's {@code core/ai/llm/base.py}
 * (which mirrors sajhamcpserver's {@code sajha/ai/llm/base.py}). The Python {@code request=None, /, **fields} is a request object here, or a map of fields
 * (see {@link ChatCompletionRequest#coerce}). A chat model refuses embeddings and an embedding model refuses chat, both with {@code LlmException(UNSUPPORTED)}.
 */
public interface LLMModel {

    /** The model as one entry of {@code GET /v1/models}. */
    ModelInfo info();

    /** POST /v1/chat/completions. */
    ChatCompletion chatCompletionsCreate(ChatCompletionRequest request);

    default ChatCompletion chatCompletionsCreate(Map<String, Object> fields) {
        return chatCompletionsCreate(ChatCompletionRequest.coerce(fields));
    }

    /** The async twin of {@link #chatCompletionsCreate}. */
    default CompletableFuture<ChatCompletion> achatCompletionsCreate(ChatCompletionRequest request) {
        return CompletableFuture.supplyAsync(() -> chatCompletionsCreate(request));
    }

    /** POST /v1/chat/completions with stream=true, as chunks. Streaming is emulated from one completion unless a provider streams natively. */
    Iterator<ChatCompletionChunk> chatCompletionsStream(ChatCompletionRequest request);

    /** The async twin of {@link #chatCompletionsStream}. */
    Flow.Publisher<ChatCompletionChunk> achatCompletionsStream(ChatCompletionRequest request);

    /** POST /v1/embeddings. */
    EmbeddingsResponse embeddingsCreate(EmbeddingsRequest request);

    default CompletableFuture<EmbeddingsResponse> aembeddingsCreate(EmbeddingsRequest request) {
        return CompletableFuture.supplyAsync(() -> embeddingsCreate(request));
    }
}
