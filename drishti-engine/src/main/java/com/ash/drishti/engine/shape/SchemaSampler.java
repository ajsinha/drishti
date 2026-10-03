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
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Synthetic sample documents from a plain JSON Schema (draft 2020-12 or 07, or a shape.json): the types, {@code enum},
 * {@code const}, {@code format}, {@code examples}, {@code default} and the numeric, length and item bounds it states.
 * Deterministic: document {@code i} of a schema is always the same. Used when a Design starts from a schema alone; the
 * documents are labelled synthetic wherever they are kept and never count as evidence that a screen works on real data.
 */
public final class SchemaSampler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final int MAX_DEPTH = 8;
    private final JsonNode root;

    public SchemaSampler(JsonNode schema) {
        this.root = schema;
    }

    /** {@code count} documents, each different where the schema allows. */
    public List<JsonNode> documents(int count) {
        List<JsonNode> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(value(root, new Random(31L * i + 7), i, 0, "$"));
        }
        return out;
    }

    private JsonNode value(JsonNode schema, Random rnd, int n, int depth, String path) {
        JsonNode s = resolve(schema);
        if (s.has("const")) {
            return s.get("const");
        }
        if (s.path("enum").isArray() && s.get("enum").size() > 0) {
            return s.get("enum").get(n % s.get("enum").size());
        }
        for (String key : new String[] {"examples", "x-examples"}) {
            if (s.path(key).isArray() && s.get(key).size() > 0) {
                return s.get(key).get(n % s.get(key).size());
            }
        }
        for (String key : new String[] {"oneOf", "anyOf"}) {
            if (s.path(key).isArray() && s.get(key).size() > 0) {
                return value(s.get(key).get(n % s.get(key).size()), rnd, n, depth + 1, path);
            }
        }
        if (s.path("allOf").isArray()) {
            ObjectNode merged = F.objectNode();
            for (JsonNode part : s.get("allOf")) {
                merge(merged, resolve(part));
            }
            s.fields().forEachRemaining(e -> {
                if (!"allOf".equals(e.getKey())) {
                    merged.set(e.getKey(), e.getValue());
                }
            });
            return value(merged, rnd, n, depth + 1, path);
        }
        if (s.has("default")) {
            return s.get("default");
        }
        return switch (type(s)) {
            case "object" -> object(s, rnd, n, depth, path);
            case "array" -> array(s, rnd, n, depth, path);
            case "integer" -> F.numberNode(Math.round(number(s, rnd, true)));
            case "number" -> F.numberNode(Math.round(number(s, rnd, false) * 100.0) / 100.0);
            case "boolean" -> F.booleanNode(n % 2 == 0);
            case "null" -> F.nullNode();
            default -> F.textNode(string(s, rnd, n, path));
        };
    }

    private static void merge(ObjectNode into, JsonNode part) {
        for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) part::fields) {
            if ("properties".equals(e.getKey()) && into.path("properties").isObject()) {
                ((ObjectNode) into.get("properties")).setAll((ObjectNode) e.getValue());
            } else if ("required".equals(e.getKey()) && into.path("required").isArray()) {
                ((ArrayNode) into.get("required")).addAll((ArrayNode) e.getValue());
            } else {
                into.set(e.getKey(), e.getValue().deepCopy());
            }
        }
    }

    private JsonNode object(JsonNode s, Random rnd, int n, int depth, String path) {
        ObjectNode o = F.objectNode();
        if (depth >= MAX_DEPTH) {
            return o;
        }
        for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) s.path("properties")::fields) {
            o.set(e.getKey(), value(e.getValue(), rnd, n, depth + 1, path + "." + e.getKey()));
        }
        return o;
    }

    private JsonNode array(JsonNode s, Random rnd, int n, int depth, String path) {
        ArrayNode a = F.arrayNode();
        if (depth >= MAX_DEPTH) {
            return a;
        }
        int min = s.path("minItems").asInt(1);
        int max = Math.max(min, s.path("maxItems").asInt(Math.max(min, 5)));
        int size = Math.min(max, Math.max(min, 3 + n % 3));
        for (int i = 0; i < size; i++) {
            a.add(value(s.path("items"), rnd, n + i, depth + 1, path + "[]"));
        }
        return a;
    }

    private static double number(JsonNode s, Random rnd, boolean integer) {
        double min = s.has("minimum") ? s.get("minimum").asDouble() : s.has("exclusiveMinimum") ? s.get("exclusiveMinimum").asDouble() + 1 : 0;
        double max = s.has("maximum") ? s.get("maximum").asDouble()
                : s.has("exclusiveMaximum") ? s.get("exclusiveMaximum").asDouble() - 1 : Math.max(min + 1000, 1000);
        if (max < min) {
            max = min;
        }
        double v = min + rnd.nextDouble() * (max - min);
        if (integer) {
            return Math.min(Math.max(Math.rint(v), Math.ceil(min)), Math.floor(max));
        }
        return Math.min(Math.max(v, min), max);
    }

    private static String string(JsonNode s, Random rnd, int n, String path) {
        String format = s.path("format").asText("");
        String field = path.substring(path.lastIndexOf('.') + 1).replace("[]", "");
        String out = switch (format) {
            case "date" -> java.time.LocalDate.of(2026, 1, 1).plusDays(n * 7L + rnd.nextInt(7)).toString();
            case "date-time" -> java.time.Instant.parse("2026-01-01T09:00:00Z").plusSeconds(n * 86_400L + rnd.nextInt(3600)).toString();
            case "time" -> String.format("%02d:%02d:00", 9 + rnd.nextInt(8), rnd.nextInt(60));
            case "email" -> "user" + (n + 1) + "@example.com";
            case "uuid" -> new java.util.UUID(rnd.nextLong(), rnd.nextLong()).toString();
            case "uri", "url" -> "https://example.com/" + field + "/" + (n + 1);
            case "ipv4" -> "10.0." + (n % 250) + "." + (1 + rnd.nextInt(250));
            default -> (field.isEmpty() || "$".equals(field) ? "text" : field) + "-" + (n + 1);
        };
        int max = s.path("maxLength").asInt(Integer.MAX_VALUE);
        if (out.length() > max) {
            out = out.substring(0, max);
        }
        StringBuilder b = new StringBuilder(out);
        while (b.length() < s.path("minLength").asInt(0)) {
            b.append('x');
        }
        return b.toString();
    }

    private static String type(JsonNode s) {
        JsonNode t = s.get("type");
        if (t != null && t.isArray()) {
            for (JsonNode x : t) {
                if (!"null".equals(x.asText())) {
                    return x.asText();
                }
            }
            return "null";
        }
        if (t != null) {
            return t.asText();
        }
        return s.has("properties") ? "object" : s.has("items") ? "array" : "string";
    }

    private JsonNode resolve(JsonNode schema) {
        JsonNode s = schema;
        for (int i = 0; i < MAX_DEPTH && s.has("$ref") && s.get("$ref").asText().startsWith("#"); i++) {
            String ref = s.get("$ref").asText();
            JsonNode target = "#".equals(ref) ? root : root.at(ref.substring(1));
            if (target.isMissingNode()) {
                break;
            }
            s = target;
        }
        return s;
    }
}
