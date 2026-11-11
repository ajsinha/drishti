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
package com.ash.drishti.module.runtime.placement;

import com.ash.drishti.module.runtime.DmodManifest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The parts a module declares (the manifest's {@code parts:}, KERNEL_AND_MODULES.md section 4.11.2), each with its resource class, its
 * memory and the API paths it answers (relative to {@code /api/v1/<prefix>/}). A request path belongs to the part with the longest matching
 * path; every other path is the {@code api} part's. A module that declares no parts has one: {@code api}, light.
 *
 * @param module   the module id
 * @param version  its version
 * @param prefix   its REST namespace ({@code /api/v1/<prefix>/})
 * @param inProcess true for a module still built into the server (today's banking): placed on every node until it becomes a module
 * @param parts    the parts by name, in the manifest's order
 */
public record ModuleParts(String module, String version, String prefix, boolean inProcess, Map<String, Part> parts) {

    /** One part: its name, resource class, memory ({@code heavy} only) and its API paths. */
    public record Part(String name, PartClass cls, long memoryMb, List<String> paths) {
        public Part {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    /** The parts of a module still built into the server: its API and pages, its compute and its feeds. */
    public static final List<Part> IN_PROCESS = List.of(new Part("api", PartClass.LIGHT, 0, List.of()),
            new Part("pages", PartClass.LIGHT, 0, List.of()), new Part("compute", PartClass.HEAVY, 0, List.of()),
            new Part("feeds", PartClass.FEED, 0, List.of()));

    public ModuleParts {
        parts = Map.copyOf(new LinkedHashMap<>(parts));
        prefix = prefix == null || prefix.isBlank() ? module : prefix;
    }

    /** A module of today's server (no {@code .dmod} yet): counted as placed on every node, so its behaviour is unchanged. */
    public static ModuleParts inProcess(String id, String version) {
        Map<String, Part> m = new LinkedHashMap<>();
        IN_PROCESS.forEach(p -> m.put(p.name(), p));
        return new ModuleParts(id, version, id, true, m);
    }

    /** One module's parts from code (a test, a host that reads parts elsewhere). */
    public static ModuleParts of(String id, String version, String prefix, List<Part> parts) {
        Map<String, Part> m = new LinkedHashMap<>();
        parts.forEach(p -> m.put(p.name(), p));
        return new ModuleParts(id, version, prefix, false, m);
    }

    /** The parts a loaded module's manifest declares; refuses a part without a known class. */
    @SuppressWarnings("unchecked")
    public static ModuleParts of(DmodManifest manifest) {
        List<Part> out = new ArrayList<>();
        if (manifest.section("parts") instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                Map<String, Object> spec = e.getValue() instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
                long mb = spec.get("memory-mb") instanceof Number n ? n.longValue() : 0;
                List<String> paths = spec.get("paths") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
                out.add(new Part(String.valueOf(e.getKey()), PartClass.of(String.valueOf(spec.getOrDefault("class", "light"))), mb, paths));
            }
        }
        if (out.isEmpty()) {
            out.add(new Part("api", PartClass.LIGHT, 0, List.of()));
        }
        return of(manifest.id(), manifest.version(), manifest.api() == null ? null : manifest.api().prefix(), out);
    }

    /** The part a request path (after {@code /api/v1/<prefix>/}) belongs to: the longest declared path, else {@code api}. */
    public Optional<Part> partFor(String rest) {
        String r = rest == null ? "" : rest.startsWith("/") ? rest.substring(1) : rest;
        Part best = null;
        int len = -1;
        for (Part p : parts.values()) {
            for (String path : p.paths()) {
                String q = path.startsWith("/") ? path.substring(1) : path;
                if ((r.equals(q) || r.startsWith(q.endsWith("/") ? q : q + "/")) && q.length() > len) {
                    best = p;
                    len = q.length();
                }
            }
        }
        return Optional.ofNullable(best != null ? best : parts.get("api"));
    }
}
