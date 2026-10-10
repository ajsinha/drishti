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
package com.ash.drishti.packs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.env.Environment;

/**
 * Where the pack folders and the pack runtime files are, from the environment: the packs folder
 * ({@code drishti.packs.dir}, default {@code ./config/packs}) and the files under the data umbrella
 * ({@code drishti.data.dir}, default {@code ./data}) that each have their own setting. One place, so every reader agrees.
 */
public final class PackPaths {

    public static final String DEFAULT_PACKS = "./config/packs";
    private static final String LEGACY_PACKS = "./packs";
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private PackPaths() {
    }

    /** The data umbrella ({@code drishti.data.dir}). */
    public static String dataDir(Environment env) {
        String d = env.getProperty("drishti.data.dir", "./data");
        return d.endsWith("/") || d.endsWith("\\") ? d.substring(0, d.length() - 1) : d;
    }

    /**
     * The packs folder. For one release the old location still works: when the configured folder (the default or not)
     * does not exist and {@code ./packs} does, that one is used, with a warning.
     */
    public static String packsDir(Environment env) {
        String dir = env.getProperty("drishti.packs.dir", DEFAULT_PACKS);
        if (!Files.isDirectory(Path.of(dir)) && Files.isDirectory(Path.of(LEGACY_PACKS))) {
            if (WARNED.compareAndSet(false, true)) {
                System.getLogger(PackPaths.class.getName()).log(System.Logger.Level.WARNING,
                        "The packs folder {0} does not exist; using the old location {1}. Move it with: git mv packs config/packs "
                                + "(or set drishti.packs.dir / DRISHTI_PACKS_DIR). This fallback ends in the next release.", dir, LEGACY_PACKS);
            }
            return LEGACY_PACKS;
        }
        return dir;
    }

    public static String installedDir(Environment env) {
        return env.getProperty("drishti.packs.installed-dir", dataDir(env) + "/packs/installed");
    }

    public static String overlay(Environment env) {
        return env.getProperty("drishti.packs.overlay", dataDir(env) + "/packs/added.yaml");
    }

    public static String settingsDir(Environment env) {
        return env.getProperty("drishti.packs.settings-dir", dataDir(env) + "/packs/settings");
    }

    public static String samplesFile(Environment env) {
        return env.getProperty("drishti.packs.samples-file", dataDir(env) + "/packs/samples-mode");
    }

    public static String historyFile(Environment env) {
        return env.getProperty("drishti.packs.deploy.history-file", dataDir(env) + "/packs/deploy-history.jsonl");
    }
}
