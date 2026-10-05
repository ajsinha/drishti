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

/** Chooses the provider by {@code drishti.explain.ask.api}. Creating one makes no network call. */
public final class AskProviders {

    private AskProviders() {}

    public static AskProvider create(AskProperties cfg) {
        return "anthropic".equals(cfg.api()) ? new AnthropicProvider(cfg) : new OpenAiProvider(cfg);
    }
}
