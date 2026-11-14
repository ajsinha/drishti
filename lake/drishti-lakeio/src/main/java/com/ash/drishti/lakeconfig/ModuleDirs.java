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
package com.ash.drishti.lakeconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The folders that hold the modules' files (the manifest, {@code dataload.yaml}, {@code connectors/}): each folder of the modules folder
 * that has a {@code module.yaml} (the shipped modules and the development folders), then each module installed from a {@code .dmod}
 * ({@code <modules>/installed/<id>/<version>/}: the version {@code installed/active.yaml} names, else the highest), unless the same id is
 * already there as a folder. The loader and the server read a module's content through this one list, so a module installed only as a
 * package contributes what a folder does.
 */
public final class ModuleDirs {

    private ModuleDirs() {
    }

    /** The module folders under {@code modulesRoot}, by id; empty when it is not a folder. */
    public static List<Path> of(Path modulesRoot) {
        List<Path> out = new ArrayList<>();
        if (modulesRoot == null || !Files.isDirectory(modulesRoot)) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (Path d : children(modulesRoot)) {
            String id = d.getFileName().toString();
            if (!Files.isRegularFile(d.resolve("module.yaml")) || id.startsWith(".") || !seen.add(id)) {
                continue;
            }
            out.add(d);
        }
        Path installed = modulesRoot.resolve("installed");
        Map<String, String> active = active(installed.resolve("active.yaml"));
        for (Path idDir : children(installed)) {
            String id = idDir.getFileName().toString();
            if (!Files.isDirectory(idDir) || seen.contains(id)) {
                continue;
            }
            List<Path> versions = children(idDir).stream().filter(v -> Files.isRegularFile(v.resolve("module.yaml"))).toList();
            Path pick = active.containsKey(id)
                    ? versions.stream().filter(v -> v.getFileName().toString().equals(active.get(id))).findFirst().orElse(null)
                    : versions.stream().max(Comparator.comparing(v -> v.getFileName().toString(), ModuleDirs::compare)).orElse(null);
            if (pick != null) {
                seen.add(id);
                out.add(pick);
            }
        }
        return out;
    }

    /** The module's id for a folder from {@link #of}: the folder's name, or, for an installed version, its parent's. */
    public static String idOf(Path moduleDir) {
        Path parent = moduleDir.getParent();
        return parent != null && parent.getParent() != null && "installed".equals(String.valueOf(parent.getParent().getFileName()))
                ? parent.getFileName().toString() : moduleDir.getFileName().toString();
    }

    private static List<Path> children(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Map<String, String> active(Path file) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonNode m = new YAMLMapper().readTree(Files.readString(file));
            JsonNode mods = m.path("modules").isObject() ? m.path("modules") : m;
            mods.fields().forEachRemaining(e -> {
                if (e.getValue().isValueNode()) {
                    out.put(e.getKey(), e.getValue().asText());
                }
            });
        } catch (IOException | RuntimeException e) {
            // an unreadable file names nothing: the highest version is used
        }
        return out;
    }

    /** Dotted versions by number, then as text. */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c = p.matches("\\d+") && q.matches("\\d+") ? Long.compare(Long.parseLong(p), Long.parseLong(q)) : p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
