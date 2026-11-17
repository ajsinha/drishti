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
 * {@code <home>/config}) and the data folder ({@code drishti.data.dir}, default {@code <home>/data}). The modules folder is the module
 * boot's alone: a module's folder is asked of the boot result ({@code ModuleHomes}), never derived from a root. Each is a system property
 * (the server publishes its resolved values at start, the launchers pass them) or its environment variable. A relative value is under the install folder; nothing is ever taken from
 * the process's working folder: with no install folder named, a default is refused with a message naming the setting.
 */
public final class Roots {

    /** The install folder: the anchor of every default. */
    public static final String HOME = "drishti.home";
    /** Its environment variable. */
    public static final String HOME_ENV = "DRISHTI_HOME";
    /** The configuration folder. */
    public static final String CONFIG = "drishti.config.dir";

    private Roots() {
    }

    /** The install folder, absolute; refused when nothing names it. */
    public static Path home() {
        String h = setting(HOME, HOME_ENV);
        if (h == null) {
            throw new IllegalStateException(HOME + " (" + HOME_ENV + ") is not set: name the install folder (drishti.py start server and the images do), or"
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

    /**
     * A folder setting of a process with no Spring environment: the system property, else its environment variable, else
     * {@code <home>/<underHome>}; absolute (a relative value is under the install folder).
     */
    public static Path named(String property, String env, String underHome) {
        return base(property, env, underHome);
    }

    /**
     * The site's connectors folder for code with no Spring environment: {@code drishti.sources.connectors-dir}, else
     * {@code DRISHTI_CONNECTORS_DIR}, else {@code <config>/connectors}; absolute.
     */
    public static Path connectors() {
        String v = setting("drishti.sources.connectors-dir", "DRISHTI_CONNECTORS_DIR");
        return v == null ? Path.of(config("connectors")) : anchored(v);
    }

    /** A value as an absolute path: as given when absolute, else under the install folder (never the working folder). */
    public static Path anchored(String value) {
        Path p = Path.of(value);
        return p.isAbsolute() ? p.normalize() : home().resolve(p).normalize();
    }

    /** A JDBC URL of a database in a local file (SQLite, DuckDB, H2): its file part is a path. */
    private static final java.util.regex.Pattern FILE_DB = java.util.regex.Pattern.compile("^(jdbc:(?:sqlite|duckdb|h2:file):)(.+)$");
    /** A value that is not a local relative path: a URL or a scheme ({@code s3:}, {@code mem:}, {@code classpath:}, {@code jdbc:}), a word. */
    private static final java.util.regex.Pattern NOT_A_PATH = java.util.regex.Pattern.compile(
            "^(?:[A-Za-z][A-Za-z0-9+.-]+:.*|none|auto|off|on|true|false|-?[0-9]+(?:\\.[0-9]+)?)$");
    /** An absolute local path: POSIX, a Windows drive, or a UNC share. */
    private static final java.util.regex.Pattern ABSOLUTE = java.util.regex.Pattern.compile("^(?:/|\\\\|[A-Za-z]:[\\\\/]).*");

    /** Whether a value is an absolute local path (POSIX, a Windows drive, a UNC share), on any operating system. */
    public static boolean isAbsolute(String value) {
        return value != null && ABSOLUTE.matcher(value.strip()).matches();
    }

    /**
     * One value of a path setting made absolute, the one rule of every reader (docs/admin/CONFIGURATION.md, "Launch roots"): a relative
     * path is taken under {@code home}, never the working folder; a {@code jdbc:sqlite:}/{@code duckdb:}/{@code h2:file:} URL has its file
     * part anchored; any other URL (a key ending {@code url}), a scheme ({@code s3://}, {@code mem://}, {@code file:}), a word, a number, a
     * JSON path ({@code $.x}, {@code @x}), a template ({@code {x}}) or an absolute path is kept as given.
     *
     * @param key      the setting's name (for the URL rule and the message)
     * @param value    one value (one entry of a list)
     * @param home     the install folder, absolute, or null
     * @param problems where a relative value with no install folder is reported (the value is then kept)
     * @return the value to use
     */
    public static String anchorValue(String key, String value, Path home, java.util.List<String> problems) {
        String x = value == null ? "" : value.strip();
        java.util.regex.Matcher db = FILE_DB.matcher(x);
        if (db.matches()) {                                       // jdbc:sqlite:<file>: the file part, unless absolute or in memory
            String file = db.group(2);
            if (file.startsWith(":") || ABSOLUTE.matcher(file).matches() || file.contains("{")) {
                return value;
            }
            if (home == null) {
                problems.add(key + " is '" + x + "', a database file at a relative path, and " + HOME + " (" + HOME_ENV + ") is not set");
                return value;
            }
            return db.group(1) + home.resolve(file).normalize();
        }
        if ((key.endsWith("url") || key.endsWith("URL")) && !key.equals("drishti.packs.registry.url")) {   // any other URL is not a path (the registry's may be a folder)
            return value;
        }
        if (x.isEmpty() || ABSOLUTE.matcher(x).matches() || NOT_A_PATH.matcher(x).matches() || x.contains("{")
                || x.startsWith("$") || x.startsWith("@")) {             // a JSON path, a placeholder left as text
            return value;
        }
        if (home == null) {
            problems.add(key + " is '" + x + "', a relative path, and " + HOME + " (" + HOME_ENV + ") is not set: give an absolute path, or set " + HOME);
            return value;
        }
        return home.resolve(x).normalize().toString();
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
