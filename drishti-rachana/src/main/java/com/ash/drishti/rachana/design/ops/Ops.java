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
package com.ash.drishti.rachana.design.ops;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The JSON form of operations: {@code {"op":"addPanel","kind":"table","at":{"area":"main","after":"legs"},"options":{...}}}
 * and so on, one object per operation. Reading is strict and says which operation (0-based) and which field is wrong.
 * Stateless and thread-safe.
 */
public final class Ops {

    /** A malformed list of operations; the message names the operation. */
    public static final class FormatException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;
        private final int op;

        FormatException(int op, String message) {
            super(op < 0 ? message : "operation " + op + ": " + message);
            this.op = op;
        }

        /** The operation's index, or -1 when the list itself is wrong. */
        public int op() {
            return op;
        }
    }

    private static final Map<String, Set<String>> FIELDS = Map.of(
            "addPanel", Set.of("op", "id", "kind", "at", "options"),
            "move", Set.of("op", "panel", "area", "before", "after", "span", "height"),
            "setOption", Set.of("op", "panel", "option", "value"),
            "bind", Set.of("op", "panel", "path", "role"),
            "remove", Set.of("op", "panel"),
            "setTitle", Set.of("op", "title"),
            "setStrip", Set.of("op", "items"),
            "setKeys", Set.of("op", "keys"),
            "setMatch", Set.of("op", "match"),
            "text", Set.of("op", "yaml"));
    private static final Set<String> AT = Set.of("area", "before", "after", "span", "height");
    private static final ObjectMapper JSON = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.USE_LONG_FOR_INTS, true);

    private Ops() {}

    /** Reads a JSON array of operations. */
    public static List<Op> parse(String json) {
        try {
            return parse(JSON.readTree(json));
        } catch (JsonProcessingException e) {
            throw new FormatException(-1, "the operations are not JSON: " + e.getOriginalMessage());
        }
    }

    public static List<Op> parse(JsonNode array) {
        if (array == null || !array.isArray()) {
            throw new FormatException(-1, "'ops' must be a list of operations such as {\"op\":\"remove\",\"panel\":\"legs\"}");
        }
        List<Op> out = new ArrayList<>();
        int i = 0;
        for (JsonNode n : array) {
            out.add(one(i++, n));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Op one(int i, JsonNode n) {
        if (!n.isObject() || !n.path("op").isTextual()) {
            throw new FormatException(i, "an operation is an object with an \"op\" such as " + String.join(", ", FIELDS.keySet().stream().sorted().toList()));
        }
        String op = n.get("op").asText();
        Set<String> known = FIELDS.get(op);
        if (known == null) {
            throw new FormatException(i, "unknown operation '" + op + "'; expected one of " + String.join(", ", FIELDS.keySet().stream().sorted().toList()));
        }
        for (Iterator<String> it = n.fieldNames(); it.hasNext();) {
            String f = it.next();
            if (!known.contains(f)) {
                throw new FormatException(i, "'" + op + "' has no field '" + f + "'; it takes " + String.join(", ", known.stream().filter(k -> !k.equals("op")).sorted().toList()));
            }
        }
        return switch (op) {
            case "addPanel" -> new AddPanel(text(i, n, "id", false), text(i, n, "kind", true), at(i, n.get("at")), (Map<String, Object>) map(i, n, "options", false));
            case "move" -> new Move(text(i, n, "panel", true), text(i, n, "area", false), text(i, n, "before", false), text(i, n, "after", false),
                    whole(i, n, "span"), whole(i, n, "height"));
            case "setOption" -> {
                if (!n.has("value")) {
                    throw new FormatException(i, "'setOption' needs a \"value\" (null removes the option)");
                }
                yield new SetOption(text(i, n, "panel", true), text(i, n, "option", true), java(n.get("value")));
            }
            case "bind" -> new Bind(text(i, n, "panel", true), text(i, n, "path", true), text(i, n, "role", false));
            case "remove" -> new Remove(text(i, n, "panel", true));
            case "setTitle" -> new SetTitle((Map<String, Object>) map(i, n, "title", true));
            case "setStrip" -> new SetStrip(items(i, n));
            case "setKeys" -> new SetKeys((Map<String, Object>) map(i, n, "keys", true));
            case "setMatch" -> new SetMatch((Map<String, Object>) map(i, n, "match", true));
            default -> new Text(text(i, n, "yaml", true));
        };
    }

    private static String text(int i, JsonNode n, String field, boolean required) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            if (required) {
                throw new FormatException(i, "'" + n.get("op").asText() + "' needs \"" + field + "\"");
            }
            return null;
        }
        if (!v.isTextual()) {
            throw new FormatException(i, "\"" + field + "\" must be text");
        }
        return v.asText();
    }

    private static Integer whole(int i, JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt()) {
            throw new FormatException(i, "\"" + field + "\" must be a whole number");
        }
        return v.asInt();
    }

    private static Map<String, Object> map(int i, JsonNode n, String field, boolean required) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            if (required) {
                throw new FormatException(i, "'" + n.get("op").asText() + "' needs \"" + field + "\" (an object)");
            }
            return null;
        }
        if (!v.isObject()) {
            throw new FormatException(i, "\"" + field + "\" must be an object");
        }
        Object o = java(v);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(int i, JsonNode n) {
        JsonNode v = n.get("items");
        if (v == null || !v.isArray()) {
            throw new FormatException(i, "'setStrip' needs \"items\" (a list of objects)");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode x : v) {
            if (!x.isObject()) {
                throw new FormatException(i, "each strip item must be an object such as {\"label\":\"PV\",\"bind\":\"$.pv\"}");
            }
            out.add((Map<String, Object>) java(x));
        }
        return out;
    }

    private static AddPanel.At at(int i, JsonNode a) {
        if (a == null || a.isNull()) {
            return null;
        }
        if (!a.isObject()) {
            throw new FormatException(i, "\"at\" must be an object: { area, before | after, span, height }");
        }
        for (Iterator<String> it = a.fieldNames(); it.hasNext();) {
            String f = it.next();
            if (!AT.contains(f)) {
                throw new FormatException(i, "\"at\" has no field '" + f + "'; it takes area, before, after, span, height");
            }
        }
        return new AddPanel.At(text(i, a, "area", false), text(i, a, "before", false), text(i, a, "after", false), whole(i, a, "span"), whole(i, a, "height"));
    }

    /** JSON as plain Java: Long, Double, Boolean, String, null, List, LinkedHashMap. */
    static Object java(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> m.put(e.getKey(), java(e.getValue())));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.forEach(x -> l.add(java(x)));
            return l;
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        return n.asText();
    }

    /** An operation as JSON, {@code "op"} first. */
    public static ObjectNode toJson(Op op) {
        ObjectNode out = JSON.createObjectNode();
        out.put("op", op.name());
        JSON.valueToTree(op).fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue()));
        if (op instanceof SetOption && !out.has("value")) {
            out.putNull("value");
        }
        return out;
    }

    public static ArrayNode toJson(List<? extends Op> ops) {
        ArrayNode out = JSON.createArrayNode();
        ops.forEach(o -> out.add(toJson(o)));
        return out;
    }
}
