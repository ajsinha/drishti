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
package com.ash.drishti.packs.module;

import java.util.List;

/**
 * The one place a retired setting name stops the server: a site that still sets a name the product no longer reads (an engine's old switch, a module
 * setting's old server key) is told, at start, which new key replaces it. Nothing is read from the old name and nothing is warned about and carried on.
 */
public final class RetiredNames {

    private RetiredNames() {
    }

    /** One line of the refusal: the retired name, where it is set, and the key to use instead. */
    public static String line(String oldName, String where, String newKey) {
        return "  " + oldName + (where == null || where.isBlank() ? "" : " (set in " + where + ")") + " is retired: use " + newKey;
    }

    /** The refusal's text, or null when no name is retired. */
    public static String message(List<String> lines) {
        return lines.isEmpty() ? null : "the server cannot start: settings names that were retired are still set; rename each one:\n" + String.join("\n", lines);
    }

    /** Stops the start (IllegalStateException with {@link #message}) when {@code lines} is not empty. */
    public static void refuse(List<String> lines) {
        String m = message(lines);
        if (m != null) {
            throw new IllegalStateException(m);
        }
    }
}
