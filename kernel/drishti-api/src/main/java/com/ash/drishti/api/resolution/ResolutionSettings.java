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
import java.util.Map;
import java.util.function.Function;

/**
 * Where the layers are, one setting per concept (docs/architecture/KERNEL_AND_MODULES.md section 18.2); no folder is derived from another
 * folder's position.
 *
 * @param siteDir      the site's packs ({@code drishti.packs.site-dir}, its former name {@code drishti.packs.dir}; default {@code ./config/packs})
 * @param installedDir packs an administrator deployed, part of the site ({@code drishti.packs.installed-dir}; default {@code <data>/packs/installed})
 * @param kernelDir    the kernel's packs ({@code drishti.kernel.packs-dir}; default {@code ./config/packs/core}), never read as site packs
 * @param modulesHome  the modules shipped as folders ({@code drishti.modules.home}; default {@code ./modules})
 * @param modulesPath  the store of modules installed from a {@code .dmod} ({@code drishti.modules.path}, else {@code drishti.module-deploy.dir};
 *                     default {@code <data>/modules})
 */
public record ResolutionSettings(Path siteDir, Path installedDir, Path kernelDir, Path modulesHome, Path modulesPath) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The settings of a server or a tool, from its resolved properties (null when not set). */
    public static ResolutionSettings of(Function<String, String> property) {
        String data = or(property.apply("drishti.data.dir"), "./data");
        String newSite = property.apply("drishti.packs.site-dir");
        String site = or(newSite, or(property.apply("drishti.packs.dir"), "./config/packs"));
        String home = or(property.apply("drishti.modules.home"), or(property.apply("drishti.module-manifests-dir"), "./modules"));
        String store = or(property.apply("drishti.modules.path"), or(property.apply("drishti.module-deploy.dir"), data + "/modules"));
        return new ResolutionSettings(abs(site), abs(or(property.apply("drishti.packs.installed-dir"), data + "/packs/installed")),
                abs(or(property.apply("drishti.kernel.packs-dir"), site + "/core")), abs(home), abs(store));
    }

    /** The pack resolver's layout of these settings. */
    public PackResolver.Layout layout() {
        return new PackResolver.Layout(siteDir, kernelDir, modulesHome, modulesPath, installedDir);
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
