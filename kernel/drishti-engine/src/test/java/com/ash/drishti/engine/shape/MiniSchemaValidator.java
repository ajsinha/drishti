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
import java.util.ArrayList;
import java.util.List;

/**
 * A validator for the subset of JSON Schema 2020-12 the shape extractor emits (type, enum, minimum, maximum,
 * properties, required, additionalProperties, items, minItems, maxItems, oneOf, anyOf, local $ref). Test scope only.
 */
final class MiniSchemaValidator {

    private final JsonNode root;

    MiniSchemaValidator(JsonNode root) {
        this.root = root;
    }

    /** Every violation of {@code value} against the schema, each with the path where it was found. */
    List<String> validate(JsonNode value) {
        List<String> errors = new ArrayList<>();
        check(root, value, "$", errors);
        return errors;
    }

    private boolean valid(JsonNode schema, JsonNode value) {
        List<String> e = new ArrayList<>();
        check(schema, value, "$", e);
        return e.isEmpty();
    }

    private void check(JsonNode schema, JsonNode v, String path, List<String> errors) {
        if (schema.has("$ref")) {
            check(resolve(schema.get("$ref").asText()), v, path, errors);
        }
        if (schema.has("oneOf")) {
            long hits = 0;
            List<String> why = new ArrayList<>();
            for (JsonNode b : schema.get("oneOf")) {
                List<String> e = new ArrayList<>();
                check(b, v, path, e);
                hits += e.isEmpty() ? 1 : 0;
                why.addAll(e);
            }
            if (hits != 1) {
                errors.add(path + ": matches " + hits + " of oneOf branches " + why);
            }
        }
        if (schema.has("anyOf")) {
            boolean any = false;
            for (JsonNode b : schema.get("anyOf")) {
                any |= valid(b, v);
            }
            if (!any) {
                errors.add(path + ": matches no anyOf branch");
            }
        }
        if (schema.has("type") && !typeOk(schema.get("type"), v)) {
            errors.add(path + ": type " + v.getNodeType() + " is not " + schema.get("type"));
            return;
        }
        if (schema.has("enum")) {
            boolean in = false;
            for (JsonNode e : schema.get("enum")) {
                in |= e.equals(v);
            }
            if (!in) {
                errors.add(path + ": " + v + " not in enum " + schema.get("enum"));
            }
        }
        if (v.isNumber()) {
            if (schema.has("minimum") && compare(v, schema.get("minimum")) < 0) {
                errors.add(path + ": below minimum");
            }
            if (schema.has("maximum") && compare(v, schema.get("maximum")) > 0) {
                errors.add(path + ": above maximum");
            }
        }
        if (v.isObject()) {
            object(schema, v, path, errors);
        }
        if (v.isArray()) {
            if (schema.has("minItems") && v.size() < schema.get("minItems").asInt()) {
                errors.add(path + ": fewer than minItems");
            }
            if (schema.has("maxItems") && v.size() > schema.get("maxItems").asInt()) {
                errors.add(path + ": more than maxItems");
            }
            if (schema.has("items")) {
                for (int i = 0; i < v.size(); i++) {
                    check(schema.get("items"), v.get(i), path + "[" + i + "]", errors);
                }
            }
        }
    }

    private void object(JsonNode schema, JsonNode v, String path, List<String> errors) {
        JsonNode props = schema.path("properties");
        if (schema.has("required")) {
            for (JsonNode r : schema.get("required")) {
                if (!v.has(r.asText())) {
                    errors.add(path + ": missing required " + r.asText());
                }
            }
        }
        v.fields().forEachRemaining(e -> {
            if (props.has(e.getKey())) {
                check(props.get(e.getKey()), e.getValue(), path + "." + e.getKey(), errors);
            } else if (schema.has("additionalProperties")) {
                check(schema.get("additionalProperties"), e.getValue(), path + "." + e.getKey(), errors);
            }
        });
    }

    private JsonNode resolve(String ref) {
        if ("#".equals(ref)) {
            return root;
        }
        JsonNode n = root.at(ref.substring(1));
        if (n.isMissingNode()) {
            throw new IllegalStateException("unresolved $ref " + ref);
        }
        return n;
    }

    private static boolean typeOk(JsonNode type, JsonNode v) {
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (typeOk(t, v)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asText()) {
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "string" -> v.isTextual();
            case "boolean" -> v.isBoolean();
            case "null" -> v.isNull();
            case "number" -> v.isNumber();
            case "integer" -> v.isNumber() && (v.isIntegralNumber() || v.asDouble() == Math.rint(v.asDouble()));
            default -> false;
        };
    }

    /** Exact comparison (integers beyond 2^53); a non-finite instance compares as equal (no bound applies). */
    private static int compare(JsonNode v, JsonNode bound) {
        if (!bound.isNumber()) {
            return 1;
        }
        if (Double.isNaN(v.asDouble()) || Double.isInfinite(v.asDouble())) {
            return 0;
        }
        return v.decimalValue().compareTo(bound.decimalValue());
    }
}
