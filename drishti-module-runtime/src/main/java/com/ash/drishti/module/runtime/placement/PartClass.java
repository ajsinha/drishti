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
package com.ash.drishti.module.runtime.placement;

import java.util.Locale;

/**
 * A module part's resource class (KERNEL_AND_MODULES.md section 4.11.2): what the publisher says a part costs, so the site places it.
 * {@code light} may go wide; {@code heavy} declares its memory and obeys the deployer's max-nodes; {@code feed} and {@code singleton} run on
 * exactly one node, elected among the nodes they are placed on.
 */
public enum PartClass {
    LIGHT, HEAVY, FEED, SINGLETON;

    /** Exactly one runner: an elected owner among the placed nodes. */
    public boolean oneRunner() {
        return this == FEED || this == SINGLETON;
    }

    /** The word the manifest and the API use. */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** From the manifest's word; refuses an unknown one. */
    public static PartClass of(String word) {
        try {
            return valueOf(String.valueOf(word).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown resource class `" + word + "` (light, heavy, feed or singleton)", e);
        }
    }
}
