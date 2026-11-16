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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one door features use: resolves the model by logical name, runs the policy before and after, honours the request's
 * timeout, and tells the registry how the call went (health). Features never touch a provider directly.
 */
public final class LlmGateway {

    private final LlmRegistry registry;
    private final LlmPolicy policy;
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    public LlmGateway(LlmRegistry registry, LlmPolicy policy) {
        this.registry = registry;
        this.policy = policy;
    }

    public LlmRegistry registry() {
        return registry;
    }

    public ChatResponse chat(CallContext ctx, ChatRequest request) throws LlmException {
        LlmRegistry.Resolved r = registry.chat(request.model());
        ModelInfo info = r.info();
        ChatRequest masked = policy.before(ctx, info, request);
        if (masked.jsonMode() && !info.capabilities().contains(Capability.JSON_MODE)) {
            throw new LlmException(LlmException.Kind.UNSUPPORTED, "model " + info.ref() + " has no json-mode");
        }
        if (!masked.tools().isEmpty() && !info.capabilities().contains(Capability.TOOL_CALLS)) {
            throw new LlmException(LlmException.Kind.UNSUPPORTED, "model " + info.ref() + " has no tool-calls");
        }
        long t0 = System.nanoTime();
        try {
            ChatResponse resp = call(r.model(), masked);
            registry.record(r.entry(), true, null);
            policy.after(ctx, info, masked, resp, null, ms(t0));
            return resp;
        } catch (LlmException e) {
            registry.record(r.entry(), false, e.getMessage());
            policy.after(ctx, info, masked, null, e, ms(t0));
            throw e;
        }
    }

    private ChatResponse call(LlmModel model, ChatRequest req) {
        Duration t = req.timeout();
        if (t == null) {
            return model.chat(req);
        }
        CompletableFuture<ChatResponse> f = CompletableFuture.supplyAsync(() -> model.chat(req), executor);
        try {
            return f.get(t.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new LlmException(LlmException.Kind.TIMEOUT, "the model endpoint did not answer within " + Math.max(1, t.toSeconds()) + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.FAILED, "the model call was interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LlmException le) {
                throw le;
            }
            throw new LlmException(LlmException.Kind.FAILED, "the model call failed", e.getCause());
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }
}
