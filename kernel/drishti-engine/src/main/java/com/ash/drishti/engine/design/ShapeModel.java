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
package com.ash.drishti.engine.design;

import com.ash.drishti.engine.shape.RoleInfo;
import com.ash.drishti.engine.shape.Shape;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A shape read as a tree of {@link FieldNode}s: schema, roles and presence in one place, with {@code $ref}s resolved and
 * recursive trees cut at the second visit. Names that an expression cannot bind ({@code a-b}, spaces) are left out: a
 * drafted Sutra must be able to say every path it uses. Immutable after construction.
 *
 * <p>With samples, what a booking system's export does to a field is read from its values: a field blank ({@code ""}) in some
 * records and a number or a date in others (the shape calls it mixed), a date that is blank in some records, and a decimal written
 * as text ({@code "2332.9131"}) are typed by their non-blank values, so they are shown and formatted like the numbers and dates
 * they are. Whole numbers written as text stay text (ids, versions, codes).
 */
final class ShapeModel {

    private static final Pattern BINDABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> PLAIN = Set.of("string", "number", "integer", "null");

    private final JsonNode root;
    private final Map<String, RoleInfo> roles;
    private final SampleStats stats;
    private final Pattern idNames;
    private final FieldNode top;
    private final Map<String, FieldNode> byPath = new LinkedHashMap<>();

    ShapeModel(Shape shape) {
        this(shape, null, null);
    }

    /** With {@code stats}, fields the shape left mixed or untyped are typed from the samples' values; {@code idNames} stay text. */
    ShapeModel(Shape shape, SampleStats stats, Pattern idNames) {
        this.root = shape.schema();
        this.roles = shape.roles();
        this.stats = stats == null || stats.count() == 0 ? null : stats;
        this.idNames = idNames;
        this.top = build(root, "$", "$", 1.0, new HashSet<>());
    }

    FieldNode root() {
        return top;
    }

    FieldNode find(String path) {
        return byPath.get(path);
    }

    /** Every node, in schema order. */
    List<FieldNode> all() {
        return new ArrayList<>(byPath.values());
    }

    static boolean bindable(String name) {
        return BINDABLE.matcher(name).matches();
    }

    private FieldNode build(JsonNode raw, String path, String name, double presence, Set<String> refs) {
        JsonNode node = raw;
        boolean cut = false;
        String ref = node.path("$ref").asText(null);
        if (ref == null && node.has("anyOf")) {
            for (JsonNode alt : node.get("anyOf")) {
                if (!"null".equals(alt.path("type").asText())) {
                    node = alt;
                    ref = alt.path("$ref").asText(null);
                    break;
                }
            }
        }
        List<String> chain = new ArrayList<>(refs);
        if (ref != null) {
            if (!refs.add(ref)) {
                cut = true;
            }
            node = resolve(ref);
        }
        String type = typeOf(node);
        Map<String, FieldNode> props = new LinkedHashMap<>();
        boolean tree = false;
        JsonNode properties = node.path("properties");
        JsonNode items = node.path("items");
        if (!cut && "object".equals(type)) {
            fill(properties, path + ".", presence, refs, props);
        } else if ("array".equals(type) && items.isObject()) {
            if (items.has("$ref") && refs.contains(items.get("$ref").asText()) || "#".equals(items.path("$ref").asText())) {
                tree = true;
            } else {
                JsonNode item = items;
                String itemRef = item.path("$ref").asText(null);
                if (itemRef != null) {
                    refs.add(itemRef);
                    item = resolve(itemRef);
                }
                if ("object".equals(typeOf(item))) {
                    fill(item.path("properties"), path + "[].", presence, refs, props);
                }
                if (itemRef != null) {
                    refs.remove(itemRef);
                }
            }
        }
        if (ref != null && !chain.contains(ref)) {
            refs.remove(ref);
        }
        RoleInfo role = roles.get(path);
        if (role != null && "tree".equals(role.role())) {
            tree = true;
        }
        String format = node.path("format").asText(null);
        double min = num(node, "minimum");
        double max = num(node, "maximum");
        String seen = stats == null || !plainValues(node, type, format) ? null : stats.valueKind(path);
        boolean idLike = idNames != null && idNames.matcher(name).find();
        if ("number".equals(seen) && !idLike && !"number".equals(type)) {
            double[] r = stats.range(path);
            type = "number";
            min = r[0];
            max = r[1];
            role = role != null && !"plain".equals(role.role()) && !"dimension".equals(role.role()) ? role
                    : new RoleInfo("measure", "numbers (blank or written as text in some records)", null);
        } else if ("date".equals(seen)) {
            type = "string";
            format = "date";
            role = role != null && ("link".equals(role.role()) || "id".equals(role.role())) ? role
                    : new RoleInfo("date", "every non-blank value is an ISO date", null);
        } else if ("string".equals(seen) && "mixed".equals(type)) {
            type = "string";
        }
        FieldNode f = new FieldNode(path, name, type, role, presence, format, min, max,
                node.has("minItems") ? node.get("minItems").asInt() : -1, node.has("maxItems") ? node.get("maxItems").asInt() : -1, tree, props, node);
        byPath.put(path, f);
        return f;
    }

    /** A node the samples may type: mixed plain values (text, numbers, null), or text without a format. */
    private static boolean plainValues(JsonNode node, String type, String format) {
        if ("string".equals(type)) {
            return format == null;
        }
        if (!"mixed".equals(type) || !node.has("oneOf")) {
            return false;
        }
        for (JsonNode b : node.get("oneOf")) {
            if (!PLAIN.contains(b.path("type").asText(""))) {
                return false;
            }
        }
        return true;
    }

    private void fill(JsonNode properties, String prefix, double presence, Set<String> refs, Map<String, FieldNode> into) {
        properties.fieldNames().forEachRemaining(n -> {
            if (!bindable(n)) {
                return;
            }
            JsonNode child = properties.get(n);
            double own = child.path("x-drishti").has("presence") ? child.path("x-drishti").get("presence").asDouble() : 1.0;
            into.put(n, build(child, prefix + n, n, presence * own, refs));
        });
    }

    private JsonNode resolve(String ref) {
        if ("#".equals(ref)) {
            return root;
        }
        if (ref.startsWith("#/$defs/")) {
            JsonNode d = root.path("$defs").path(ref.substring("#/$defs/".length()));
            if (d.isObject()) {
                return d;
            }
        }
        return root;
    }

    private static double num(JsonNode n, String field) {
        return n.has(field) && n.get(field).isNumber() ? n.get(field).asDouble() : Double.NaN;
    }

    private static String typeOf(JsonNode n) {
        if (n.has("oneOf")) {
            return "mixed";
        }
        JsonNode t = n.get("type");
        if (t == null) {
            return n.has("properties") ? "object" : "mixed";
        }
        if (t.isArray()) {
            for (JsonNode e : t) {
                if (!"null".equals(e.asText())) {
                    return norm(e.asText());
                }
            }
            return "mixed";
        }
        return norm(t.asText());
    }

    private static String norm(String t) {
        return "integer".equals(t) ? "number" : t;
    }
}
