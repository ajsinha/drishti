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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.server.bi.report.ReportParser.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The typed {@code format:} of a report visual (report schema v2, docs/architecture/BI_PARITY_PLAN.md R6): one JSON Schema per visual family, written in
 * YAML under {@code src/main/resources/bi/report/format/} ({@code common.yaml} holds the shared parts). {@link #check} validates a visual's format against
 * its family; {@link #toMap} serves the schemas, resolved and flattened per family, to the designer, whose Format pane is generated from them.
 *
 * <p>The validator knows the subset of JSON Schema 2020-12 the files use: {@code type, enum, const, minimum, maximum, maxLength, pattern, properties,
 * additionalProperties, items, minItems, maxItems, required, allOf, $ref} and {@code oneOf} picked by {@code x-discriminator}. The properties of an
 * {@code allOf} member count as the object's own, so {@code additionalProperties: false} beside {@code allOf: [common frame]} still admits the frame's keys.
 * A key a visual family has but a type does not use ({@code x-types}) is a warning, never an error.
 *
 * <p>Compatibility: a format key that a 2.3 report could already hold ({@link #LEGACY}) keeps working, so a bad value of one is a warning (the viewer ignores
 * it); a bad value of a key that is new in 2.4 is {@code DRS-9032}, an error. An unknown key is a warning ({@code DRS-9030}), as before.
 */
public final class FormatSchemas {

    /** The family files, in the order the designer lists them. */
    public static final List<String> FAMILIES = List.of("chart", "other-chart", "card", "table", "matrix", "slicer", "static", "code");

    /** Format keys a 2.3 report could hold: a bad value of one is a warning, never an error, so a stored report never stops opening. */
    static final Set<String> LEGACY = Set.of("labels", "stacked", "smooth", "axisTitles", "legend", "colour", "period", "numberFormat", "conditional", "bins", "mode",
            "level", "drill", "min", "max", "totals", "dataBars", "subtotals", "scope", "default", "options", "size", "python", "params", "run", "spec", "window", "plugin",
            "view", "aggregates");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FormatSchemas DEFAULT = load();

    private final Map<String, JsonNode> files;
    private final Map<String, String> familyOfType = new LinkedHashMap<>();

    private FormatSchemas(Map<String, JsonNode> files) {
        this.files = files;
        for (String f : FAMILIES) {
            files.get(f).path("x-types").forEach(t -> familyOfType.put(t.asText(), f));
        }
    }

    /** The schemas shipped with the server. */
    public static FormatSchemas get() {
        return DEFAULT;
    }

    private static FormatSchemas load() {
        Map<String, JsonNode> files = new LinkedHashMap<>();
        List<String> all = new ArrayList<>(FAMILIES);
        all.add(0, "common");
        for (String f : all) {
            try (InputStream in = FormatSchemas.class.getResourceAsStream("/bi/report/format/" + f + ".yaml")) {
                if (in == null) {
                    throw new IllegalStateException("the format schema bi/report/format/" + f + ".yaml is missing from the server");
                }
                files.put(f, YAML.readTree(in));
            } catch (IOException e) {
                throw new IllegalStateException("the format schema " + f + " does not parse: " + e.getMessage(), e);
            }
        }
        return new FormatSchemas(files);
    }

    /** The family of a visual type ({@code chart} for a column), or null for a type no family covers. */
    public String family(String type) {
        return familyOfType.get(type);
    }

    /** Every visual type a family covers. */
    public Map<String, String> families() {
        return java.util.Collections.unmodifiableMap(familyOfType);
    }

    // ---- checking ----------------------------------------------------------------------------------------------------

    /** The problems of one visual's {@code format} ({@code path} is the visual's path, {@code pages[0].visuals[2]}); none for a type without a family. */
    public List<Problem> check(String type, Map<String, Object> format, String path) {
        List<Problem> out = new ArrayList<>();
        String fam = family(type);
        if (fam == null || format == null || format.isEmpty()) {
            return out;
        }
        JsonNode value = JSON.valueToTree(format);
        new Walk(type, out).object(fam, files.get(fam), value, path + ".format", true);
        return out;
    }

    /** One run of the checker over a format: the visual's type, and where findings go. */
    private final class Walk {
        private final String type;
        private final List<Problem> out;
        private boolean legacy;

        Walk(String type, List<Problem> out) {
            this.type = type;
            this.out = out;
        }

        private void bad(String path, String message) {
            out.add(new Problem(legacy ? "warning" : "error", ReportParser.VISUAL, path, legacy ? message + " (ignored)" : message));
        }

        /** The object rules of {@code schema}, its {@code allOf} members' properties merged in. {@code top} marks the format itself. */
        void object(String file, JsonNode schema, JsonNode value, String path, boolean top) {
            if (!value.isObject()) {
                bad(path, "this is a mapping (key: value)");
                return;
            }
            Map<String, Ref> props = properties(file, schema);
            JsonNode extra = schema.path("additionalProperties");
            Ref extraRef = extra.isObject() ? new Ref(file, extra) : null;
            for (JsonNode req : schema.path("required")) {
                if (!value.has(req.asText())) {
                    bad(path, "needs `" + req.asText() + "`");
                }
            }
            value.fields().forEachRemaining(e -> {
                String k = e.getKey();
                String kp = path + "." + k;
                boolean was = legacy;
                if (top) {
                    legacy = LEGACY.contains(k);
                }
                Ref p = props.get(k);
                if (p != null) {
                    JsonNode types = p.node().path("x-types");
                    if (top && types.isArray() && types.size() > 0 && !contains(types, type)) {
                        out.add(new Problem("warning", ReportParser.INVALID, kp, "a " + type + " does not use `" + k + "` (it is for " + join(types) + "); it is ignored"));
                    } else {
                        value(p.file(), p.node(), e.getValue(), kp);
                    }
                } else if (extraRef != null) {
                    value(extraRef.file(), extraRef.node(), e.getValue(), kp);
                } else if (extra.isBoolean() && !extra.asBoolean()) {
                    out.add(new Problem("warning", ReportParser.INVALID, kp, "unknown " + (top ? "format " : "") + "key '" + k + "' is ignored" + (props.isEmpty() ? ""
                            : " (" + String.join(", ", props.keySet().stream().filter(x -> !top || visibleFor(props.get(x))).toList()) + ")")));
                }
                legacy = was;
            });
        }

        private boolean visibleFor(Ref p) {
            JsonNode types = p.node().path("x-types");
            return !types.isArray() || types.isEmpty() || contains(types, type);
        }

        void value(String file, JsonNode schema, JsonNode value, String path) {
            Ref r = deref(file, schema);
            JsonNode s = r.node();
            if (s != schema && hasConstraints(schema)) {
                value(file, constraintsOnly(schema), value, path);     // the keys beside a $ref (rare): checked too
            }
            if (s.has("oneOf")) {
                oneOf(r.file(), s, value, path);
                return;
            }
            if (s.has("const") && !s.get("const").equals(value)) {
                bad(path, "is " + s.get("const").asText());
                return;
            }
            String t = s.path("type").asText("");
            if (!t.isEmpty() && !typeOk(t, value)) {
                bad(path, "is " + article(t) + " " + t + (s.has("enum") ? ": " + join(s.get("enum")) : "") + ", not " + show(value));
                return;
            }
            if (s.has("enum") && !contains(s.get("enum"), value.asText()) || s.has("enum") && value.isContainerNode()) {
                bad(path, "is one of " + join(s.get("enum")) + ", not " + show(value));
                return;
            }
            if (value.isNumber()) {
                if (s.has("minimum") && value.asDouble() < s.get("minimum").asDouble() || s.has("maximum") && value.asDouble() > s.get("maximum").asDouble()) {
                    bad(path, "is " + (s.has("minimum") ? s.get("minimum").asText() : "any") + " to " + (s.has("maximum") ? s.get("maximum").asText() : "any") + ", not " + value.asText());
                }
            }
            if (value.isTextual()) {
                if (s.has("maxLength") && value.asText().length() > s.get("maxLength").asInt()) {
                    bad(path, "is at most " + s.get("maxLength").asInt() + " characters");
                }
                if (s.has("pattern") && !Pattern.compile(s.get("pattern").asText()).matcher(value.asText()).find()) {
                    bad(path, "'" + value.asText() + "' is not " + ("colour".equals(s.path("x-control").asText()) ? "a colour (#rrggbb)" : "in the right form (" + s.get("pattern").asText() + ")"));
                }
            }
            if (value.isArray()) {
                if (s.has("minItems") && value.size() < s.get("minItems").asInt() || s.has("maxItems") && value.size() > s.get("maxItems").asInt()) {
                    bad(path, "holds " + (s.has("minItems") ? s.get("minItems").asInt() : 0) + " to " + (s.has("maxItems") ? s.get("maxItems").asInt() : "any number of") + " items, not " + value.size());
                }
                if (s.has("items")) {
                    for (int i = 0; i < value.size(); i++) {
                        value(r.file(), s.get("items"), value.get(i), path + "[" + i + "]");
                    }
                }
            }
            if (value.isObject() && ("object".equals(t) || s.has("properties"))) {
                object(r.file(), s, value, path, false);
            }
        }

        private void oneOf(String file, JsonNode s, JsonNode value, String path) {
            String disc = s.path("x-discriminator").asText("");
            JsonNode branches = s.get("oneOf");
            if (!disc.isEmpty()) {
                List<String> kinds = new ArrayList<>();
                for (JsonNode b : branches) {
                    JsonNode bb = deref(file, b).node();
                    String k = bb.path("properties").path(disc).path("const").asText("");
                    kinds.add(k);
                    if (value.isObject() && k.equals(value.path(disc).asText(null))) {
                        value(file, b, value, path);
                        return;
                    }
                }
                bad(path + "." + disc, "`" + disc + "` is one of " + String.join(", ", kinds) + (value.path(disc).isMissingNode() ? "" : ", not " + show(value.path(disc))));
                return;
            }
            for (JsonNode b : branches) {
                List<Problem> trial = new ArrayList<>();
                Walk w = new Walk(type, trial);
                w.legacy = legacy;
                w.value(file, b, value, path);
                if (trial.stream().noneMatch(Problem::error)) {
                    out.addAll(trial);
                    return;
                }
            }
            bad(path, "is none of the forms this key takes");
        }
    }

    // ---- schema helpers ----------------------------------------------------------------------------------------------

    /** A schema node and the file its local {@code #/$defs} references resolve in. */
    private record Ref(String file, JsonNode node) {}

    /** Follows {@code $ref} ({@code #/$defs/x} in the same file, {@code common#/$defs/x} in another) until a node without one. */
    private Ref deref(String file, JsonNode schema) {
        String f = file;
        JsonNode n = schema;
        for (int guard = 0; n.has("$ref") && guard < 16; guard++) {
            String ref = n.get("$ref").asText();
            int hash = ref.indexOf('#');
            String target = hash <= 0 ? f : ref.substring(0, hash);
            JsonNode root = files.get(target);
            if (root == null) {
                throw new IllegalStateException("format schema " + f + ": no file '" + target + "' for $ref " + ref);
            }
            JsonNode at = root;
            for (String part : ref.substring(hash + 1).split("/")) {
                if (!part.isEmpty()) {
                    at = at.path(part);
                }
            }
            if (at.isMissingNode()) {
                throw new IllegalStateException("format schema " + f + ": $ref " + ref + " points at nothing");
            }
            f = target;
            n = at;
        }
        return new Ref(f, n);
    }

    /** The properties of an object schema with those of its {@code allOf} members, in the members' order first. */
    private Map<String, Ref> properties(String file, JsonNode schema) {
        Map<String, Ref> props = new LinkedHashMap<>();
        for (JsonNode member : schema.path("allOf")) {
            Ref r = deref(file, member);
            props.putAll(properties(r.file(), r.node()));
        }
        schema.path("properties").fields().forEachRemaining(e -> props.put(e.getKey(), new Ref(file, e.getValue())));
        return props;
    }

    private static boolean hasConstraints(JsonNode schema) {
        for (String k : List.of("enum", "minimum", "maximum", "maxLength", "pattern", "minItems", "maxItems")) {
            if (schema.has(k)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode constraintsOnly(JsonNode schema) {
        ObjectNode o = JSON.createObjectNode();
        schema.fields().forEachRemaining(e -> {
            if (!e.getKey().equals("$ref") && !e.getKey().startsWith("x-")) {
                o.set(e.getKey(), e.getValue());
            }
        });
        return o;
    }

    private static boolean typeOk(String t, JsonNode v) {
        return switch (t) {
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "string" -> v.isTextual();
            case "boolean" -> v.isBoolean();
            case "integer" -> v.isIntegralNumber() || v.isNumber() && v.asDouble() == Math.rint(v.asDouble());
            case "number" -> v.isNumber();
            default -> true;
        };
    }

    private static boolean contains(JsonNode list, String v) {
        for (JsonNode x : list) {
            if (x.asText().equals(v)) {
                return true;
            }
        }
        return false;
    }

    private static String join(JsonNode list) {
        List<String> s = new ArrayList<>();
        list.forEach(x -> s.add(x.asText()));
        return String.join(", ", s);
    }

    private static String show(JsonNode v) {
        return v.isTextual() ? "'" + v.asText() + "'" : v.isContainerNode() ? (v.isArray() ? "a list" : "a mapping") : v.asText();
    }

    private static String article(String t) {
        return "integer".equals(t) || "object".equals(t) || "array".equals(t) ? "an" : "a";
    }

    // ---- what the designer reads -------------------------------------------------------------------------------------

    /**
     * The schemas for the designer ({@code GET /api/v1/bi/reports/format-schemas}): {@code types} maps a visual type to its family; {@code families} gives
     * each family's {@code order} of sections and its {@code properties}, every {@code $ref} resolved and the {@code allOf} members' properties merged in,
     * so the pane is generated without a schema library. {@code schemas} are the files as written (JSON), for anyone who wants the source.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> fams = new LinkedHashMap<>();
        for (String f : FAMILIES) {
            JsonNode root = files.get(f);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("types", JSON.convertValue(root.path("x-types"), List.class));
            one.put("order", JSON.convertValue(root.path("x-order"), List.class));
            Map<String, Object> props = new LinkedHashMap<>();
            properties(f, root).forEach((k, r) -> props.put(k, JSON.convertValue(resolve(r.file(), r.node(), 0), Map.class)));
            one.put("properties", props);
            fams.put(f, one);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("types", new LinkedHashMap<>(familyOfType));
        m.put("families", fams);
        Map<String, Object> raw = new LinkedHashMap<>();
        files.forEach((k, v) -> raw.put(k, JSON.convertValue(v, Map.class)));
        m.put("schemas", raw);
        return m;
    }

    /** A copy of a schema node with every {@code $ref} replaced by its target (the sibling {@code x-} keys of the reference win). */
    private JsonNode resolve(String file, JsonNode node, int depth) {
        if (depth > 12 || !node.isContainerNode()) {
            return node;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode a = JSON.createArrayNode();
            node.forEach(x -> a.add(resolve(file, x, depth + 1)));
            return a;
        }
        ObjectNode o = JSON.createObjectNode();
        if (node.has("$ref")) {
            Ref r = deref(file, node);
            JsonNode target = resolve(r.file(), r.node(), depth + 1);
            target.fields().forEachRemaining(e -> o.set(e.getKey(), e.getValue()));
        }
        Set<String> skip = new LinkedHashSet<>(List.of("$ref"));
        node.fields().forEachRemaining(e -> {
            if (!skip.contains(e.getKey())) {
                o.set(e.getKey(), resolve(file, e.getValue(), depth + 1));
            }
        });
        return o;
    }
}
