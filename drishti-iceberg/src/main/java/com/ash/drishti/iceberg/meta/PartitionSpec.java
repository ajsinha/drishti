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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * A partition spec: how rows are split into partitions, a list of fields each a transform of a source column. A
 * table keeps every spec it ever had (partition evolution); each data file names the spec it was written with.
 */
public record PartitionSpec(int specId, List<Field> fields) {

    /** One partition field: a transform of the source column {@code sourceId}, with its own id and name. */
    public record Field(int sourceId, int fieldId, String name, Transform transform) {}

    public PartitionSpec {
        fields = List.copyOf(fields);
    }

    /** The spec of an unpartitioned table. */
    public static PartitionSpec unpartitioned(int specId) {
        return new PartitionSpec(specId, List.of());
    }

    public boolean isUnpartitioned() {
        return fields.stream().allMatch(f -> f.transform() instanceof Transform.Void);
    }

    /** The type of each partition field's value, for the schema its source columns are in. */
    public Type.Struct partitionType(Schema schema) {
        List<NestedField> out = new ArrayList<>();
        for (Field f : fields) {
            NestedField src = schema.field(f.sourceId());
            // a field of an old spec whose source column was since dropped: its values are read as the manifest's Avro types say
            Type.Primitive t = src != null && src.type() instanceof Type.Primitive p ? f.transform().resultType(p) : null;
            out.add(NestedField.optional(f.fieldId(), f.name(), t));
        }
        return new Type.Struct(out);
    }

    /** The source column's type of field {@code i}, or null when the column is gone (a void field of a dropped column). */
    public Type.Primitive sourceType(Schema schema, int i) {
        NestedField src = schema.field(fields.get(i).sourceId());
        return src != null && src.type() instanceof Type.Primitive p ? p : null;
    }

    public static PartitionSpec read(JsonNode n) {
        List<Field> fields = new ArrayList<>();
        JsonNode arr = n.isArray() ? n : n.path("fields");
        int next = 1000;
        for (JsonNode f : arr) {
            int id = f.has("field-id") ? f.get("field-id").asInt() : next;   // v1 specs may lack field ids: 1000, 1001, …
            next = id + 1;
            if (f.has("source-ids") && f.get("source-ids").size() > 1) {
                throw new com.ash.drishti.iceberg.types.UnsupportedFeature("a partition field over several columns (format version 3)");
            }
            int source = f.has("source-id") ? f.get("source-id").asInt() : f.path("source-ids").path(0).asInt();
            fields.add(new Field(source, id, f.path("name").asText(), Transform.parse(f.path("transform").asText())));
        }
        return new PartitionSpec(n.isArray() ? 0 : n.path("spec-id").asInt(0), fields);
    }

    public ObjectNode write() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("spec-id", specId);
        var arr = o.putArray("fields");
        for (Field f : fields) {
            arr.addObject().put("name", f.name()).put("transform", f.transform().toString()).put("source-id", f.sourceId())
                    .put("field-id", f.fieldId());
        }
        return o;
    }

    /** Whether two specs partition the same way (the same fields, whatever their spec ids). */
    public boolean sameFields(PartitionSpec other) {
        if (fields.size() != other.fields.size()) {
            return false;
        }
        for (int i = 0; i < fields.size(); i++) {
            Field a = fields.get(i);
            Field b = other.fields.get(i);
            if (a.sourceId() != b.sourceId() || !a.name().equals(b.name()) || !a.transform().toString().equals(b.transform().toString())) {
                return false;
            }
        }
        return true;
    }

    /** The highest partition field id, or 999 when there are none. */
    public int lastFieldId() {
        return fields.stream().mapToInt(Field::fieldId).max().orElse(999);
    }
}
