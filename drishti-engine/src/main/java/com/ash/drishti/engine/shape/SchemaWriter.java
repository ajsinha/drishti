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
package com.ash.drishti.engine.shape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns merged {@link Facts} into a JSON Schema (draft 2020-12) with {@code x-drishti} annotations, collecting the
 * per-path rows of the report and the roles on the way. One instance per request; not shared.
 */
final class SchemaWriter {

    static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$-]*");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final BuilderProperties props;
    private final RoleDetector roles;
    private final List<JsonNode> documents;
    private final int total;
    private final ObjectNode defs = F.objectNode();
    final List<PathRow> rows = new ArrayList<>();
    final List<Conflict> conflicts = new ArrayList<>();
    final Map<String, RoleInfo> roleByPath = new LinkedHashMap<>();

    SchemaWriter(BuilderProperties props, RoleDetector roles, List<JsonNode> documents) {
        this.props = props;
        this.roles = roles;
        this.documents = documents;
        this.total = documents.size();
    }

    ObjectNode write(Facts root) {
        ObjectNode schema = F.objectNode();
        schema.put("$schema", DRAFT);
        ObjectNode body = node(root, "$", 1.0, true, "#");
        schema.setAll(body);
        ObjectNode x = (ObjectNode) schema.get("x-drishti");
        if (x == null) {
            x = schema.putObject("x-drishti");
        }
        x.put("samples", total);
        if (!defs.isEmpty()) {
            schema.set("$defs", defs);
        }
        return schema;
    }

    // -------------------------------------------------------------------------------------------------------- node

    /** The schema of {@code f}; {@code selfRef} is how a tree inside it refers back to the record that holds it. */
    private ObjectNode node(Facts f, String path, double presence, boolean required, String selfRef) {
        List<String> types = f.types();
        int slot = rows.size();
        rows.add(null);
        RoleInfo role = roles.detect(f, path, documents).orElse(null);
        if (role != null) {
            roleByPath.put(path, role);
        }
        ObjectNode out = F.objectNode();
        List<String> nonNull = types.stream().filter(t -> !"null".equals(t)).toList();
        boolean nullable = types.contains("null");
        if (f.isMap()) {
            if (nullable) {
                out.putArray("type").add("object").add("null");
            } else {
                out.put("type", "object");
            }
            out.set("additionalProperties", node(f.mapValue, path + "{}", 1.0, true, selfRef));
        } else if (nonNull.isEmpty()) {
            out.put("type", "null");
        } else if (nonNull.size() == 1) {
            branch(nonNull.get(0), f, path, selfRef, out);
            if (nullable) {
                nullable(out);
            }
        } else {
            ArrayNode one = out.putArray("oneOf");
            for (String t : nonNull) {
                ObjectNode b = F.objectNode();
                branch(t, f, path, selfRef, b);
                one.add(b);
            }
            if (nullable) {
                one.add(F.objectNode().put("type", "null"));
            }
            conflicts.add(new Conflict(path, nonNull));
        }
        ObjectNode x = F.objectNode();
        if (role != null) {
            x.put("role", role.role());
            x.put("reason", role.reason());
            if (role.kind() != null) {
                x.put("kind", role.kind());
            }
        }
        if (!required) {
            x.put("presence", round(presence));
        }
        if (nonNull.size() > 1) {
            ArrayNode c = x.putArray("conflict");
            nonNull.forEach(c::add);
        }
        if (f.isMap()) {
            x.put("map", true);
            x.put("keys", f.mapKeys);
        }
        if (f.masked > 0) {
            x.put("masked", true);
        }
        if (!x.isEmpty()) {
            out.set("x-drishti", x);
        }
        double share = f.docShare(total);
        rowFill(slot, path, f, types, role, presence, share, nonNull.size() > 1);
        return out;
    }

    private void rowFill(int slot, String path, Facts f, List<String> types, RoleInfo role, double presence, double docShare, boolean conflict) {
        String type = f.isMap() ? "map" : String.join("|", types);
        List<String> ex = f.examples.stream().limit(props.examples()).toList();
        rows.set(slot, new PathRow(path, type, role == null ? null : role.role(), role == null ? null : role.reason(), round(presence),
                ex, docShare < 1.0 ? List.copyOf(f.files) : List.of(), conflict, f.masked > 0));
    }

    private static void nullable(ObjectNode out) {
        if (out.has("$ref")) {
            ObjectNode ref = out.deepCopy();
            out.removeAll();
            out.putArray("anyOf").add(ref).add(F.objectNode().put("type", "null"));
            return;
        }
        JsonNode t = out.get("type");
        if (t != null && t.isTextual()) {
            ArrayNode a = F.arrayNode().add(t.asText()).add("null");
            out.set("type", a);
            JsonNode en = out.get("enum");
            if (en instanceof ArrayNode e) {
                e.addNull();
            }
        }
    }

    private void branch(String type, Facts f, String path, String selfRef, ObjectNode out) {
        switch (type) {
            case "object" -> object(f, path, selfRef, out);
            case "array" -> array(f, path, selfRef, out);
            case "string" -> string(f, out);
            case "integer", "number" -> {
                out.put("type", type);
                if (f.minExact != null) {
                    number(out, "minimum", f.minExact, "integer".equals(type));
                    number(out, "maximum", f.maxExact, "integer".equals(type));
                }
                if (f.nonFinite) {
                    out.put("$comment", "a sample held NaN or an infinite number: no bound is written for it");
                }
            }
            default -> out.put("type", type);
        }
    }

    private static void number(ObjectNode out, String key, java.math.BigDecimal v, boolean integral) {
        if (isWhole(v, integral)) {
            out.put(key, v.toBigIntegerExact());
        } else {
            out.put(key, v);
        }
    }

    private static boolean isWhole(java.math.BigDecimal v, boolean integral) {
        return integral && (v.scale() <= 0 || v.stripTrailingZeros().scale() <= 0);
    }

    private void string(Facts f, ObjectNode out) {
        out.put("type", "string");
        int plain = f.plainStrings();
        if (plain == 0) {
            return;
        }
        String format = f.dates == plain ? "date" : f.datetimes == plain ? "date-time" : f.uuids == plain ? "uuid"
                : f.emails == plain ? "email" : f.currencies == plain ? "currency" : null;
        if (format != null) {
            out.put("format", format);
        }
        boolean formatted = f.dates + f.datetimes + f.uuids + f.emails > 0;
        if (f.masked == 0 && !formatted && !f.overflow && f.repeats && f.strings >= props.enumMinSeen()
                && f.distinct.size() <= props.enumMaxDistinct()) {
            ArrayNode en = out.putArray("enum");
            f.distinct.keySet().forEach(en::add);
        }
    }

    private void array(Facts f, String path, String selfRef, ObjectNode out) {
        out.put("type", "array");
        if (f.tree) {
            ObjectNode ref = F.objectNode().put("$ref", selfRef);
            out.set("items", f.treeNulls == 0 ? ref : F.objectNode().set("anyOf", F.arrayNode().add(ref).add(F.objectNode().put("type", "null"))));
        } else if (f.items != null) {
            out.set("items", node(f.items, path + "[]", 1.0, true, selfRef));
        }
        if (f.arrays > 0) {
            out.put("minItems", f.tree || f.minLen == Integer.MAX_VALUE ? 0 : f.minLen);
            out.put("maxItems", f.maxLen);
        }
    }

    private void object(Facts f, String path, String selfRef, ObjectNode out) {
        out.put("type", "object");
        boolean record = f.props.values().stream().anyMatch(p -> p.tree);
        String ref = selfRef;
        String def = null;
        if (record) {
            def = "$".equals(path) ? null : defName(path);
            ref = def == null ? "#" : "#/$defs/" + def;
            if (def != null) {
                defs.putNull(def);
            }
        }
        ObjectNode properties = out.putObject("properties");
        ArrayNode required = F.arrayNode();
        for (Facts p : f.props.values()) {
            boolean req = p.occ == f.objects;
            double presence = f.objects == 0 ? 1.0 : p.occ / (double) f.objects;
            properties.set(p.name, node(p, path + "." + segment(p.name), presence, req, ref));
            if (req) {
                required.add(p.name);
            }
        }
        if (!required.isEmpty()) {
            out.set("required", required);
        }
        if (def != null) {
            defs.set(def, out.deepCopy());
            out.removeAll();
            out.put("$ref", ref);
        }
    }

    private String defName(String path) {
        String base = path.replaceAll("\\[\\]|\\{\\}", "").replaceAll("^\\$\\.?", "");
        base = base.isEmpty() ? "record" : base.replaceAll("[^A-Za-z0-9_]+", "_");
        String name = base + "_item";
        int n = 1;
        while (defs.has(name) && n < 1000) {
            name = base + "_item" + (++n);
        }
        return name;
    }

    private static String segment(String name) {
        return PLAIN_NAME.matcher(name).matches() ? name : "['" + name.replace("'", "\\'") + "']";
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
