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

    /**
     * Every pack folder under a root by pack name, in name order. A name held by two folders of the root (two domain folders, or the root
     * and a domain folder) is refused: it is in neither ({@link #duplicates} names both folders).
     */
    public static Map<String, Path> all(Path root) {
        Map<String, Path> out = new java.util.TreeMap<>();
        holders(root).forEach((name, at) -> {
            if (at.size() == 1) {
                out.put(name, at.get(0));
            }
        });
        return new LinkedHashMap<>(out);
    }

    /**
     * The pack names two folders of a root hold, each with the problem in words naming both folders: such a name is never resolved to one
     * of them (a pack read from the wrong domain folder would bring its rules, roles and masks unseen).
     */
    public static Map<String, String> duplicates(Path root) {
        Map<String, String> out = new java.util.TreeMap<>();
        holders(root).forEach((name, at) -> {
            if (at.size() > 1) {
                out.put(name, twice(name, at));
            }
        });
        return out;
    }

    /**
     * Refuses a pack name that two folders of one packs root hold, among {@code dirs} (roots as {@link #roots} lists them, one root after
     * another): throws {@link IllegalStateException} naming both. One root holding it once, or two roots holding it (an installed pack
     * before a shipped one), is fine.
     */
    public static void requireOne(List<Path> dirs, String name) {
        Map<Path, List<Path>> byRoot = new LinkedHashMap<>();
        for (Path d : dirs) {
            Path p = d.resolve(name).normalize();
            if (p.startsWith(d.normalize()) && Files.isRegularFile(p.resolve("pack.yaml"))) {
                Path group = d.getParent() != null && dirs.contains(d.getParent()) ? d.getParent() : d;
                byRoot.computeIfAbsent(group, k -> new ArrayList<>()).add(p);
            }
        }
        for (List<Path> at : byRoot.values()) {
            if (at.size() > 1) {
                throw new IllegalStateException(twice(name, at));
            }
        }
    }

    private static String twice(String name, List<Path> at) {
        return "pack '" + name + "' is in two folders: " + at.get(0) + " and " + at.get(1)
                + "; neither is read: a pack's name is unique across a packs folder and its domain folders (keep one, rename or remove the other)";
    }

    private static Map<String, List<Path>> holders(Path root) {
        Map<String, List<Path>> found = new java.util.TreeMap<>();
        for (Path d : roots(root)) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.list(d)) {
                s.filter(p -> Files.isRegularFile(p.resolve("pack.yaml"))).sorted()
                        .forEach(p -> found.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>()).add(p));
            } catch (IOException e) {
                // an unreadable folder has no packs
            }
        }
        return found;
    }

    /** The pack folders under a root, in pack-name order. */
    public static List<Path> list(Path root) {
        return List.copyOf(all(root).values());
    }

    /** A pack's folder under a root, or {@code <root>/<name>} (not there) when no folder holds it; two folders of the root holding it: refused. */
    public static Path of(Path root, String name) {
        requireOne(roots(root.normalize()), name);                  // two domain folders holding it: refused, never one of them
        for (Path d0 : roots(root)) {
            Path d = d0.normalize();                                // a root such as module/../config/packs
            Path p = d.resolve(name).normalize();
            if (p.startsWith(d) &&Files.isRegularFile(p.resolve("pack.yaml"))) {
                return p;
            }
        }
        return root.resolve(name).normalize();
    }
}
