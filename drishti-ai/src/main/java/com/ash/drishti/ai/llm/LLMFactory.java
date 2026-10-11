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

import com.ash.drishti.ai.CallContext;
import com.ash.drishti.ai.LlmGateway;
import com.ash.drishti.ai.LlmRegistry;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;

/**
 * The one door application code uses: {@link #model} hands out {@link GovernedModel}s (policy applied), {@link #provider} the configured providers. Mirrors
 * {@code LLMFactory} in {@code core/ai/llm/factory.py}, whose passthrough methods ({@code chatCompletionsCreate} ...) are here too, for the default model.
 */
public final class LLMFactory {

    private final LlmGateway gateway;
    private final Duration timeout;

    public LLMFactory(LlmGateway gateway, Duration timeout) {
        this.gateway = gateway;
        this.timeout = timeout;
    }

    public LlmGateway gateway() {
        return gateway;
    }

    /** A governed model by {@code provider/model}, a model name only one provider has, or (blank or {@code default}) the layer's default; {@code kind} is chat or embedding. */
    public LLMModel model(String name, String kind, CallContext context) {
        String ref = name == null || name.isBlank() || "default".equals(name) ? null : name;
        if ("embedding".equals(kind)) {
            return new GovernedModel(gateway, context, ref, new ModelInfo(name, "", "embedding", null, null, null), timeout);
        }
        LlmRegistry.Resolved r = gateway.registry().chat(ref);
        return new GovernedModel(gateway, context, ref, Convert.info(r.info()), timeout);
    }

    /** A configured provider, as an {@link LLMProvider} (usable ones only). */
    public Optional<LLMProvider> provider(String name) {
        return gateway.registry().provider(name).filter(LlmRegistry.Entry::usable).map(e -> new ProviderBase(e.config(), e.provider()));
    }

    public List<String> providerNames() {
        return gateway.registry().entries().stream().filter(LlmRegistry.Entry::usable).map(e -> e.config().name()).toList();
    }

    /** The shipped and registered provider types. */
    public static List<String> providerTypes() {
        return List.copyOf(LLMProviders.registered().keySet());
    }

    // passthrough to the default model, as the caller in the request (drishti.context) or "anonymous"

    public ChatCompletion chatCompletionsCreate(ChatCompletionRequest request) {
        return model(request.model(), "chat", callerOf(request)).chatCompletionsCreate(request);
    }

    public Iterator<ChatCompletionChunk> chatCompletionsStream(ChatCompletionRequest request) {
        return model(request.model(), "chat", callerOf(request)).chatCompletionsStream(request);
    }

    public Flow.Publisher<ChatCompletionChunk> achatCompletionsStream(ChatCompletionRequest request) {
        return model(request.model(), "chat", callerOf(request)).achatCompletionsStream(request);
    }

    public EmbeddingsResponse embeddingsCreate(EmbeddingsRequest request) {
        return model(request.model(), "embedding", request.drishti() == null ? null : request.drishti().context()).embeddingsCreate(request);
    }

    private static CallContext callerOf(ChatCompletionRequest r) {
        return r.drishti() != null && r.drishti().context() != null ? r.drishti().context() : new CallContext(null, null, null);
    }
}
