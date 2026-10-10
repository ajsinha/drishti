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

/**
 * The umbrella folder every runtime path defaults under ({@code drishti.data.dir}, {@code DRISHTI_DATA_DIR}, default
 * {@code ./data}). For code that has no Spring environment (plugins, the command-line tools): the system property wins,
 * then the environment variable. The server publishes its resolved value as the system property at start, so a value
 * set only in a configuration file reaches the plugins too. A path's own setting still overrides its default.
 */
public final class DataDir {

    public static final String PROPERTY = "drishti.data.dir";
    public static final String ENV = "DRISHTI_DATA_DIR";
    public static final String DEFAULT = "./data";

    private DataDir() {
    }

    /** The umbrella folder as configured, not made absolute. */
    public static String root() {
        String p = System.getProperty(PROPERTY);
        if (p == null || p.isBlank()) {
            p = System.getenv(ENV);
        }
        return p == null || p.isBlank() ? DEFAULT : p.strip();
    }

    /** A folder under the umbrella, e.g. {@code under("delta")} is {@code ./data/delta} by default. */
    public static String under(String sub) {
        String r = root();
        return (r.endsWith("/") || r.endsWith("\\") ? r : r + "/") + sub;
    }
}
