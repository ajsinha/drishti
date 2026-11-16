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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A module found by discovery: its manifest, its unpacked folder ({@code <path>/<id>/<version>/}) and the package kept beside it
 * ({@code <path>/<id>/<id>-<version>.dmod}, or inside the version folder's parent; null when absent).
 */
public record ModuleCandidate(DmodManifest manifest, Path dir, Path dmod) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public String id() {
        return manifest.id();
    }

    /** A YAML file of the module, relative to its folder, as a map (empty when absent); refuses one that does not read. */
    public Map<String, Object> yaml(String relative, String check) {
        if (relative == null) {
            return Map.of();
        }
        Path p = dir.resolve(relative).normalize();
        if (!p.startsWith(dir.normalize())) {
            throw new ModuleRefusedException(check, manifest.label() + ": " + relative + " leaves the module folder");
        }
        if (!Files.isRegularFile(p)) {
            throw new ModuleRefusedException(check, manifest.label() + ": " + relative + " is named by module.yaml but missing");
        }
        try {
            Object o = YAML.readValue(p.toFile(), Object.class);
            return o instanceof Map<?, ?> ? DmodManifest.map(o) : Map.of("items", o == null ? List.of() : o);
        } catch (IOException e) {
            throw new ModuleRefusedException(check, manifest.label() + ": " + relative + " does not read: "
                    + String.valueOf(e.getMessage()).lines().findFirst().orElse(""), e);
        }
    }

    /** The command words it brings: {@code functions} inline (a list) or a file ({@code functions.yaml}: {@code functions: [{word}]}). */
    public List<String> words() {
        Object f = manifest.section("functions");
        Object items = f instanceof String s ? yamlList(s, "functions") : f;
        List<String> out = new ArrayList<>();
        if (items instanceof List<?> l) {
            for (Object x : l) {
                String w = x instanceof Map<?, ?> m ? DmodManifest.str(m.get("word")) : DmodManifest.str(x);
                if (w != null) {
                    out.add(w.toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** The lens ids it brings ({@code lenses}: a file with {@code lenses: [{id}]}). */
    public List<String> lenses() {
        Object l = manifest.section("lenses");
        Object items = l instanceof String s ? yamlList(s, "lenses") : l;
        List<String> out = new ArrayList<>();
        if (items instanceof List<?> list) {
            for (Object x : list) {
                String id = x instanceof Map<?, ?> m ? DmodManifest.str(m.get("id")) : DmodManifest.str(x);
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private Object yamlList(String file, String key) {
        Map<String, Object> m = yaml(file, "V6");
        return m.containsKey(key) ? m.get(key) : m.get("items");
    }
}
