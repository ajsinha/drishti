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
package com.ash.drishti.iceberg.write;

import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Type;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;

/**
 * The Avro schemas of manifests and manifest lists, versions 1 and 2, field for field as the spec's "Manifests" section
 * gives them (names, field ids, optional fields as unions with null, Iceberg maps of int keys as arrays of
 * {@code k<id>_v<id>} records), so any reader (Java, Python, Rust, Spark, Trino, Snowflake) reads what is written.
 */
final class AvroSchemas {

    private AvroSchemas() {}

    static final Schema NULL = Schema.create(Schema.Type.NULL);

    static Schema optional(Schema s) {
        return Schema.createUnion(NULL, s);
    }

    static Field field(String name, Schema s, int id, boolean optional) {
        Field f = new Field(name, optional ? optional(s) : s, null, optional ? Field.NULL_DEFAULT_VALUE : null);
        f.addProp("field-id", id);
        return f;
    }

    static Schema prim(Schema.Type t) {
        return Schema.create(t);
    }

    /** An Iceberg map with int keys: an array of key/value records with the map logical type. */
    static Schema intMap(int keyId, int valueId, Schema value) {
        Schema kv = Schema.createRecord("k" + keyId + "_v" + valueId, null, null, false,
                List.of(field("key", prim(Schema.Type.INT), keyId, false), field("value", value, valueId, false)));
        Schema arr = Schema.createArray(kv);
        arr.addProp("logicalType", "map");
        return arr;
    }

    static Schema list(Schema element, int elementId) {
        Schema arr = Schema.createArray(element);
        arr.addProp("element-id", elementId);
        return arr;
    }

