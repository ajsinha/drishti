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

import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.UnsupportedFeature;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.NullOrder;
import org.apache.iceberg.SortDirection;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.ByteBuffers;

/** Between Apache Iceberg's objects and the plain values Drishti's Parquet reader and writer work in. Stateless. */
public final class Bridge {

    private Bridge() {}

    public static Type type(org.apache.iceberg.types.Type t) {
        return switch (t.typeId()) {
            case BOOLEAN -> Type.Primitive.BOOLEAN;
            case INTEGER -> Type.Primitive.INT;
            case LONG -> Type.Primitive.LONG;
            case FLOAT -> Type.Primitive.FLOAT;
            case DOUBLE -> Type.Primitive.DOUBLE;
            case DATE -> Type.Primitive.DATE;
            case TIME -> Type.Primitive.TIME;
            case TIMESTAMP -> ((Types.TimestampType) t).shouldAdjustToUTC() ? Type.Primitive.TIMESTAMPTZ : Type.Primitive.TIMESTAMP;
            case STRING -> Type.Primitive.STRING;
            case UUID -> Type.Primitive.UUID;
            case BINARY -> Type.Primitive.BINARY;
            case FIXED -> Type.Primitive.fixed(((Types.FixedType) t).length());
            case DECIMAL -> Type.Primitive.decimal(((Types.DecimalType) t).precision(), ((Types.DecimalType) t).scale());
            case STRUCT -> new Type.Struct(t.asStructType().fields().stream().map(Bridge::field).toList());
            case LIST -> {
                Types.NestedField e = t.asListType().fields().get(0);
                yield new Type.ListType(e.fieldId(), e.isRequired(), type(e.type()));
            }
            case MAP -> {
                Types.NestedField k = t.asMapType().fields().get(0);
                Types.NestedField v = t.asMapType().fields().get(1);
                yield new Type.MapType(k.fieldId(), type(k.type()), v.fieldId(), v.isRequired(), type(v.type()));
            }
            default -> throw new UnsupportedFeature("a column of type " + t + " (format version 3)");
        };
    }

    private static NestedField field(Types.NestedField f) {
        return new NestedField(f.fieldId(), f.name(), f.isRequired(), type(f.type()), f.doc());
    }

    public static Schema schema(org.apache.iceberg.Schema s) {
        return new Schema(s.schemaId(), s.columns().stream().map(Bridge::field).toList(), List.copyOf(s.identifierFieldIds()));
    }

    public static org.apache.iceberg.types.Type iceberg(Type t) {
        return switch (t) {
            case Type.Primitive p -> switch (p.id()) {
                case BOOLEAN -> Types.BooleanType.get();
                case INT -> Types.IntegerType.get();
                case LONG -> Types.LongType.get();
                case FLOAT -> Types.FloatType.get();
                case DOUBLE -> Types.DoubleType.get();
                case DATE -> Types.DateType.get();
                case TIME -> Types.TimeType.get();
                case TIMESTAMP -> Types.TimestampType.withoutZone();
                case TIMESTAMPTZ -> Types.TimestampType.withZone();
                case STRING -> Types.StringType.get();
                case UUID -> Types.UUIDType.get();
                case BINARY -> Types.BinaryType.get();
                case FIXED -> Types.FixedType.ofLength(p.length());
                case DECIMAL -> Types.DecimalType.of(p.precision(), p.scale());
                default -> throw new IllegalArgumentException("not a primitive type: " + p);
            };
            case Type.Struct s -> Types.StructType.of(s.fields().stream().map(Bridge::icebergField).toList());
            case Type.ListType l -> l.elementRequired() ? Types.ListType.ofRequired(l.elementId(), iceberg(l.element()))
                    : Types.ListType.ofOptional(l.elementId(), iceberg(l.element()));
            case Type.MapType m -> m.valueRequired() ? Types.MapType.ofRequired(m.keyId(), m.valueId(), iceberg(m.key()), iceberg(m.value()))
                    : Types.MapType.ofOptional(m.keyId(), m.valueId(), iceberg(m.key()), iceberg(m.value()));
        };
    }

