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
    /**
     * The packs enabled when {@code drishti.packs.enabled} is not set (or blank) and no edition names them: the one list,
     * {@code config/packs/default-packs.yaml} (copied into this jar at build; the console reads the same file).
     */
    public static final String DEFAULT_ENABLED = String.join(",", com.ash.drishti.packs.edition.Editions.defaultPacks());
    private static final String LEGACY_PACKS = "./packs";
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private PackPaths() {
    }

    /** The value of {@code drishti.packs.enabled} that means "the edition's packs, else the default list" (application.yaml's default). */
    public static final String DEFAULT_WORD = "default";

    /**
     * The packs enabled in the configuration: {@code drishti.packs.enabled}; when unset or {@value #DEFAULT_WORD}, the edition's
     * ({@code drishti.edition}), else {@link #DEFAULT_ENABLED}. Set to empty it is empty, as it always was (no packs).
     */
    public static String enabled(Environment env) {
        String v = env.getProperty("drishti.packs.enabled", DEFAULT_WORD);
        if (!DEFAULT_WORD.equals(v.strip())) {
            return v;
        }
        if (Boolean.parseBoolean(env.getProperty(com.ash.drishti.packs.edition.Editions.SPEC + ".packs.none", "false"))) {
            return "";                                         // a distribution of no packs (Drishti Genomics): none, not the default list
        }
        java.util.List<String> edition = com.ash.drishti.packs.edition.Editions.of(env).packs();
        return edition.isEmpty() ? DEFAULT_ENABLED : String.join(",", edition);
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

    /**
     * Further pack folders, comma-separated, looked in after the packs folder: {@code drishti.packs.extra-dirs} (retired example packs),
     * then the {@code packs/} folder of every installed module a site made (below), so its packs come with it.
     */
    public static String extraDirs(Environment env) {
        String set = env.getProperty("drishti.packs.extra-dirs", "");
        java.util.List<String> all = new java.util.ArrayList<>();
        if (!set.isBlank()) {
            all.add(set);
        }
        for (InstalledPacks m : installedModulePacks(env)) {
            all.add(m.folder().toString());
        }
        return String.join(",", all);
    }

    /** The pack folder of one installed content module and the packs its manifest lists ({@code packs: {folder, list}}). */
    public record InstalledPacks(String module, Path folder, java.util.List<String> packs) {
    }

    /**
     * The packs of the modules installed on this node ({@code <drishti.modules.path>/installed/<id>/<version>/}, the version that
     * {@code installed/active.yaml} names) that are not first-party: a site's own content module (`drishti.py pack new`) brings its
     * packs, which load with it and need no {@code DRISHTI_PACKS} entry. The product's own modules keep their packs where the
     * distribution names them.
     */
    public static java.util.List<InstalledPacks> installedModulePacks(Environment env) {
        java.util.List<InstalledPacks> out = new java.util.ArrayList<>();
        String setting = env.getProperty("drishti.modules.path", Path.of(dataDir(env), "modules").toString());
        com.fasterxml.jackson.databind.ObjectMapper yaml = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        for (String s : setting.split("[," + java.io.File.pathSeparator + "]")) {
            Path installed = s.isBlank() ? null : Path.of(s.strip()).toAbsolutePath().normalize().resolve("installed");
            if (installed == null || !Files.isRegularFile(installed.resolve("active.yaml"))) {
                continue;
            }
            try {
                Object mods = yaml.readValue(installed.resolve("active.yaml").toFile(), java.util.Map.class).get("modules");
                if (!(mods instanceof java.util.Map<?, ?> active)) {
                    continue;
                }
                for (java.util.Map.Entry<?, ?> e : active.entrySet()) {
                    Path dir = installed.resolve(String.valueOf(e.getKey())).resolve(String.valueOf(e.getValue()));
                    Path manifest = dir.resolve("module.yaml");
                    if (!Files.isRegularFile(manifest)) {
                        continue;
                    }
                    java.util.Map<?, ?> m = yaml.readValue(manifest.toFile(), java.util.Map.class);
                    if ("first-party".equals(String.valueOf(m.get("origin"))) || !(m.get("packs") instanceof java.util.Map<?, ?> packs)) {
                        continue;
                    }
                    Path folder = dir.resolve(String.valueOf(packs.get("folder") == null ? "packs" : packs.get("folder"))).normalize();
                    java.util.List<String> names = packs.get("list") instanceof java.util.List<?> l ? l.stream().map(String::valueOf).toList() : java.util.List.of();
                    if (folder.startsWith(dir) && Files.isDirectory(folder)) {
                        out.add(new InstalledPacks(String.valueOf(e.getKey()), folder, names));
                    }
                }
            } catch (java.io.IOException | RuntimeException ex) {
                System.getLogger(PackPaths.class.getName()).log(System.Logger.Level.WARNING, "installed modules under {0}: {1}", installed, ex.getMessage());
            }
        }
        return out;
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
