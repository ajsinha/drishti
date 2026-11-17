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
package com.ash.drishti.api.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * <b>The old way, kept for the overlap of release i7a's increments I5 to I7 only; deleted in I8</b> (docs/plans/I7A_IMPACT.md, decision 5).
 * The one adapter between the readers and the modules of i6: the module folders under the modules folder ({@code drishti.modules.home},
 * {@code <dir>/module.yaml}) and the module store's active installs ({@code <store>/installed/active.yaml} naming
 * {@code installed/<id>/<version>/}, which win an id). When the modules folder holds packages ({@code *.dmod}), the module boot's result
 * ({@link ModuleHomes}) is published and nothing here is read; when it holds none, every reader takes its modules from here, read at each
 * ask (a deployment into the store shows at once, as in i6).
 *
 * <p>The only class that reads {@code active.yaml} or names the modules folder's settings (guard G9, {@code NoModuleFolderReaderTest}, names
 * it as its one exception until I8).
 */
public final class LegacyModules {

    /** The modules folder's setting; the module boot reads the same folder for its packages. */
    public static final String HOME = "drishti.modules.home";
    /** Its former name. */
    public static final String HOME_OLD = "drishti.module-manifests-dir";
    /** Its environment variable. */
    public static final String HOME_ENV = "DRISHTI_MODULES_HOME";
    /** The module store's setting. */
    public static final String STORE = "drishti.modules.path";
    /** The deployment's setting of the store. */
    public static final String DEPLOY_STORE = "drishti.module-deploy.dir";
    /** The store's folder of installed modules. */
    public static final String INSTALLED = "installed";
    /** The store's file that names each installed module's active version. */
    public static final String ACTIVE = "active.yaml";
    /** {@code drishti.modules.home=none}: no module folders (a kernel-only server; the unit tests). */
    public static final String NONE = "none";

    /** The folders under the modules folder without a module.yaml already noted (once each). */
    private static final java.util.Set<Path> NOTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** One module folder of the old layout and where it came from in words ({@code trading}, {@code the module store's genomics 1.2.0}). */
    public record Found(Path dir, String source) {
    }

    private LegacyModules() {
    }

    /** The modules folder a property lookup names ({@link #HOME}, {@link #HOME_OLD}, else {@code <home>/modules}), as given (maybe relative). */
    public static String folderSetting(Function<String, String> property) {
        return or(property.apply(HOME), or(property.apply(HOME_OLD), com.ash.drishti.api.Roots.named(HOME, HOME_ENV, "modules").toString()));
    }

    /** The module store a property lookup names: {@link #STORE}, else {@link #DEPLOY_STORE}, else {@code <data>/modules}. */
    public static String storeSetting(Function<String, String> property) {
        return or(property.apply(STORE), or(property.apply(DEPLOY_STORE), dataSetting(property) + "/modules"));
    }

    /** The deployment's store ({@link #DEPLOY_STORE}, else {@code <data>/modules}): where the module node installs. */
    public static Path deployStore(Function<String, String> property) {
        String deploy = property.apply(DEPLOY_STORE);
        return Path.of(deploy != null && !deploy.isBlank() ? deploy.strip() : dataSetting(property) + "/modules");
    }

    private static String dataSetting(Function<String, String> property) {
        return or(property.apply("drishti.data.dir"), com.ash.drishti.api.Roots.data());
    }

    /** The store's file of active versions. */
    public static Path activeFile(Path store) {
        return store.resolve(INSTALLED).resolve(ACTIVE);
    }

    /** The folder of an installed version in the store. */
    public static Path installed(Path store, String id, String version) {
        return store.resolve(INSTALLED).resolve(id).resolve(version).normalize();
    }

    /**
     * Reads an {@code active.yaml} ({@code modules: {id: version}}, or the map at the top): id → version; empty when the file is absent. A
     * file that does not read throws, naming it.
     */
    public static Map<String, String> readActive(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            Object v = YAML.readValue(file.toFile(), Object.class);
            Map<?, ?> m = v instanceof Map<?, ?> x ? x : Map.of();
            Map<?, ?> mods = m.get("modules") instanceof Map<?, ?> x ? x : m;
            mods.forEach((k, val) -> {
                if (val != null && !(val instanceof Map) && !(val instanceof List)) {
                    out.put(String.valueOf(k), String.valueOf(val));
                }
            });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(file + " cannot be read: " + e.getMessage(), e);
        }
    }

    /**
     * Every module of the old layout, in reading order: each folder of the modules folder with a {@code module.yaml} (its name as its
     * source), then each active install of the store (its view under the site's overrides, {@link ModuleOverrides#installed}), which a
     * reader keyed by id lets win. A broken {@code active.yaml} is a problem, never thrown.
     */
    public static List<Found> read(Path folder, Path store, List<String> problems) {
        List<Found> out = new ArrayList<>();
        if (folder != null && Files.isDirectory(folder)) {
            for (Path dir : dirs(folder)) {
                if (Files.isRegularFile(dir.resolve(ModuleHomes.MANIFEST))) {
                    out.add(new Found(dir, folder.relativize(dir).toString().replace('\\', '/')));
                } else if (NOTED.add(dir)) {                                   // an old build folder (modules/dist): a note, not a failure
                    System.getLogger(LegacyModules.class.getName()).log(System.Logger.Level.INFO, dir + ": no module.yaml, not a module, "
                            + "skipped (an old build folder? delete it)");
                }
            }
        }
        Path installed = store == null ? null : store.resolve(INSTALLED);
        if (installed != null && Files.isRegularFile(installed.resolve(ACTIVE))) {
            try {
                for (Map.Entry<String, String> e : readActive(installed.resolve(ACTIVE)).entrySet()) {
                    Path home = installed.resolve(e.getKey()).resolve(e.getValue()).normalize();
                    if (home.startsWith(installed) && Files.isRegularFile(home.resolve(ModuleHomes.MANIFEST))) {
                        out.add(new Found(ModuleOverrides.installed(home, e.getKey()), "the module store's " + e.getKey() + " " + e.getValue()));
                    }
                }
            } catch (RuntimeException e) {
                problems.add(installed.resolve(ACTIVE) + " cannot be read: " + e.getMessage());
            }
        }
        return out;
    }

    /** The homes of the old layout (origin development, no content hash), by id; a later folder of an id wins, as the resolver reads them. */
    public static ModuleHomes homes(Path folder, Path store) {
        Map<String, ModuleHomes.Home> out = new TreeMap<>();
        for (Found f : read(folder, store, new ArrayList<>())) {
            try {
                ModuleHomes.Home h = ModuleHomes.development(f.dir());
                out.put(h.id(), h);
            } catch (RuntimeException e) {
                // a manifest that does not read: the resolver reports the file itself
            }
        }
        return ModuleHomes.of(out.values());
    }

    /** The homes of the old layout a property lookup names (its modules folder and its store); none for {@code none}. */
    public static ModuleHomes homes(Function<String, String> property) {
        String folder = folderSetting(property);
        if (NONE.equals(folder)) {
            return ModuleHomes.none();
        }
        return homes(Path.of(folder).toAbsolutePath().normalize(), Path.of(storeSetting(property)).toAbsolutePath().normalize());
    }

    private static List<Path> dirs(Path root) {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory).map(p -> p.toAbsolutePath().normalize()).sorted().toList();
        } catch (IOException e) {
            return List.of();                                          // an unreadable folder has nothing to offer
        }
    }

    private static String or(String v, String d) {
        return v == null || v.isBlank() ? d : v.strip();
    }
}
