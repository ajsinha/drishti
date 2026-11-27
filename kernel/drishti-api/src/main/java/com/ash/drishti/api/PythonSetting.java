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
package com.ash.drishti.api;

import java.util.function.UnaryOperator;

/**
 * The one place that decides which Python the server starts. The precedence, first that is not blank:
 * <ol>
 * <li>{@code DRISHTI_PYTHON} (the environment variable): beats everything;</li>
 * <li>the work's own setting ({@code drishti.feeds.python}, {@code compute.python} of compute.yaml);</li>
 * <li>{@code drishti.python}: the interpreter {@code tools/drishti.py setup} ran with, written into the settings file
 * (an absolute path, see the explicit-paths rule);</li>
 * <li>empty: the caller's own last resort (feeds: {@code python3}, {@code python} on Windows; Drishti makes and uses no venv).</li>
 * </ol>
 */
public final class PythonSetting {

    /** The shared setting. */
    public static final String PROPERTY = "drishti.python";
    /** The variable that overrides every setting. */
    public static final String ENV = "DRISHTI_PYTHON";

    private PythonSetting() {
    }

    /**
     * The interpreter to start, or {@code ""} when none is configured.
     *
     * @param own the work's own setting (may be null or blank)
     * @param property reads a setting by key (the Spring environment's {@code getProperty}); null reads nothing
     * @param env reads an environment variable by name ({@code System::getenv} in the server)
     */
    public static String resolve(String own, UnaryOperator<String> property, UnaryOperator<String> env) {
        for (String v : new String[] {env == null ? null : env.apply(ENV), own, property == null ? null : property.apply(PROPERTY)}) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return "";
    }

    /**
     * {@code configured} when it is not blank, else the PATH's Python by its name on this operating system: {@code python} on Windows
     * (where the installer and the Store put no {@code python3}), {@code python3} elsewhere. Every place that starts Python asks here.
     */
    public static String orDefault(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "python" : "python3";
    }
}
