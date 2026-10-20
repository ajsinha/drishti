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
package com.ash.drishti.iceberg.meta;

import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.UnsupportedFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/** Schemas to and from their JSON form in table metadata (the spec's "Schemas" appendix). */
public final class SchemaJson {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private SchemaJson() {}

    public static Schema read(JsonNode n) {
        if (!"struct".equals(n.path("type").asText("struct"))) {
            throw new IllegalArgumentException("a schema is a struct, not " + n.path("type"));
        }
        List<Integer> identifiers = new ArrayList<>();
        n.path("identifier-field-ids").forEach(i -> identifiers.add(i.asInt()));
        return new Schema(n.path("schema-id").asInt(0), fields(n.path("fields")), identifiers);
    }

    private static List<NestedField> fields(JsonNode arr) {
        List<NestedField> out = new ArrayList<>();
        for (JsonNode f : arr) {
            if (f.has("initial-default") || f.has("write-default")) {
                throw new UnsupportedFeature("column defaults (format version 3) on " + f.path("name").asText());
            }
            out.add(new NestedField(f.path("id").asInt(), f.path("name").asText(), f.path("required").asBoolean(), type(f.path("type")),
                    f.hasNonNull("doc") ? f.get("doc").asText() : null));
        }
        return out;
    }

    public static Type type(JsonNode t) {
        if (t.isTextual()) {
            return Type.Primitive.parse(t.asText());
        }
        String kind = t.path("type").asText();
        return switch (kind) {
            case "struct" -> new Type.Struct(fields(t.path("fields")));
            case "list" -> new Type.ListType(t.path("element-id").asInt(), t.path("element-required").asBoolean(), type(t.path("element")));
            case "map" -> new Type.MapType(t.path("key-id").asInt(), type(t.path("key")), t.path("value-id").asInt(),
                    t.path("value-required").asBoolean(), type(t.path("value")));
            default -> throw new UnsupportedFeature("the Iceberg type '" + t + "'");
        };
    }

    public static ObjectNode write(Schema s) {
        ObjectNode n = F.objectNode();
        n.put("type", "struct");
        n.put("schema-id", s.schemaId());
        if (!s.identifierFieldIds().isEmpty()) {
            ArrayNode ids = n.putArray("identifier-field-ids");
            s.identifierFieldIds().forEach(ids::add);
        }
        n.set("fields", fieldsJson(s.columns()));
        return n;
    }

    private static ArrayNode fieldsJson(List<NestedField> fields) {
        ArrayNode arr = F.arrayNode();
        for (NestedField f : fields) {
            ObjectNode o = arr.addObject();
            o.put("id", f.id());
            o.put("name", f.name());
            o.put("required", f.required());
            o.set("type", typeJson(f.type()));
            if (f.doc() != null) {
                o.put("doc", f.doc());
            }
        }
        return arr;
    }

    public static JsonNode typeJson(Type t) {
        return switch (t) {
            case Type.Primitive p -> F.textNode(p.toString());
            case Type.Struct s -> {
                ObjectNode o = F.objectNode();
                o.put("type", "struct");
                o.set("fields", fieldsJson(s.fields()));
                yield o;
            }
            case Type.ListType l -> {
                ObjectNode o = F.objectNode();
                o.put("type", "list");
                o.put("element-id", l.elementId());
                o.set("element", typeJson(l.element()));
                o.put("element-required", l.elementRequired());
                yield o;
            }
            case Type.MapType m -> {
                ObjectNode o = F.objectNode();
                o.put("type", "map");
                o.put("key-id", m.keyId());
                o.set("key", typeJson(m.key()));
                o.put("value-id", m.valueId());
                o.set("value", typeJson(m.value()));
                o.put("value-required", m.valueRequired());
                yield o;
            }
        };
    }
}
