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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.SourcePlugin;
import java.util.Locale;

/**
 * A connector's health reduced to one word: a plugin's health text starting {@code UP} is up, {@code DEGRADED} serves but
 * cannot read some of its data, anything else (or a health check that throws) is down. Shared by the admin health page and
 * the explanation of a view, so both say the same.
 */
public enum SourceHealth {
    UP, DEGRADED, DOWN;

    /** The word for a plugin's health text ({@code null} means up, as the admin page reads it). */
    public static SourceHealth of(String health) {
        String h = health == null ? "UP" : health;
        return h.startsWith("UP") ? UP : h.startsWith("DEGRADED") ? DEGRADED : DOWN;
    }

    /** The word for a plugin, down when its health check throws. */
    public static SourceHealth of(SourcePlugin plugin) {
        try {
            return of(plugin.health());
        } catch (RuntimeException e) {
            return DOWN;
        }
    }

    /** Lower case, as the explanation says it. */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }
}
