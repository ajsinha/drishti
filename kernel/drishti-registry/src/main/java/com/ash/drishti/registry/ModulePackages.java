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
package com.ash.drishti.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one reader of the modules that come as packages (KERNEL_AND_MODULES.md section 3.3), for the registry and the distributions alike:
 * the distribution's first-party ones (the ids of its {@code META-INF/drishti/first-party.yaml}, each read from its
 * {@code drishti/modules/<id>-<version>.dmod} on the class path), then every other active module of the module store
 * ({@code <store>/installed/active.yaml}, each {@code installed/<id>/<version>/module.yaml}). A package's manifest wins over a manifest
 * folder's copy of the same module.
 */
public final class ModulePackages {

    /**
     * One package's manifest.
     *
     * @param active false for a first-party package an administrator removed or switched off (the module store's {@code registry.json})
     * @param source in words: {@code the distribution's genomics.dmod}, {@code the module store's genomics}
     */
    public record Package(String id, Map<String, Object> manifest, boolean active, String source) {
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ModulePackages() {
    }

    /**
     * The module store of a server's settings: {@code drishti.module-deploy.dir}, else {@code <drishti.data.dir>/modules}.
     *
     * @param property a setting, resolved (null when not set)
     */
    public static Path store(java.util.function.Function<String, String> property) {
        String deploy = property.apply("drishti.module-deploy.dir");
        if (deploy != null && !deploy.isBlank()) {
            return Path.of(deploy.strip());
        }
        String data = property.apply("drishti.data.dir");
        return Path.of(data == null || data.isBlank() ? "./data" : data.strip(), "modules");
    }

    /**
     * The packaged modules, first-party then installed, skipping the ids in {@code skip} (modules read already, from their jars).
     *
     * @param loader   where the shipped packages and {@code first-party.yaml} are looked for
     * @param store    the module store (null: none)
     * @param warnings where what does not read is told
     */
    @SuppressWarnings("unchecked")
    public static List<Package> read(ClassLoader loader, Path store, Set<String> skip, List<String> warnings) {
        List<Package> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(skip);
        Map<String, String> states = new LinkedHashMap<>();
        Path registry = store == null ? null : store.resolve("registry.json");
        if (registry != null && Files.isRegularFile(registry)) {
            try {
                for (Object o : new ObjectMapper().readValue(registry.toFile(), List.class)) {
                    if (o instanceof Map<?, ?> e && e.get("id") != null) {
                        states.put(String.valueOf(e.get("id")), String.valueOf(e.get("state")));
                    }
                }
            } catch (IOException | RuntimeException e) {
                warnings.add("the module store's registry.json: " + e.getMessage());
            }
        }
        try (InputStream in = loader.getResourceAsStream("META-INF/drishti/first-party.yaml")) {
            Map<String, Object> fp = in == null ? Map.of() : YAML.readValue(in, Map.class);
            if (fp != null && fp.get("modules") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> e && e.get("id") != null && !seen.contains(String.valueOf(e.get("id")))) {
                        String id = String.valueOf(e.get("id"));
                        Map<String, Object> m = shippedManifest(loader, "drishti/modules/" + id + "-" + e.get("version") + ".dmod");
                        if (m != null) {
                            seen.add(id);
                            out.add(new Package(id, m, "ACTIVE".equals(states.getOrDefault(id, "ACTIVE")), "the distribution's " + id + ".dmod"));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            warnings.add("first-party.yaml: " + e.getMessage());
        }
        Path active = store == null ? null : store.resolve("installed").resolve("active.yaml");
        if (active == null || !Files.isRegularFile(active)) {
            return out;
        }
        Map<String, Object> versions;
        try {
            Map<String, Object> m = readMap(active);                 // the node writes modules: {id: version}; the map at the top reads too
            versions = m.get("modules") instanceof Map<?, ?> mods ? (Map<String, Object>) mods : m;
        } catch (RuntimeException e) {
            warnings.add(active.getFileName() + ": " + e.getMessage());
            return out;
        }
        for (Map.Entry<String, Object> e : versions.entrySet()) {
            String id = e.getKey();
            Path manifest = store.resolve("installed").resolve(id).resolve(String.valueOf(e.getValue())).resolve("module.yaml");
            if (seen.contains(id) || !Files.isRegularFile(manifest) || !"ACTIVE".equals(states.getOrDefault(id, "ACTIVE"))) {
                continue;
            }
            try {
                out.add(new Package(id, readMap(manifest), true, "the module store's " + id));
                seen.add(id);
            } catch (RuntimeException ex) {
                warnings.add("the module store's " + id + ": " + ex.getMessage());
            }
        }
        return out;
    }

    /** The manifest inside a package on the class path, or null when the package is not there. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> shippedManifest(ClassLoader loader, String resource) throws IOException {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(in);
            for (java.util.zip.ZipEntry en; (en = z.getNextEntry()) != null; ) {
                if (en.getName().equals("module.yaml")) {
                    return YAML.readValue(z.readAllBytes(), Map.class);
                }
            }
            return null;
        }
    }

    private static Map<String, Object> readMap(Path f) {
        try {
            Map<String, Object> m = YAML.readValue(f.toFile(), YAML.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            return m == null ? Map.of() : m;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read: " + e.getMessage(), e);
        }
    }
}
