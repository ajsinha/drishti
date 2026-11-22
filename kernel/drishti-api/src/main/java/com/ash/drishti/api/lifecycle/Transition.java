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
package com.ash.drishti.api.lifecycle;

import java.util.Locale;

/** The five lifecycle transitions (docs/architecture/LIFECYCLE.md section 2), named as the engine specification names them. */
public enum Transition {
    INIT, START, SUSPEND, RESUME, SHUTDOWN;

    /** The word used in the REST paths, the audit and Admin ({@code suspend}). */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The transition a path word names ({@code suspend}); an unknown word is an {@link IllegalArgumentException} naming the five. */
    public static Transition of(String word) {
        for (Transition t : values()) {
            if (t.word().equalsIgnoreCase(word == null ? "" : word.strip())) {
                return t;
            }
        }
        throw new IllegalArgumentException("a lifecycle transition is init, start, suspend, resume or shutdown, not '" + word + "'");
    }
}
