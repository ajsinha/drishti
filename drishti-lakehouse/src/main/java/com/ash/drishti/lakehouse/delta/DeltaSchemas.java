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
package com.ash.drishti.lakehouse.delta;

import com.ash.drishti.iceberg.meta.SchemaJson;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Delta table's schema (the {@code schemaString} of its {@code metaData} action, the same JSON in a Delta log and in a Delta Sharing
 * answer) as the Iceberg schema the external-table reader works with: each column gets a field id (its column-mapping id when the table
 * has one, else a new one), its Iceberg type, and a name-mapping entry naming its <em>physical</em> column in the Parquet files (its
 * column-mapping physical name, else its name), so files written by Spark, Databricks or delta-rs without field ids are read by name.
 * Partition columns become identity partition fields: their values come from the file's partition values, not from the file.
 *
 * <p>Types: {@code string}, {@code long}, {@code integer}/{@code short}/{@code byte} (int), {@code float}, {@code double},
 * {@code boolean}, {@code binary}, {@code date}, {@code timestamp} (timestamptz), {@code timestamp_ntz} (timestamp),
 * {@code decimal(p,s)}, structs, arrays and maps. {@code variant} and anything else is refused by name.
 */
public final class DeltaSchemas {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Pattern DECIMAL = Pattern.compile("decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final String MAPPING_ID = "delta.columnMapping.id";
    private static final String PHYSICAL = "delta.columnMapping.physicalName";

    /**
     * The converted schema.
     *
     * @param schema the Iceberg schema (id 0)
     * @param nameMapping the {@code schema.name-mapping.default} JSON (physical column names to field ids)
     * @param spec the partition spec (identity on each partition column; unpartitioned when there are none)
     * @param physical a top-level column's physical name (in the files, the partition values and the statistics) by field id
     */
    public record Converted(Schema schema, String nameMapping, ObjectNode spec, Map<Integer, String> physical) {

        /** The top-level column whose physical name is {@code name}, or null. */
        public NestedField byPhysical(String name) {
            for (Map.Entry<Integer, String> e : physical.entrySet()) {
                if (e.getValue().equals(name)) {
                    return schema.field(e.getKey());
                }
            }
            return null;
        }
    }

    private DeltaSchemas() {}

    /** Ids handed out while converting: column-mapping ids are kept, the rest numbered above the highest of them. */
    private static final class Ids {
        private int next;
        private final Set<Integer> used = new HashSet<>();

        int take(JsonNode metadata) {
            if (metadata != null && metadata.hasNonNull(MAPPING_ID)) {
                int id = metadata.get(MAPPING_ID).asInt();
                used.add(id);
                return id;
            }
            do {
                next++;
            } while (used.contains(next));
            used.add(next);
            return next;
        }
    }

    /**
     * Converts {@code schemaString}.
     *
     * @param partitionColumns the table's partition columns (logical names, as {@code partitionColumns} lists them)
     * @throws IllegalArgumentException naming the column and its type when a type is not read
     */
    public static Converted convert(String schemaString, List<String> partitionColumns) {
        JsonNode root;
        try {
            root = JSON.readTree(schemaString);
        } catch (IOException e) {
            throw new UncheckedIOException("the Delta schema is not JSON", e);
        }
        Ids ids = new Ids();
        ids.next = maxMappingId(root.path("fields"));
        List<NestedField> columns = new ArrayList<>();
        ArrayNode mapping = F.arrayNode();
        Map<Integer, String> physical = new LinkedHashMap<>();
        for (JsonNode f : root.path("fields")) {
            NestedField c = field(f, ids, mapping, f.path("name").asText());
            columns.add(c);
            physical.put(c.id(), physicalName(f));
        }
        Schema schema = new Schema(0, columns, List.of());
        ObjectNode spec = F.objectNode();
        spec.put("spec-id", 0);
        ArrayNode pf = spec.putArray("fields");
        int partitionId = 1000;
        for (String p : partitionColumns) {
            NestedField c = columns.stream().filter(x -> x.name().equals(p)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("the partition column " + p + " is not in the table's schema"));
            if (!(c.type() instanceof Type.Primitive)) {
                throw new IllegalArgumentException("the partition column " + p + " is not of a simple type");
            }
            pf.addObject().put("name", p).put("transform", "identity").put("source-id", c.id()).put("field-id", partitionId++);
        }
        return new Converted(schema, mapping.toString(), spec, Map.copyOf(physical));
    }

    private static int maxMappingId(JsonNode fields) {
        int max = 0;
        for (JsonNode f : fields) {
            if (f.path("metadata").hasNonNull(MAPPING_ID)) {
                max = Math.max(max, f.path("metadata").get(MAPPING_ID).asInt());
            }
            max = Math.max(max, maxMappingId(nestedFields(f.path("type"))));
        }
        return max;
    }

    private static JsonNode nestedFields(JsonNode type) {
        if (!type.isObject()) {
            return F.arrayNode();
        }
        return switch (type.path("type").asText()) {
            case "struct" -> type.path("fields");
            case "array" -> nestedFields(type.path("elementType"));
            case "map" -> {
                ArrayNode both = F.arrayNode();
                nestedFields(type.path("keyType")).forEach(both::add);
                nestedFields(type.path("valueType")).forEach(both::add);
                yield both;
            }
            default -> F.arrayNode();
        };
    }

    private static String physicalName(JsonNode f) {
        JsonNode m = f.path("metadata");
        return m.hasNonNull(PHYSICAL) ? m.get(PHYSICAL).asText() : f.path("name").asText();
    }

    private static NestedField field(JsonNode f, Ids ids, ArrayNode mapping, String where) {
        int id = ids.take(f.path("metadata"));
        ObjectNode m = mapping.addObject();
        m.put("field-id", id);
        m.putArray("names").add(physicalName(f));
        Type t = type(f.path("type"), ids, m, where);
        return new NestedField(id, f.path("name").asText(), !f.path("nullable").asBoolean(true), t, null);
    }

    private static Type type(JsonNode t, Ids ids, ObjectNode mapping, String where) {
        if (t.isTextual()) {
            return primitive(t.asText(), where);
        }
        switch (t.path("type").asText()) {
            case "struct" -> {
                ArrayNode nested = mapping.putArray("fields");
                List<NestedField> fs = new ArrayList<>();
                for (JsonNode f : t.path("fields")) {
                    fs.add(field(f, ids, nested, where + "." + f.path("name").asText()));
                }
                return new Type.Struct(fs);
            }
            case "array" -> {
                int element = ids.take(null);
                ObjectNode em = mapping.putArray("fields").addObject();
                em.put("field-id", element);
                em.putArray("names").add("element");
                return new Type.ListType(element, !t.path("containsNull").asBoolean(true), type(t.path("elementType"), ids, em, where + "[]"));
            }
            case "map" -> {
                ArrayNode nested = mapping.putArray("fields");
                int key = ids.take(null);
                ObjectNode km = nested.addObject();
                km.put("field-id", key);
                km.putArray("names").add("key");
                int value = ids.take(null);
                ObjectNode vm = nested.addObject();
                vm.put("field-id", value);
                vm.putArray("names").add("value");
                return new Type.MapType(key, type(t.path("keyType"), ids, km, where + ".key"), value, !t.path("valueContainsNull").asBoolean(true),
                        type(t.path("valueType"), ids, vm, where + ".value"));
            }
            default -> throw new IllegalArgumentException("the column " + where + " has the Delta type " + t + ", which is not read");
        }
    }

    private static Type.Primitive primitive(String name, String where) {
        Matcher d = DECIMAL.matcher(name);
        if (d.matches()) {
            return Type.Primitive.decimal(Integer.parseInt(d.group(1)), Integer.parseInt(d.group(2)));
        }
        return switch (name) {
            case "string" -> Type.Primitive.STRING;
            case "long" -> Type.Primitive.LONG;
            case "integer", "short", "byte" -> Type.Primitive.INT;
            case "float" -> Type.Primitive.FLOAT;
            case "double" -> Type.Primitive.DOUBLE;
            case "boolean" -> Type.Primitive.BOOLEAN;
            case "binary" -> Type.Primitive.BINARY;
            case "date" -> Type.Primitive.DATE;
            case "timestamp" -> Type.Primitive.TIMESTAMPTZ;
            case "timestamp_ntz" -> Type.Primitive.TIMESTAMP;
            default -> throw new IllegalArgumentException("the column " + where + " has the Delta type " + name
                    + ", which is not read (leave it out of the share or the table, or cast it to a string)");
        };
    }

    /** The schema as Iceberg metadata JSON writes it. */
    public static ObjectNode schemaJson(Converted c) {
        return SchemaJson.write(c.schema());
    }
}
