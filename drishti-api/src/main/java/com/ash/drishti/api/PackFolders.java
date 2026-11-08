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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Where a pack's folder is: the one rule every Java reader follows (docs/architecture/DOMAIN_MODULES.md section 8). A packs root
 * ({@code config/packs}, the installed packs, {@code examples/packs}) holds packs directly ({@code <root>/<pack>/pack.yaml}) or one level
 * down in <b>domain folders</b> ({@code <root>/<domain>/<pack>/pack.yaml}): a folder of the root with no {@code pack.yaml} of its own.
 * The shipped packs live in their domain's folder ({@code config/packs/banking/trading}). The console's and the tools' Python twin is
 * {@code drishti-console/core/pack_paths.py}; tests hold both to the layout.
 */
public final class PackFolders {

    private PackFolders() {
    }

    /** The folders packs are in under a root: the root itself, then each domain folder in name order. */
    public static List<Path> roots(Path root) {
        List<Path> out = new ArrayList<>();
        out.add(root);
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).filter(d -> !Files.isRegularFile(d.resolve("pack.yaml"))).sorted().forEach(out::add);
        } catch (IOException e) {
            // an unreadable folder has no domain folders to offer
        }
        return out;
    }

    /** Every pack folder under a root by pack name (the first folder wins a name), in name order. */
    public static Map<String, Path> all(Path root) {
        Map<String, Path> out = new java.util.TreeMap<>();
        for (Path d : roots(root)) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.list(d)) {
                s.filter(p -> Files.isRegularFile(p.resolve("pack.yaml"))).sorted().forEach(p -> out.putIfAbsent(p.getFileName().toString(), p));
            } catch (IOException e) {
                // an unreadable folder has no packs
            }
        }
        return new LinkedHashMap<>(out);
    }

    /** The pack folders under a root, in pack-name order. */
    public static List<Path> list(Path root) {
        return List.copyOf(all(root).values());
    }

    /** A pack's folder under a root, or {@code <root>/<name>} (not there) when no folder holds it. */
    public static Path of(Path root, String name) {
        for (Path d : roots(root)) {
            Path p = d.resolve(name).normalize();
            if (p.startsWith(d) && Files.isRegularFile(p.resolve("pack.yaml"))) {
                return p;
            }
        }
        return root.resolve(name).normalize();
    }
}
