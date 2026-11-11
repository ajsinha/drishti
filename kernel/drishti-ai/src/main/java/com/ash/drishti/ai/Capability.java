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

/** What a provider or a model can do; a caller asks before it relies on one. */
public enum Capability {
    CHAT, STREAMING, TOOL_CALLS, EMBEDDINGS, JSON_MODE;

    /** The name written in a provider file: {@code chat}, {@code tool-calls}, {@code json-mode}. */
    @com.fasterxml.jackson.annotation.JsonValue
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public static Capability of(String id) {
        return valueOf(id.strip().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
    }
}