    private static Types.NestedField icebergField(NestedField f) {
        return Types.NestedField.of(f.id(), !f.required(), f.name(), iceberg(f.type()), f.doc());
    }

    public static org.apache.iceberg.Schema iceberg(Schema s) {
        return new org.apache.iceberg.Schema(s.schemaId(), s.columns().stream().map(Bridge::icebergField).toList(),
                new java.util.HashSet<>(s.identifierFieldIds()));
    }

    public static PartitionSpec spec(org.apache.iceberg.PartitionSpec s) {
        List<PartitionSpec.Field> out = new ArrayList<>();
        s.fields().forEach(f -> out.add(new PartitionSpec.Field(f.sourceId(), f.fieldId(), f.name(), Transform.parse(f.transform().toString()))));
        return new PartitionSpec(s.specId(), out);
    }

    /** The spec over {@code schema}, by the names of the source columns. */
    public static org.apache.iceberg.PartitionSpec iceberg(PartitionSpec s, Schema source, org.apache.iceberg.Schema schema) {
        org.apache.iceberg.PartitionSpec.Builder b = org.apache.iceberg.PartitionSpec.builderFor(schema);
        for (PartitionSpec.Field f : s.fields()) {
            String col = source.nameOf(f.sourceId());
            String t = f.transform().toString();
            if (t.equals("identity")) {
                b.identity(col, f.name());
            } else if (t.equals("year")) {
                b.year(col, f.name());
            } else if (t.equals("month")) {
                b.month(col, f.name());
            } else if (t.equals("day")) {
                b.day(col, f.name());
            } else if (t.equals("hour")) {
                b.hour(col, f.name());
            } else if (t.startsWith("bucket[")) {
                b.bucket(col, Integer.parseInt(t.substring(7, t.length() - 1)), f.name());
            } else if (t.startsWith("truncate[")) {
                b.truncate(col, Integer.parseInt(t.substring(9, t.length() - 1)), f.name());
            } else if (t.equals("void")) {
                b.alwaysNull(col, f.name());
            } else {
                throw new UnsupportedFeature("the partition transform " + t);
            }
        }
        return b.build();
    }

    public static SortOrder order(org.apache.iceberg.SortOrder o) {
        List<SortOrder.Field> out = new ArrayList<>();
        o.fields().forEach(f -> out.add(new SortOrder.Field(f.sourceId(), Transform.parse(f.transform().toString()),
                f.direction() == SortDirection.ASC, f.nullOrder() == NullOrder.NULLS_FIRST)));
        return new SortOrder(o.orderId(), out);
    }

    public static org.apache.iceberg.SortOrder iceberg(SortOrder o, Schema source, org.apache.iceberg.Schema schema) {
        if (o.fields().isEmpty()) {
            return org.apache.iceberg.SortOrder.unsorted();
        }
        org.apache.iceberg.SortOrder.Builder b = org.apache.iceberg.SortOrder.builderFor(schema);
        for (SortOrder.Field f : o.fields()) {
            if (!(f.transform() instanceof Transform.Identity)) {
                throw new UnsupportedFeature("a sort order on the transform " + f.transform());
            }
            String col = source.nameOf(f.sourceId());
            NullOrder nulls = f.nullsFirst() ? NullOrder.NULLS_FIRST : NullOrder.NULLS_LAST;
            if (f.ascending()) {
                b.asc(col, nulls);
            } else {
                b.desc(col, nulls);
            }
        }
        return b.build();
    }

    public static Snapshot snapshot(org.apache.iceberg.Snapshot s) {
        Map<String, String> summary = s.summary() == null ? Map.of() : s.summary();
        return new Snapshot(s.snapshotId(), s.parentId(), s.sequenceNumber(), s.timestampMillis(), s.manifestListLocation(), List.of(),
                s.operation() == null ? "" : s.operation(), summary, s.schemaId());
    }

