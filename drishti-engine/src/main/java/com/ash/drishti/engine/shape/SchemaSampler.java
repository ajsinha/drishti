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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Synthetic sample documents from a plain JSON Schema (draft 2020-12 or 07, or a shape.json): the types, {@code enum},
 * {@code const}, {@code format}, {@code examples}, {@code default} and the numeric, length and item bounds it states.
 * Deterministic: document {@code i} of a schema is always the same. Used when a Design starts from a schema alone; the
 * documents are labelled synthetic wherever they are kept and never count as evidence that a screen works on real data.
 * Bounded: array lengths, string lengths, nesting and the size of one document come from {@link Limits}
 * ({@code drishti.builder.sample-max-*}); a schema that asks for more is clamped and {@link #problems()} says so, nothing
 * beyond the limits is ever allocated. One instance is used by one thread.
 */
public final class SchemaSampler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final int MAX_DEPTH = 8;
    private static final int NODE_COST = 16;

    /** The bounds of one synthetic document. */
    public record Limits(int maxItems, int maxString, int maxDepth, long maxBytes) {
        public static Limits of(BuilderProperties p) {
            return new Limits(p.sampleMaxItems(), p.sampleMaxString(), p.sampleMaxDepth(), p.sampleMaxKb() * 1024L);
        }

        public static Limits defaults() {
            return of(BuilderProperties.defaults());
        }
    }
    /** Past MAX_DEPTH only required members are written; this many levels further, nothing is. */
    private static final int HARD_CAP = 6;
    private final JsonNode root;
    private final Limits limits;
    private final java.util.Set<String> problems = new java.util.LinkedHashSet<>();
    private long used;

    public SchemaSampler(JsonNode schema) {
        this(schema, Limits.defaults());
    }

    public SchemaSampler(JsonNode schema, Limits limits) {
        this.root = schema;
        this.limits = limits;
    }

    private boolean cutAt(int depth) {
        return depth >= Math.min(MAX_DEPTH, limits.maxDepth());
    }

    private boolean hardAt(int depth) {
        return depth >= Math.min(MAX_DEPTH, limits.maxDepth()) + HARD_CAP;
    }

    /** What was clamped, in words (each once), after {@link #documents}. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    private boolean spend(long bytes, String path) {
        used += bytes + NODE_COST;
        if (used > limits.maxBytes()) {
            problems.add("a generated document would be over " + limits.maxBytes() / 1024 + " KB (drishti.builder.sample-max-kb): the rest of it at " + path + " is left out");
            return false;
        }
        return true;
    }

    /** {@code count} documents, each different where the schema allows. */
    public List<JsonNode> documents(int count) {
        List<JsonNode> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            used = 0;
            out.add(value(root, new Random(31L * i + 7), i, 0, "$"));
        }
        return out;
    }

    private JsonNode value(JsonNode schema, Random rnd, int n, int depth, String path) {
        JsonNode s = resolve(schema);
        if (depth > Math.min(limits.maxDepth(), MAX_DEPTH) + HARD_CAP || !spend(0, path)) {
            if (depth > limits.maxDepth() + HARD_CAP) {
                problems.add("the schema nests deeper than " + limits.maxDepth() + " levels (drishti.builder.sample-max-depth): cut at " + path);
            }
            return F.nullNode();
        }
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
        Set<String> required = new HashSet<>();
        s.path("required").forEach(r -> required.add(r.asText()));
        boolean cut = cutAt(depth);
        if (hardAt(depth)) {
            return o;
        }
        for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) s.path("properties")::fields) {
            if (e.getValue().isBoolean() && !e.getValue().asBoolean()) {
                continue;
            }
            if (cut && !required.contains(e.getKey())) {
                continue;
            }
            if (!spend(e.getKey().length(), path)) {
                break;
            }
            o.set(e.getKey(), value(e.getValue(), rnd, n, depth + 1, path + "." + e.getKey()));
        }
        if (cut) {
            return o;
        }
        int min = s.path("minProperties").asInt(0);
        JsonNode patterns = s.path("patternProperties");
        if (patterns.isObject() && patterns.size() > 0) {
            int target = Math.max(min, 1);
            for (int round = 0; round < 40 && o.size() < target; round++) {
                for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) patterns::fields) {
                    String key = RegexSampler.sample(e.getKey(), rnd);
                    if (key != null && !o.has(key) && o.size() < target) {
                        o.set(key, value(e.getValue(), rnd, n, depth + 1, path + "." + key));
                    }
                }
            }
        }
        JsonNode extra = s.path("additionalProperties");
        for (int i = 1; o.size() < min && !(extra.isBoolean() && !extra.asBoolean()); i++) {
            o.set("extra-" + i, value(extra.isObject() ? extra : F.objectNode(), rnd, n, depth + 1, path + ".extra"));
        }
        return o;
    }

    private JsonNode array(JsonNode s, Random rnd, int n, int depth, String path) {
        ArrayNode a = F.arrayNode();
        boolean cut = cutAt(depth);
        int min = s.path("minItems").asInt(cut ? 0 : 1);
        if (hardAt(depth)) {
            return a;
        }
        JsonNode prefix = s.path("prefixItems");
        JsonNode items = s.path("items");
        if (!prefix.isArray() && items.isArray()) {
            prefix = items;
            items = s.path("additionalItems");
        }
        boolean noMore = items.isBoolean() && !items.asBoolean();
        int fixed = prefix.isArray() ? prefix.size() : 0;
        int max = Math.max(min, s.path("maxItems").asInt(Math.max(min, 5)));
        int size = cut ? min : Math.min(max, Math.max(min, 3 + n % 3));
        if (fixed > 0) {
            size = noMore ? Math.min(fixed, max) : Math.max(size, Math.min(fixed, max));
        }
        if (size > limits.maxItems()) {
            problems.add("an array at " + path + " asks for " + size + " items; " + limits.maxItems() + " are generated (drishti.builder.sample-max-items)");
            size = limits.maxItems();
        }
        for (int i = 0; i < size; i++) {
            if (!spend(0, path)) {
                break;
            }
            if (i < fixed) {
                a.add(value(prefix.get(i), rnd, n + i, depth + 1, path + "[]"));
            } else if (!noMore) {
                a.add(value(items, rnd, n + i, depth + 1, path + "[]"));
            }
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

    private String string(JsonNode s, Random rnd, int n, String path) {
        String format = s.path("format").asText("");
        if (s.path("pattern").isTextual() && format.isEmpty()) {
            String fromPattern = fromPattern(s, rnd);
            if (fromPattern != null) {
                return fromPattern;
            }
        }
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
        int cap = (int) Math.max(0, Math.min(limits.maxString(), limits.maxBytes() - used));
        int want = s.path("minLength").asInt(0);
        if (want > cap) {
            problems.add("a string at " + path + " asks for " + want + " characters; " + cap + " are generated (drishti.builder.sample-max-string)");
            want = cap;
        }
        if (out.length() > cap) {
            out = out.substring(0, cap);
        }
        StringBuilder b = new StringBuilder(out);
        while (b.length() < want) {
            b.append('x');
        }
        spend(b.length(), path);
        return b.toString();
    }

    /** A string matching the schema's {@code pattern} (a simple subset) within its length bounds, or null. */
    private static String fromPattern(JsonNode s, Random rnd) {
        int min = s.path("minLength").asInt(0);
        int max = s.path("maxLength").asInt(Integer.MAX_VALUE);
        for (int attempt = 0; attempt < 25; attempt++) {
            String out = RegexSampler.sample(s.get("pattern").asText(), rnd);
            if (out == null) {
                return null;
            }
            if (out.length() >= min && out.length() <= max) {
                return out;
            }
        }
        return null;
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
        if (s.has("$ref") && !s.get("$ref").asText().startsWith("#")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST,
                    "the schema's $ref " + s.get("$ref").asText() + " is not inside the schema; only local references (#/...) are followed");
        }
        for (int i = 0; i < MAX_DEPTH && s.has("$ref") && s.get("$ref").asText().startsWith("#"); i++) {
            String ref = s.get("$ref").asText();
            JsonNode target = "#".equals(ref) ? root : root.at(ref.substring(1));
            if (target.isMissingNode()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the schema's $ref " + ref + " points at nothing in the schema");
            }
            s = target;
        }
        return s;
    }
}