    /** Avro names: letters, digits and _ (Iceberg's sanitising: other characters as _x plus their hex code). */
    static String name(String n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean ok = c == '_' || Character.isLetter(c) && c < 128 || Character.isDigit(c) && c < 128 && i > 0;
            if (ok) {
                b.append(c);
            } else if (i == 0 && Character.isDigit(c)) {
                b.append('_').append(c);
            } else {
                b.append("_x").append(Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT));
            }
        }
        return b.toString();
    }

    /** The Avro type of a partition value of this Iceberg type. */
    static Schema partitionValue(Type.Primitive t, int id) {
        return switch (t.id()) {
            case BOOLEAN -> prim(Schema.Type.BOOLEAN);
            case INT -> prim(Schema.Type.INT);
            case LONG -> prim(Schema.Type.LONG);
            case FLOAT -> prim(Schema.Type.FLOAT);
            case DOUBLE -> prim(Schema.Type.DOUBLE);
            case DATE -> LogicalTypes.date().addToSchema(prim(Schema.Type.INT));
            case TIME -> LogicalTypes.timeMicros().addToSchema(prim(Schema.Type.LONG));
            case TIMESTAMP, TIMESTAMPTZ -> {
                Schema s = LogicalTypes.timestampMicros().addToSchema(prim(Schema.Type.LONG));
                s.addProp("adjust-to-utc", t.id() == Type.Id.TIMESTAMPTZ);
                yield s;
            }
            case STRING -> prim(Schema.Type.STRING);
            case UUID -> LogicalTypes.uuid().addToSchema(Schema.createFixed("uuid_" + id, null, null, 16));
            case FIXED -> Schema.createFixed("fixed_" + id, null, null, t.length());
            case BINARY -> prim(Schema.Type.BYTES);
            case DECIMAL -> LogicalTypes.decimal(t.precision(), t.scale())
                    .addToSchema(Schema.createFixed("decimal_" + id, null, null, ParquetSchemas.decimalBytes(t.precision())));
            default -> throw new IllegalArgumentException("no partition value of " + t);
        };
    }

    /** The {@code partition} struct of a spec ({@code r102}), one optional field per partition field. */
    static Schema partition(Type.Struct partitionType) {
        List<Field> fields = new ArrayList<>();
        for (NestedField f : partitionType.fields()) {
            if (!(f.type() instanceof Type.Primitive p)) {
                throw new IllegalStateException("partition field " + f.name() + " has no source column any more: its manifest cannot be rewritten");
            }
            fields.add(field(name(f.name()), partitionValue(p, f.id()), f.id(), true));
        }
        return Schema.createRecord("r102", null, null, false, fields);
    }

    static Schema dataFile(Type.Struct partitionType, int formatVersion) {
        List<Field> f = new ArrayList<>();
        if (formatVersion >= 2) {
            f.add(field("content", prim(Schema.Type.INT), 134, false));
        }
        f.add(field("file_path", prim(Schema.Type.STRING), 100, false));
        f.add(field("file_format", prim(Schema.Type.STRING), 101, false));
        f.add(field("partition", partition(partitionType), 102, false));
        f.add(field("record_count", prim(Schema.Type.LONG), 103, false));
        f.add(field("file_size_in_bytes", prim(Schema.Type.LONG), 104, false));
        if (formatVersion == 1) {
            f.add(field("block_size_in_bytes", prim(Schema.Type.LONG), 105, false));
        }
        f.add(field("column_sizes", intMap(117, 118, prim(Schema.Type.LONG)), 108, true));
        f.add(field("value_counts", intMap(119, 120, prim(Schema.Type.LONG)), 109, true));
        f.add(field("null_value_counts", intMap(121, 122, prim(Schema.Type.LONG)), 110, true));
        f.add(field("nan_value_counts", intMap(138, 139, prim(Schema.Type.LONG)), 137, true));
        f.add(field("lower_bounds", intMap(126, 127, prim(Schema.Type.BYTES)), 125, true));
        f.add(field("upper_bounds", intMap(129, 130, prim(Schema.Type.BYTES)), 128, true));
        f.add(field("key_metadata", prim(Schema.Type.BYTES), 131, true));
        f.add(field("split_offsets", list(prim(Schema.Type.LONG), 133), 132, true));
        if (formatVersion >= 2) {
            f.add(field("equality_ids", list(prim(Schema.Type.INT), 136), 135, true));
        }
        f.add(field("sort_order_id", prim(Schema.Type.INT), 140, true));
        return Schema.createRecord("r2", null, null, false, f);
    }

    static Schema manifestEntry(Type.Struct partitionType, int formatVersion) {
        List<Field> f = new ArrayList<>();
        f.add(field("status", prim(Schema.Type.INT), 0, false));
        f.add(field("snapshot_id", prim(Schema.Type.LONG), 1, formatVersion >= 2));
        if (formatVersion >= 2) {
            f.add(field("sequence_number", prim(Schema.Type.LONG), 3, true));
            f.add(field("file_sequence_number", prim(Schema.Type.LONG), 4, true));
        }
        f.add(field("data_file", dataFile(partitionType, formatVersion), 2, false));
        return Schema.createRecord("manifest_entry", null, null, false, f);
    }

    static Schema fieldSummary() {
        return Schema.createRecord("r508", null, null, false, List.of(field("contains_null", prim(Schema.Type.BOOLEAN), 509, false),
                field("contains_nan", prim(Schema.Type.BOOLEAN), 518, true), field("lower_bound", prim(Schema.Type.BYTES), 510, true),
                field("upper_bound", prim(Schema.Type.BYTES), 511, true)));
    }

    static Schema manifestFile(int formatVersion) {
        List<Field> f = new ArrayList<>();
        boolean v2 = formatVersion >= 2;
        f.add(field("manifest_path", prim(Schema.Type.STRING), 500, false));
        f.add(field("manifest_length", prim(Schema.Type.LONG), 501, false));
        f.add(field("partition_spec_id", prim(Schema.Type.INT), 502, false));
        if (v2) {
            f.add(field("content", prim(Schema.Type.INT), 517, false));
            f.add(field("sequence_number", prim(Schema.Type.LONG), 515, false));
            f.add(field("min_sequence_number", prim(Schema.Type.LONG), 516, false));
        }
        f.add(field("added_snapshot_id", prim(Schema.Type.LONG), 503, !v2));
        f.add(field(v2 ? "added_files_count" : "added_data_files_count", prim(Schema.Type.INT), 504, !v2));
        f.add(field(v2 ? "existing_files_count" : "existing_data_files_count", prim(Schema.Type.INT), 505, !v2));
        f.add(field(v2 ? "deleted_files_count" : "deleted_data_files_count", prim(Schema.Type.INT), 506, !v2));
        f.add(field("added_rows_count", prim(Schema.Type.LONG), 512, !v2));
        f.add(field("existing_rows_count", prim(Schema.Type.LONG), 513, !v2));
        f.add(field("deleted_rows_count", prim(Schema.Type.LONG), 514, !v2));
        f.add(field("partitions", list(fieldSummary(), 508), 507, true));
        f.add(field("key_metadata", prim(Schema.Type.BYTES), 519, true));
        return Schema.createRecord("manifest_file", null, null, false, f);
    }
}