    /** A data or delete file as Drishti's value; its partition values in Iceberg's internal form. */
    public static DataFile file(ContentFile<?> f, org.apache.iceberg.PartitionSpec spec) {
        Object[] partition = new Object[spec.fields().size()];
        Class<?>[] classes = spec.javaClasses();
        for (int i = 0; i < partition.length; i++) {
            Object v = f.partition().get(i, classes[i]);
            partition[i] = v instanceof ByteBuffer b ? ByteBuffers.toByteArray(b) : v instanceof CharSequence c ? c.toString() : v;
        }
        return new DataFile(f.content().id(), f.location(), f.format().name(), f.specId(), partition, f.recordCount(), f.fileSizeInBytes(),
                orEmpty(f.columnSizes()), orEmpty(f.valueCounts()), orEmpty(f.nullValueCounts()), orEmpty(f.nanValueCounts()), bytes(f.lowerBounds()),
                bytes(f.upperBounds()), f.keyMetadata() == null ? null : ByteBuffers.toByteArray(f.keyMetadata()),
                f.splitOffsets() == null ? List.of() : List.copyOf(f.splitOffsets()), f.equalityFieldIds() == null ? List.of() : List.copyOf(f.equalityFieldIds()),
                f.sortOrderId(), f instanceof DeleteFile d ? d.referencedDataFile() : null);
    }

    private static <V> Map<Integer, V> orEmpty(Map<Integer, V> m) {
        return m == null ? Map.of() : m;
    }

    private static Map<Integer, byte[]> bytes(Map<Integer, ByteBuffer> m) {
        Map<Integer, byte[]> out = new HashMap<>();
        if (m != null) {
            m.forEach((k, v) -> out.put(k, ByteBuffers.toByteArray(v)));
        }
        return out;
    }

    private static Map<Integer, ByteBuffer> buffers(Map<Integer, byte[]> m) {
        Map<Integer, ByteBuffer> out = new HashMap<>();
        if (m != null) {
            m.forEach((k, v) -> out.put(k, ByteBuffer.wrap(v)));
        }
        return out;
    }

    /** A data file the writer produced, for a commit. */
    public static org.apache.iceberg.DataFile iceberg(DataFile f, org.apache.iceberg.Table t) {
        org.apache.iceberg.PartitionSpec spec = t.specs().get(f.specId());
        DataFiles.Builder b = DataFiles.builder(spec).withPath(f.path()).withFormat(FileFormat.fromString(f.format())).withFileSizeInBytes(f.fileSizeInBytes())
                .withMetrics(new Metrics(f.recordCount(), f.columnSizes(), f.valueCounts(), f.nullValueCounts(), f.nanValueCounts(),
                        buffers(f.lowerBounds()), buffers(f.upperBounds())));
        if (spec.isPartitioned()) {
            b.withPartition(new Row(f.partition()));
        }
        if (f.splitOffsets() != null && !f.splitOffsets().isEmpty()) {
            b.withSplitOffsets(f.splitOffsets());
        }
        if (f.sortOrderId() != null && t.sortOrders().containsKey(f.sortOrderId())) {
            b.withSortOrder(t.sortOrders().get(f.sortOrderId()));
        }
        return b.build();
    }

    private record Row(Object[] values) implements StructLike {
        @Override
        public int size() {
            return values.length;
        }

        @Override
        public <T> T get(int pos, Class<T> javaClass) {
            Object v = values[pos];
            return javaClass.cast(v instanceof byte[] b ? ByteBuffer.wrap(b) : v);
        }

        @Override
        public <T> void set(int pos, T value) {
            values[pos] = value;
        }
    }

    public static Expression expression(Expr e, Schema schema) {
        Expression out = Expressions.alwaysTrue();
        for (Expr.Pred p : e.preds()) {
            String c = schema.nameOf(p.fieldId());
            Expression one = switch (p.op()) {
                case EQ -> Expressions.equal(c, p.value());
                case IN -> Expressions.in(c, p.values());
                case LT -> Expressions.lessThan(c, p.value());
                case LE -> Expressions.lessThanOrEqual(c, p.value());
                case GT -> Expressions.greaterThan(c, p.value());
                case GE -> Expressions.greaterThanOrEqual(c, p.value());
                case IS_NULL -> Expressions.isNull(c);
                case NOT_NULL -> Expressions.notNull(c);
            };
            out = Expressions.and(out, one);
        }
        return out;
    }
}
