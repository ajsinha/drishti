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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code config/placement.yaml} (KERNEL_AND_MODULES.md section 4.11): which nodes run which part of which module, by node name or by role
 * (a node label: a resource class such as {@code heavy} or {@code web}, or {@code role:<name>}). There is <b>no all-nodes default</b>: a
 * module or part not listed runs nowhere. {@code version} is the cluster configuration's version, raised by every applied change; a node
 * adopts a peer's placement only when its version is higher.
 *
 * <pre>
 * format: 1
 * version: 4
 * modules:
 *   sample:
 *     parts:
 *       api:     { nodes: [drishti-main], roles: [web] }
 *       compute: { roles: [heavy], max-nodes: 1 }
 *       feeds:   { nodes: [drishti-main, drishti-b] }      # one runner, elected in this order
 * </pre>
 */
public record Placement(int format, long version, Map<String, Map<String, Target>> modules) {

    /** Where one part goes: node names (in the order a singleton is elected), roles, the heavy limit and the all-nodes consent. */
    public record Target(List<String> nodes, List<String> roles, int maxNodes, boolean allowAll) {
        public Target {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!nodes.isEmpty()) {
                m.put("nodes", nodes);
            }
            if (!roles.isEmpty()) {
                m.put("roles", roles);
            }
            if (maxNodes > 0) {
                m.put("max-nodes", maxNodes);
            }
            if (allowAll) {
                m.put("allow-all", true);
            }
            return m;
        }
    }

    /** Nothing placed anywhere. */
    public static final Placement EMPTY = new Placement(1, 0, Map.of());

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    private static final Set<String> TARGET_FIELDS = Set.of("nodes", "roles", "max-nodes", "allow-all");

    public Placement {
        Map<String, Map<String, Target>> copy = new LinkedHashMap<>();
        (modules == null ? Map.<String, Map<String, Target>>of() : modules).forEach((k, v) -> copy.put(k, java.util.Collections
                .unmodifiableMap(new LinkedHashMap<>(v))));
        modules = java.util.Collections.unmodifiableMap(copy);
    }

    /** The target of one part, if placed. */
    public Optional<Target> target(String module, String part) {
        return Optional.ofNullable(modules.getOrDefault(module, Map.of()).get(part));
    }

    /** The same placement at another version. */
    public Placement withVersion(long v) {
        return new Placement(format, v, modules);
    }

    /** Reads the file's text; refuses with the field that is wrong (the message names it). Blank text is {@link #EMPTY}. */
    @SuppressWarnings("unchecked")
    public static Placement read(String text) {
        if (text == null || text.isBlank()) {
            return EMPTY;
        }
        Object raw;
        try {
            raw = YAML.readValue(text, Object.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("placement.yaml does not read: " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
        }
        if (raw == null) {
            return EMPTY;
        }
        if (!(raw instanceof Map<?, ?> top)) {
            throw new IllegalArgumentException("placement.yaml is not a map of fields");
        }
        for (Object k : top.keySet()) {
            if (!Set.of("format", "version", "modules").contains(String.valueOf(k))) {
                throw new IllegalArgumentException("placement.yaml: unknown field `" + k + "`");
            }
        }
        if (top.get("format") != null && !"1".equals(String.valueOf(top.get("format")))) {
            throw new IllegalArgumentException("placement.yaml: `format` must be 1");
        }
        long version = top.get("version") instanceof Number n ? n.longValue() : 0;
        Map<String, Map<String, Target>> modules = new LinkedHashMap<>();
        Object mods = top.get("modules");
        if (mods != null && !(mods instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("placement.yaml: `modules` is a map of module ids");
        }
        for (Map.Entry<?, ?> me : (mods == null ? Map.of() : (Map<?, ?>) mods).entrySet()) {
            String id = String.valueOf(me.getKey());
            Object body = me.getValue();
            Object parts = body instanceof Map<?, ?> b ? b.get("parts") : null;
            if (!(parts instanceof Map<?, ?> pm)) {
                throw new IllegalArgumentException("placement.yaml: module `" + id + "` needs `parts:` (each part with nodes or roles)");
            }
            Map<String, Target> targets = new LinkedHashMap<>();
            for (Map.Entry<?, ?> pe : pm.entrySet()) {
                String where = id + "." + pe.getKey();
                if (!(pe.getValue() instanceof Map<?, ?> t)) {
                    throw new IllegalArgumentException("placement.yaml: `" + where + "` is a map: nodes, roles, max-nodes, allow-all");
                }
                for (Object k : t.keySet()) {
                    if (!TARGET_FIELDS.contains(String.valueOf(k))) {
                        throw new IllegalArgumentException("placement.yaml: `" + where + "`: unknown field `" + k + "`");
                    }
                }
                Target target = new Target(list(t.get("nodes"), where + ".nodes"), list(t.get("roles"), where + ".roles"),
                        t.get("max-nodes") instanceof Number n ? n.intValue() : 0, Boolean.TRUE.equals(t.get("allow-all")));
                if (target.nodes().isEmpty() && target.roles().isEmpty()) {
                    throw new IllegalArgumentException("placement.yaml: `" + where + "` names no node and no role");
                }
                targets.put(String.valueOf(pe.getKey()), target);
            }
            modules.put(id, targets);
        }
        return new Placement(1, version, modules);
    }

    private static List<String> list(Object v, String where) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw new IllegalArgumentException("placement.yaml: `" + where + "` is a list");
        }
        List<String> out = new ArrayList<>();
        l.forEach(x -> out.add(String.valueOf(x).trim()));
        return out;
    }

    /** The file's text (the header comment, then the fields). */
    public String toYaml() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", 1);
        m.put("version", version);
        Map<String, Object> mods = new LinkedHashMap<>();
        modules.forEach((id, parts) -> {
            Map<String, Object> pm = new LinkedHashMap<>();
            parts.forEach((p, t) -> pm.put(p, t.toMap()));
            mods.put(id, Map.of("parts", pm));
        });
        m.put("modules", mods);
        try {
            return "# config/placement.yaml: where each module part runs (KERNEL_AND_MODULES.md section 4.11). No all-nodes default: a part\n"
                    + "# not listed runs nowhere. Changed by plan and apply (Admin -> Cluster or the API, two administrators); every node adopts the\n"
                    + "# highest version, so a hand edit takes effect only with a higher version.\n"
                    + YAML.writeValueAsString(m);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
