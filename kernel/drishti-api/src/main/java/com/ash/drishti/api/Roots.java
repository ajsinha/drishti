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

import com.ash.tools.settings.PathAnchors;
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
    /** The configuration folder: the release's shipped defaults (read-only in production). */
    public static final String CONFIG = "drishti.config.dir";
    /** The site folder: every file the site owns and every Admin write (connector overrides, engine switches, module switches, licences, ...). */
    public static final String SITE = "drishti.site.dir";
    /** Its environment variable. */
    public static final String SITE_ENV = "DRISHTI_SITE_DIR";

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

    /**
     * The site folder: {@code drishti.site.dir} ({@code DRISHTI_SITE_DIR}), else the configuration folder (the developer and demo layout: one
     * folder holds the defaults and the site's files). In production it is named ({@code /etc/drishti}) and the release is never written.
     */
    public static String site() {
        return sitePath().toString();
    }

    /** A path under the site folder, e.g. {@code site("connectors")}: where a site file is read and where Admin writes it. */
    public static String site(String sub) {
        return sitePath().resolve(sub).normalize().toString();
    }

    /** Whether the site folder is named and is not the configuration folder (production, or any install whose release is kept read-only). */
    public static boolean siteIsSeparate() {
        String v = setting(SITE, SITE_ENV);
        if (v == null) {
            return false;
        }
        try {
            return !anchored(v).equals(base(CONFIG, "DRISHTI_CONFIG_DIR", "config"));
        } catch (IllegalStateException noHome) {
            return Path.of(v).isAbsolute();         // no install folder named (a tool, a test): the site folder alone is known
        }
    }

    /**
     * The one check before a site file is written: a write under the release (the install folder) that is neither the site folder nor the
     * data folder is refused with the way out, when the site folder is a separate one. The release's own files are its configuration folder and the files straight under the install folder. Refused when the release is immutable, the message
     * names the setting; allowed everywhere else (the developer layout, where the site folder is the configuration folder).
     *
     * @param target the file or folder about to be written
     * @throws ReleaseFolderWriteException when the write would land in the release folder
     */
    public static void guardWrite(Path target) {
        if (target == null || !siteIsSeparate()) {
            return;
        }
        Path t = target.toAbsolutePath().normalize();
        Path home;
        Path config;
        try {
            home = home();
            config = base(CONFIG, "DRISHTI_CONFIG_DIR", "config");
        } catch (IllegalStateException noHome) {
            return;                                  // no release folder is known: nothing to protect
        }
        // the release's own files: its configuration folder and the files straight under the install folder (not the data folder, which may sit anywhere)
        boolean inRelease = t.startsWith(config) || (t.getParent() != null && t.getParent().equals(home));
        if (inRelease && !t.startsWith(sitePath())) {
            throw new ReleaseFolderWriteException("refused: " + t + " is inside the release folder " + home + ", which is never written: site files and Admin changes go to the"
                    + " site folder " + sitePath() + " (" + SITE + ") and the data folder");
        }
    }

    /** A write that would land in the release folder (see {@link #guardWrite}). */
    public static final class ReleaseFolderWriteException extends com.ash.drishti.api.error.DrishtiException {
        private static final long serialVersionUID = 1L;

        ReleaseFolderWriteException(String message) {
            super(com.ash.drishti.api.error.ErrorCode.RELEASE_FOLDER_READ_ONLY, message);
        }
    }

    private static Path sitePath() {
        String v = setting(SITE, SITE_ENV);
        return v == null ? base(CONFIG, "DRISHTI_CONFIG_DIR", "config") : anchored(v);
    }

    /**
     * The server's own local overrides ({@code application.local.yaml}, written by an approved Admin -> Configuration change): in the site folder
     * when it is a separate one, else in the install folder (the developer layout).
     */
    public static Path localFile() {
        return siteIsSeparate() ? sitePath().resolve("application.local.yaml") : home().resolve("application.local.yaml");
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
        return v == null ? Path.of(site("connectors")) : anchored(v);
    }

    /** A value as an absolute path: as given when absolute, else under the install folder (never the working folder). */
    public static Path anchored(String value) {
        Path p = Path.of(value);
        return p.isAbsolute() ? p.normalize() : home().resolve(p).normalize();
    }

    /** Whether a value is an absolute local path (POSIX, a Windows drive, a UNC share), on any operating system. */
    public static boolean isAbsolute(String value) {
        return PathAnchors.isAbsolute(value);
    }

    /** The one setting whose value is a URL that may also be a folder (the packs registry): the URL rule does not apply to it. */
    private static final String REGISTRY_URL = "drishti.packs.registry.url";

    /**
     * One value of a path setting made absolute, the one rule of every reader (docs/admin/CONFIGURATION.md, "Launch roots"): a relative
     * path is taken under {@code home}, never the working folder; a {@code jdbc:sqlite:}/{@code duckdb:}/{@code h2:file:} URL has its file
     * part anchored; any other URL (a key ending {@code url}), a scheme ({@code s3://}, {@code mem://}, {@code file:}), a word, a number, a
     * JSON path ({@code $.x}, {@code @x}), a template ({@code {x}}) or an absolute path is kept as given. The rule is the shared one
     * ({@link PathAnchors}); the install folder's setting is Drishti's.
     *
     * @param key      the setting's name (for the URL rule and the message)
     * @param value    one value (one entry of a list)
     * @param home     the install folder, absolute, or null
     * @param problems where a relative value with no install folder is reported (the value is then kept)
     * @return the value to use
     */
    public static String anchorValue(String key, String value, Path home, java.util.List<String> problems) {
        if (!REGISTRY_URL.equals(key)) {
            return PathAnchors.anchorValue(key, value, home, HOME, problems);
        }
        java.util.List<String> local = new java.util.ArrayList<>();     // the registry's URL may be a folder: asked as a path, named as itself
        String anchored = PathAnchors.anchorValue("drishti.packs.registry", value, home, HOME, local);
        local.forEach(m -> problems.add(m.replaceFirst("^drishti\\.packs\\.registry ", REGISTRY_URL + " ")));
        return anchored;
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
