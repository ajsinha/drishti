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

import java.nio.file.Path;

/**
 * The named roots every default folder sits under (docs/admin/CONFIGURATION.md, "Launch roots"), for code that has no Spring environment
 * or needs a default: the install folder ({@code drishti.home}), the configuration folder ({@code drishti.config.dir}, default
 * {@code <home>/config}), the data folder ({@code drishti.data.dir}, default {@code <home>/data}) and the modules folder
 * ({@code drishti.modules.home}, default {@code <home>/modules}). Each is a system property (the server publishes its resolved values at
 * start, the launchers pass them) or its environment variable. A relative value is under the install folder; nothing is ever taken from
 * the process's working folder: with no install folder named, a default is refused with a message naming the setting.
 */
public final class Roots {

    /** The install folder: the anchor of every default. */
    public static final String HOME = "drishti.home";
    /** Its environment variable. */
    public static final String HOME_ENV = "DRISHTI_HOME";
    /** The configuration folder. */
    public static final String CONFIG = "drishti.config.dir";
    /** The modules folder. */
    public static final String MODULES = "drishti.modules.home";

    private Roots() {
    }

    /** The install folder, absolute; refused when nothing names it. */
    public static Path home() {
        String h = setting(HOME, HOME_ENV);
        if (h == null) {
            throw new IllegalStateException(HOME + " (" + HOME_ENV + ") is not set: name the install folder (bin/start-server and the images do), or"
                    + " set the folder's own setting; a default folder is never taken from the process's working folder");
        }
        Path p = Path.of(h);
        if (!p.isAbsolute()) {
            throw new IllegalStateException(HOME + " (" + HOME_ENV + ") is '" + h + "', a relative path: give the install folder as an absolute path");
        }
        return p.normalize();
    }

    /** A path under the install folder, e.g. {@code home("sutras")}. */
    public static String home(String sub) {
        return home().resolve(sub).normalize().toString();
    }

    /** The configuration folder. */
    public static String config() {
        return base(CONFIG, "DRISHTI_CONFIG_DIR", "config").toString();
    }

    /** A path under the configuration folder, e.g. {@code config("bi")}. */
    public static String config(String sub) {
        return base(CONFIG, "DRISHTI_CONFIG_DIR", "config").resolve(sub).normalize().toString();
    }

    /** The data folder. */
    public static String data() {
        return base(DataDir.PROPERTY, DataDir.ENV, "data").toString();
    }

    /** A path under the data folder, e.g. {@code data("collab")}. */
    public static String data(String sub) {
        return base(DataDir.PROPERTY, DataDir.ENV, "data").resolve(sub).normalize().toString();
    }

    /** The modules folder. */
    public static String modules() {
        return base(MODULES, "DRISHTI_MODULES_HOME", "modules").toString();
    }

    /** A value as an absolute path: as given when absolute, else under the install folder (never the working folder). */
    public static Path anchored(String value) {
        Path p = Path.of(value);
        return p.isAbsolute() ? p.normalize() : home().resolve(p).normalize();
    }

    private static Path base(String property, String env, String underHome) {
        String v = setting(property, env);
        return v == null ? home().resolve(underHome) : anchored(v);
    }

    private static String setting(String property, String env) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? null : v.strip();
    }
}
