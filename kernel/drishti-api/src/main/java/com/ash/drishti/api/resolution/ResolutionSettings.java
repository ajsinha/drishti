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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Where the layers are, one setting per concept (docs/architecture/KERNEL_AND_MODULES.md section 18.2); no folder is derived from another
 * folder's position.
 *
 * @param siteDir      the site's packs ({@code drishti.packs.site-dir}, its former name {@code drishti.packs.dir}; default {@code ./config/packs})
 * @param installedDir packs an administrator deployed, part of the site ({@code drishti.packs.installed-dir}; default {@code <data>/packs/installed})
 * @param kernelDir    the kernel's packs ({@code drishti.kernel.packs-dir}; default {@code ./config/packs/core}), never read as site packs
 * @param modulesHome  the modules folder, shown in the layout (null when the settings come from a process: its modules are the boot's)
 * @param homes        the module boot's result; null: none was booted
 */
public record ResolutionSettings(Path siteDir, Path installedDir, Path kernelDir, Path modulesHome, List<Path> extraDirs,
        ModuleHomes homes) {

    public ResolutionSettings {
        extraDirs = extraDirs == null ? List.of() : List.copyOf(extraDirs);
    }

    /** As above, no module booted. */
    public ResolutionSettings(Path siteDir, Path installedDir, Path kernelDir, Path modulesHome, List<Path> extraDirs) {
        this(siteDir, installedDir, kernelDir, modulesHome, extraDirs, null);
    }

    /** These settings with the module boot's result as the modules. */
    public ResolutionSettings withHomes(ModuleHomes h) {
        return new ResolutionSettings(siteDir, installedDir, kernelDir, modulesHome, extraDirs, h);
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * The settings of a server or a tool, from its resolved properties (null when not set). The modules: the module boot's result the
     * properties carry ({@link ModuleHomes#PROPERTY}), else none.
     */
    public static ResolutionSettings of(Function<String, String> property) {
        String data = or(property.apply("drishti.data.dir"), com.ash.drishti.api.Roots.data());
        String newSite = property.apply("drishti.packs.site-dir");
        String site = or(newSite, or(property.apply("drishti.packs.dir"), com.ash.drishti.api.Roots.config("packs")));
        return new ResolutionSettings(abs(site), abs(or(property.apply("drishti.packs.installed-dir"), data + "/packs/installed")),
                abs(or(property.apply("drishti.kernel.packs-dir"), site + "/core")), null,
                // a test's or a tool's own folders of packs (drishti.packs.extra-dirs, the retired example packs): part of the site, after it
                java.util.Arrays.stream(or(property.apply("drishti.packs.extra-dirs"), "").split(",")).map(String::strip).filter(x -> !x.isEmpty())
                        .map(ResolutionSettings::abs).toList(), ModuleHomes.of(property).orElse(null));
    }

    /** The pack resolver's layout of these settings. */
    public PackResolver.Layout layout() {
        return new PackResolver.Layout(siteDir, kernelDir, modulesHome, installedDir, extraDirs, homes);
    }

    /** Reads a YAML file into a map: empty for an empty file; a broken file throws, naming it. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> yaml(Path file) {
        try {
            String text = Files.readString(file);
            Object v = text.isBlank() ? null : YAML.readValue(text, new TypeReference<Object>() {});
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new UncheckedIOException(file + " cannot be read: " + e.getMessage().lines().findFirst().orElse(""), e);
        }
    }

    private static String or(String v, String d) {
        return v == null || v.isBlank() ? d : v.strip();
    }

    private static Path abs(String p) {
        return Path.of(p).toAbsolutePath().normalize();
    }
}
