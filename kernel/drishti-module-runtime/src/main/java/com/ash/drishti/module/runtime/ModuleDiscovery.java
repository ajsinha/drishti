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
package com.ash.drishti.module.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Discovery (KERNEL_AND_MODULES.md section 4.2): lists every {@code <path>/<id>/<version>/module.yaml} of the module folders in order, picks
 * the version {@code active.yaml} names (else the highest), and reads each manifest (V1). The first folder holding an id wins. Nothing
 * else of a module is touched. A manifest that does not read is reported as refused, never thrown.
 */
public final class ModuleDiscovery {

    /** What discovery found: the candidates, and the modules refused at V1 (id → words). */
    public record Found(List<ModuleCandidate> candidates, Map<String, ModuleRefusedException> refused) {
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ModuleDiscovery() {
    }

    /** Discovers the modules of the folders; {@code active} maps an id to its active version (may be empty). */
    /** The host's own folders a modules folder may hold beside the modules (the deploy store and its work areas): never a module id. */
    public static final java.util.Set<String> HOST_FOLDERS = java.util.Set.of("installed", "staging", "tombstones", "store", "lib");

    public static Found discover(List<Path> folders, Map<String, String> active) {
        List<ModuleCandidate> out = new ArrayList<>();
        Map<String, ModuleRefusedException> refused = new LinkedHashMap<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            if (Files.isRegularFile(folder.resolve(DmodManifest.FILE))) {    // an exploded module itself (development mode only)
                try (InputStream in = Files.newInputStream(folder.resolve(DmodManifest.FILE))) {
                    DmodManifest m = DmodManifest.read(in, folder.getFileName().toString());
                    if (seen.putIfAbsent(m.id(), true) == null) {
                        out.add(new ModuleCandidate(m, folder, null));
                    }
                } catch (ModuleRefusedException e) {
                    refused.put(folder.getFileName().toString(), e);
                } catch (IOException e) {
                    refused.put(folder.getFileName().toString(), new ModuleRefusedException("V1", folder + ": module.yaml does not read: "
                            + e.getMessage(), e));
                }
                continue;
            }
            for (Path idDir : list(folder)) {
                String id = idDir.getFileName().toString();
                if (!Files.isDirectory(idDir) || seen.containsKey(id) || id.startsWith(".") || HOST_FOLDERS.contains(id)) {
                    continue;
                }
                Optional<Path> version = pick(idDir, active.get(id));
                if (version.isEmpty()) {
                    continue;
                }
                seen.put(id, true);
                Path dir = version.get();
                try (InputStream in = Files.newInputStream(dir.resolve(DmodManifest.FILE))) {
                    DmodManifest m = DmodManifest.read(in, id + " " + dir.getFileName());
                    Path dmod = idDir.resolve(Dmod.fileName(m.id(), m.version()));
                    out.add(new ModuleCandidate(m, dir, Files.isRegularFile(dmod) ? dmod : null));
                } catch (ModuleRefusedException e) {
                    refused.put(id, e);
                } catch (IOException e) {
                    refused.put(id, new ModuleRefusedException("V1", id + ": module.yaml does not read: " + e.getMessage(), e));
                }
            }
        }
        out.sort(Comparator.comparing(ModuleCandidate::id));
        return new Found(List.copyOf(out), refused);
    }

    /** Reads {@code active.yaml} ({@code modules: {id: version}} or the map at the top); empty when the file is absent. */
    public static Map<String, String> readActive(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Map<String, Object> m = DmodManifest.map(YAML.readValue(file.toFile(), Object.class));
            Map<String, Object> mods = m.get("modules") instanceof Map<?, ?> ? DmodManifest.map(m.get("modules")) : m;
            Map<String, String> out = new LinkedHashMap<>();
            mods.forEach((k, v) -> {
                if (DmodManifest.str(v) != null) {
                    out.put(k, DmodManifest.str(v));
                }
            });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<Path> pick(Path idDir, String active) {
        List<Path> versions = list(idDir).stream()
                .filter(p -> Files.isRegularFile(p.resolve(DmodManifest.FILE)) && VersionRange.isVersion(p.getFileName().toString())).toList();
        if (active != null) {
            return versions.stream().filter(p -> p.getFileName().toString().equals(active)).findFirst();
        }
        return versions.stream().max((a, b) -> VersionRange.compare(a.getFileName().toString(), b.getFileName().toString()));
    }

    private static List<Path> list(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
