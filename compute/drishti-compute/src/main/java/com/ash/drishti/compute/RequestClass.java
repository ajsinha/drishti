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
package com.ash.drishti.compute;

import java.util.Locale;

/**
 * The three classes of compute request (docs/architecture/DATA_ARCHITECTURE.md › Reactive connectors, section 10.3), in the order a free worker serves them:
 * a person waiting on a panel, then triggers, then batch work, which may hold only its share of a pool's workers.
 */
public enum RequestClass {
    INTERACTIVE, EVENT, BATCH;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static RequestClass of(String s) {
        if (s == null || s.isBlank()) {
            return INTERACTIVE;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("a request class is interactive, event or batch, not '" + s + "'");
        }
    }
}
