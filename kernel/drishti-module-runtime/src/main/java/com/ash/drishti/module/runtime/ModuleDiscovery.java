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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovery (KERNEL_AND_MODULES.md section 4.2): reads the manifest (V1) of each exploded module folder it is given: a folder with a
 * {@code module.yaml} at its root, or a folder whose subfolders each have one. The module boot's candidates ({@link ModuleBoot.Result#candidates})
 * do not come through here; this is the tools' and the tests' way to load a module from a folder. A manifest that does not read is reported
 * as refused, never thrown.
 */
public final class ModuleDiscovery {

    /** What discovery found: the candidates, and the modules refused at V1 (id → words). */
    public record Found(List<ModuleCandidate> candidates, Map<String, ModuleRefusedException> refused) {
    }

    private ModuleDiscovery() {
    }

    /** Discovers the modules of the folders; the first folder holding an id wins. */
    public static Found discover(List<Path> folders) {
        List<ModuleCandidate> out = new ArrayList<>();
        Map<String, ModuleRefusedException> refused = new LinkedHashMap<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            List<Path> dirs = Files.isRegularFile(folder.resolve(DmodManifest.FILE)) ? List.of(folder) : list(folder);
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir) || dir.getFileName().toString().startsWith(".") || !Files.isRegularFile(dir.resolve(DmodManifest.FILE))) {
                    continue;
                }
                String where = dir.getFileName().toString();
                if (VersionRange.isVersion(where) && dir.getParent() != null && dir.getParent().getFileName() != null) {
                    where = dir.getParent().getFileName().toString();           // <id>/<version>: a refusal is named by the id
                }
                try (InputStream in = Files.newInputStream(dir.resolve(DmodManifest.FILE))) {
                    DmodManifest m = DmodManifest.read(in, where);
                    if (seen.putIfAbsent(m.id(), true) == null) {
                        out.add(new ModuleCandidate(m, dir, null));
                    }
                } catch (ModuleRefusedException e) {
                    refused.put(where, e);
                } catch (IOException e) {
                    refused.put(where, new ModuleRefusedException("V1", dir + ": module.yaml does not read: " + e.getMessage(), e));
                }
            }
        }
        out.sort(Comparator.comparing(ModuleCandidate::id));
        return new Found(List.copyOf(out), refused);
    }

    private static List<Path> list(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
