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

/** A model call or a provider setup failed. The message never carries a key or a prompt. */
public class LlmException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Kind { CONFIG, NOT_FOUND, UNSUPPORTED, POLICY, RATE_LIMITED, TIMEOUT, FAILED }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        this(kind, message, null);
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean timeout() {
        return kind == Kind.TIMEOUT;
    }
}
