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

import java.time.Duration;

/**
 * A language model reached over HTTP. One question in, one plain-text answer out: no tools, no streaming, no state. Written
 * against the documented request and response shape of each API ({@link OpenAiProvider}, {@link AnthropicProvider}).
 */
public interface AskProvider {

    /**
     * @param system the fixed instructions
     * @param user the delimited material and the question
     * @param maxTokens the output cap
     * @param timeout how long to wait for the whole answer
     * @return the model's text
     * @throws Failure when the endpoint is unreachable, slow, answers an error, or answers something that is not text
     */
    String complete(String system, String user, int maxTokens, Duration timeout) throws Failure;

    /** The endpoint failed; {@code timeout} says it was too slow. The message never carries the key or the prompt. */
    final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        private final boolean timeout;

        public Failure(String message, boolean timeout, Throwable cause) {
            super(message, cause);
            this.timeout = timeout;
        }

        public boolean timeout() {
            return timeout;
        }
    }
}
