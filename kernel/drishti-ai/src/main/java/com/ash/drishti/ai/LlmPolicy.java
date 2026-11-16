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

/**
 * The hooks around every model call. {@link #before} may refuse the call (throw) or change the request (masking);
 * {@link #after} sees the outcome (audit, cost accounting) and must not throw.
 */
public interface LlmPolicy {

    ChatRequest before(CallContext ctx, ModelInfo model, ChatRequest request) throws LlmException;

    /** @param response null when the call failed; @param failure null when it succeeded */
    void after(CallContext ctx, ModelInfo model, ChatRequest request, ChatResponse response, LlmException failure, long millis);

    /** A policy that allows everything and records nothing. */
    static LlmPolicy none() {
        return new LlmPolicy() {
            @Override
            public ChatRequest before(CallContext ctx, ModelInfo model, ChatRequest request) {
                return request;
            }

            @Override
            public void after(CallContext ctx, ModelInfo model, ChatRequest request, ChatResponse response, LlmException failure, long millis) {}
        };
    }
}
