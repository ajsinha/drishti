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

/** Tokens used by one call and what they cost under the model's configured prices (0 when none are configured). */
public record Usage(int promptTokens, int completionTokens, double cost) {

    public static final Usage NONE = new Usage(0, 0, 0);

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
