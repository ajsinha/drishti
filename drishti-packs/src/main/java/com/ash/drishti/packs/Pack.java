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
package com.ash.drishti.packs;

import java.nio.file.Path;
import java.util.Map;

/**
 * A loaded domain pack.
 *
 * @param name pack name ({@code finance})
 * @param version pack version
 * @param title display title
 * @param description one paragraph
 * @param dir the pack's directory
 * @param manifest the parsed {@code pack.yaml}
 */
public record Pack(String name, String version, String title, String description, Path dir, Map<String, Object> manifest) {

    /** The entity kinds this pack owns. */
    @SuppressWarnings("unchecked")
    /** The pack's short code ({@code MKT}): typed alone on the command line it opens the pack's overview; "" when none. */
    public String code() {
        Object c = manifest.get("code");
        return c == null ? "" : String.valueOf(c).trim().toUpperCase(java.util.Locale.ROOT);
    }

    public java.util.List<String> kinds() {
        Object k = manifest.get("kinds");
        return k instanceof java.util.List<?> l ? l.stream().map(String::valueOf).toList() : java.util.List.of();
    }

    /**
     * The packs this one inherits from, in declaration order: {@code extends:} (and the older {@code requires:}, read
     * the same way). Everything a parent brings comes with the child; where two contributions conflict, the more
     * specific one wins: the child over its parents, and the rightmost parent over those to its left.
     */
    public java.util.List<String> parents() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String key : new String[] {"extends", "requires"}) {
            if (manifest.get(key) instanceof java.util.List<?> l) {
                l.forEach(x -> out.add(String.valueOf(x).trim()));
            }
        }
        return java.util.List.copyOf(out);
    }

    /** Same as {@link #parents()}; kept for callers written before inheritance. */
    public java.util.List<String> requires() {
        return parents();
    }

    /**
     * The connector definitions the pack suggests, by name: the {@code connector-templates:} mapping, and the older
     * {@code connectors:} mapping read as templates. A template is never in force by itself once a connector file of the
     * same name exists; it is what a file is generated from, and what is used when a file cannot be written.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> connectorTemplates() {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (String key : new String[] {"connectors", "connector-templates"}) {
            if (manifest.get(key) instanceof Map<?, ?> m) {
                m.forEach((k, v) -> out.put(String.valueOf(k), v instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of()));
            }
        }
        return out;
    }

    /**
     * Every connector the pack names, in order: a {@code connectors:} list, the templates, and the connectors its
     * {@code routes:} send kinds to. These are logical names of site connectors ({@code config/connectors/<name>.yaml}).
     */
    public java.util.List<String> connectorRefs() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (manifest.get("connectors") instanceof java.util.List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x).trim()));
        }
        out.addAll(connectorTemplates().keySet());
        if (manifest.get("routes") instanceof Map<?, ?> r) {
            r.values().forEach(v -> out.add(String.valueOf(v).trim()));
        }
        return java.util.List.copyOf(out);
    }

    /** The kind-to-connector routes the pack declares. */
    public Map<String, String> routes() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (manifest.get("routes") instanceof Map<?, ?> r) {
            r.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        }
        return out;
    }

    public Path resolve(String relative) {
        return dir.resolve(relative).normalize();
    }
}
