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
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;

/**
 * An Iceberg schema as the Parquet schema of a data file, every field carrying its Iceberg field id (the spec's
 * "Parquet" appendix): {@code date} an INT32 DATE, {@code time} INT64 TIME(MICROS), timestamps INT64
 * TIMESTAMP(MICROS) adjusted to UTC or not, {@code uuid} a 16-byte FIXED UUID, decimals INT32, INT64 or FIXED by
 * precision, lists in the three-level {@code list}/{@code element} layout, maps as {@code key_value} groups.
 */
public final class ParquetSchemas {

    private ParquetSchemas() {}

    public static MessageType of(Schema schema, String name) {
        Types.MessageTypeBuilder b = Types.buildMessage();
        for (NestedField f : schema.columns()) {
            b.addField(field(f));
        }
        return b.named(name);
    }

    static org.apache.parquet.schema.Type field(NestedField f) {
        org.apache.parquet.schema.Type.Repetition rep = f.required() ? org.apache.parquet.schema.Type.Repetition.REQUIRED
                : org.apache.parquet.schema.Type.Repetition.OPTIONAL;
        return type(f.type(), rep, f.name(), f.id());
    }

    private static org.apache.parquet.schema.Type type(Type t, org.apache.parquet.schema.Type.Repetition rep, String name, int id) {
        return switch (t) {
            case Type.Primitive p -> primitive(p, rep).id(id).named(name);
            case Type.Struct s -> {
                Types.GroupBuilder<org.apache.parquet.schema.GroupType> g = Types.buildGroup(rep);
                s.fields().forEach(c -> g.addField(field(c)));
                yield g.id(id).named(name);
            }
            case Type.ListType l -> Types.buildGroup(rep).as(LogicalTypeAnnotation.listType())
                    .addField(Types.repeatedGroup().addField(type(l.element(), l.elementRequired() ? org.apache.parquet.schema.Type.Repetition.REQUIRED
                            : org.apache.parquet.schema.Type.Repetition.OPTIONAL, "element", l.elementId())).named("list"))
                    .id(id).named(name);
            case Type.MapType m -> Types.buildGroup(rep).as(LogicalTypeAnnotation.mapType())
                    .addField(Types.repeatedGroup()
                            .addField(type(m.key(), org.apache.parquet.schema.Type.Repetition.REQUIRED, "key", m.keyId()))
                            .addField(type(m.value(), m.valueRequired() ? org.apache.parquet.schema.Type.Repetition.REQUIRED
                                    : org.apache.parquet.schema.Type.Repetition.OPTIONAL, "value", m.valueId()))
                            .named("key_value"))
                    .id(id).named(name);
        };
    }

    private static Types.PrimitiveBuilder<org.apache.parquet.schema.PrimitiveType> primitive(Type.Primitive p,
            org.apache.parquet.schema.Type.Repetition rep) {
        return switch (p.id()) {
            case BOOLEAN -> Types.primitive(PrimitiveTypeName.BOOLEAN, rep);
            case INT -> Types.primitive(PrimitiveTypeName.INT32, rep);
            case LONG -> Types.primitive(PrimitiveTypeName.INT64, rep);
            case FLOAT -> Types.primitive(PrimitiveTypeName.FLOAT, rep);
            case DOUBLE -> Types.primitive(PrimitiveTypeName.DOUBLE, rep);
            case DATE -> Types.primitive(PrimitiveTypeName.INT32, rep).as(LogicalTypeAnnotation.dateType());
            case TIME -> Types.primitive(PrimitiveTypeName.INT64, rep).as(LogicalTypeAnnotation.timeType(false, LogicalTypeAnnotation.TimeUnit.MICROS));
            case TIMESTAMP -> Types.primitive(PrimitiveTypeName.INT64, rep)
                    .as(LogicalTypeAnnotation.timestampType(false, LogicalTypeAnnotation.TimeUnit.MICROS));
            case TIMESTAMPTZ -> Types.primitive(PrimitiveTypeName.INT64, rep)
                    .as(LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS));
            case STRING -> Types.primitive(PrimitiveTypeName.BINARY, rep).as(LogicalTypeAnnotation.stringType());
            case UUID -> Types.primitive(PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, rep).length(16).as(LogicalTypeAnnotation.uuidType());
            case FIXED -> Types.primitive(PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, rep).length(p.length());
            case BINARY -> Types.primitive(PrimitiveTypeName.BINARY, rep);
            case DECIMAL -> {
                LogicalTypeAnnotation d = LogicalTypeAnnotation.decimalType(p.scale(), p.precision());
                if (p.precision() <= 9) {
                    yield Types.primitive(PrimitiveTypeName.INT32, rep).as(d);
                }
                if (p.precision() <= 18) {
                    yield Types.primitive(PrimitiveTypeName.INT64, rep).as(d);
                }
                yield Types.primitive(PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, rep).length(decimalBytes(p.precision())).as(d);
            }
            default -> throw new IllegalArgumentException("not a primitive: " + p);
        };
    }

    /** The fewest bytes that hold every unscaled value of this precision (two's complement). */
    static int decimalBytes(int precision) {
        int bytes = 1;
        while (Math.pow(2, 8.0 * bytes - 1) < Math.pow(10, precision)) {
            bytes++;
        }
        return bytes;
    }
}
