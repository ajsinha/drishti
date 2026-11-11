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
package com.ash.drishti.iceberg.read;

import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.Converter;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType;

/**
 * parquet-java's record assembly turned into Iceberg values in their internal form ({@link Type}): a converter per
 * requested column, matched to the table's field by id, that also widens what the file holds to what the table now
 * says (int to long, float to double, timestamps in milliseconds, nanoseconds or INT96 to microseconds). Structs are
 * {@code Object[]} in the table's field order (a field the file lacks is null), lists {@code List}, maps {@code Map}.
 * Converters are used by one reader thread.
 */
final class Converters {

    private Converters() {}

    /** Where a converted value goes: a slot of a struct, an element of a list, a key or value of a map. */
    interface Sink {
        void set(Object value);
    }

    /** Ids of the file's fields: their own, or (a file written without ids) the table's name mapping. */
    interface Ids {
        Integer of(org.apache.parquet.schema.Type fileField, String path);
    }

    /** The converter of one file field for the table's field {@code expected}. */
    static Converter of(org.apache.parquet.schema.Type file, NestedField expected, String path, Ids ids, Sink sink) {
        Type t = expected.type();
        if (file.isPrimitive()) {
            if (!(t instanceof Type.Primitive p)) {
                throw new IllegalStateException("column " + path + " is " + file + " in the file but " + t + " in the table");
            }
            return new Leaf(file.asPrimitiveType(), p, path, sink);
        }
        GroupType g = file.asGroupType();
        return switch (t) {
            case Type.Struct s -> new StructConverter(g, s, path, ids, sink);
            case Type.ListType l -> list(g, l, path, ids, sink);
            case Type.MapType m -> new MapConverter(g, m, path, ids, sink);
            case Type.Primitive p -> throw new IllegalStateException("column " + path + " is a group in the file but " + p + " in the table");
        };
    }

    /** A struct: its children matched by id; children of the table the file lacks stay null. */
    static final class StructConverter extends GroupConverter {
        private final Converter[] children;
        private final int size;
        private final Sink sink;
        private Object[] current;

        StructConverter(GroupType file, Type.Struct expected, String path, Ids ids, Sink sink) {
            this.sink = sink;
            this.size = expected.fields().size();
            this.children = new Converter[file.getFieldCount()];
            for (int i = 0; i < file.getFieldCount(); i++) {
                org.apache.parquet.schema.Type f = file.getType(i);
                String childPath = path.isEmpty() ? f.getName() : path + "." + f.getName();
                Integer id = ids.of(f, childPath);
                int slot = id == null ? -1 : expected.indexOf(id);
                if (slot < 0) {
                    children[i] = Ignore.of(f);                 // pruned from the request; kept only when a reader asks for more
                } else {
                    children[i] = of(f, expected.fields().get(slot), childPath, ids, v -> current[slot] = v);
                }
            }
        }

        @Override
        public Converter getConverter(int fieldIndex) {
            return children[fieldIndex];
        }

        @Override
        public void start() {
            current = new Object[size];
        }

        @Override
        public void end() {
            sink.set(current);
        }
    }

    /**
     * A list in any of Parquet's layouts: the standard three levels ({@code repeated group list { element }}), and the
     * older two ({@code repeated <element>}, or a repeated group that is the element itself: {@code array},
     * {@code <name>_tuple}, or a group of several fields).
     */
    static Converter list(GroupType file, Type.ListType expected, String path, Ids ids, Sink sink) {
        if (file.getFieldCount() != 1) {
            throw new IllegalStateException("list column " + path + " has " + file.getFieldCount() + " fields in the file");
        }
        org.apache.parquet.schema.Type repeated = file.getType(0);
        boolean twoLevel = repeated.isPrimitive() || repeated.asGroupType().getFieldCount() > 1 || repeated.getName().equals("array")
                || repeated.getName().equals(file.getName() + "_tuple");
        return new ListConverter(repeated, twoLevel, expected, path, ids, sink);
    }

    static final class ListConverter extends GroupConverter {
        private final Converter repeated;
        private final Sink sink;
        private List<Object> current;

        ListConverter(org.apache.parquet.schema.Type repeatedField, boolean twoLevel, Type.ListType expected, String path, Ids ids, Sink sink) {
            this.sink = sink;
            NestedField element = expected.elementField();
            if (twoLevel) {
                this.repeated = of(repeatedField, element, path + ".element", ids, v -> current.add(v));
            } else {
                GroupType g = repeatedField.asGroupType();
                Converter inner = of(g.getType(0), element, path + ".element", ids, new Sink() {
                    @Override
                    public void set(Object v) {
                        current.set(current.size() - 1, v);
                    }
                });
                this.repeated = new GroupConverter() {
                    @Override
                    public Converter getConverter(int fieldIndex) {
                        return inner;
                    }

                    @Override
                    public void start() {
                        current.add(null);                     // an element that is never set is a null element
                    }

                    @Override
                    public void end() {
                    }
                };
            }
        }

        @Override
        public Converter getConverter(int fieldIndex) {
            return repeated;
        }

        @Override
        public void start() {
            current = new ArrayList<>();
        }

        @Override
        public void end() {
            sink.set(current);
        }
    }

    /** A map: {@code repeated group key_value { key; value }}. */
    static final class MapConverter extends GroupConverter {
        private final GroupConverter entry;
        private final Sink sink;
        private Map<Object, Object> current;
        private Object key;
        private Object value;

        MapConverter(GroupType file, Type.MapType expected, String path, Ids ids, Sink sink) {
            this.sink = sink;
            GroupType kv = file.getType(0).asGroupType();
            Converter k = of(kv.getType(0), expected.keyField(), path + ".key", ids, v -> key = v);
            Converter v = kv.getFieldCount() > 1 ? of(kv.getType(1), expected.valueField(), path + ".value", ids, x -> value = x) : null;
            this.entry = new GroupConverter() {
                @Override
                public Converter getConverter(int fieldIndex) {
                    return fieldIndex == 0 ? k : v;
                }

                @Override
                public void start() {
                    key = null;
                    value = null;
                }

                @Override
                public void end() {
                    current.put(key, value);
                }
            };
        }

        @Override
        public Converter getConverter(int fieldIndex) {
            return entry;
        }

        @Override
        public void start() {
            current = new LinkedHashMap<>();
        }

        @Override
        public void end() {
            sink.set(current);
        }
    }

    /** A converter that drops what it is given (a part of a group the table no longer has). */
    static final class Ignore {
        private Ignore() {}

        static Converter of(org.apache.parquet.schema.Type t) {
            if (t.isPrimitive()) {
                return new PrimitiveConverter() {
                    @Override
                    public void addBinary(Binary value) {
                    }

                    @Override
                    public void addBoolean(boolean value) {
                    }

                    @Override
                    public void addDouble(double value) {
                    }

                    @Override
                    public void addFloat(float value) {
                    }

                    @Override
                    public void addInt(int value) {
                    }

                    @Override
                    public void addLong(long value) {
                    }
                };
            }
            GroupType g = t.asGroupType();
            Converter[] children = new Converter[g.getFieldCount()];
            for (int i = 0; i < children.length; i++) {
                children[i] = of(g.getType(i));
            }
            return new GroupConverter() {
                @Override
                public Converter getConverter(int fieldIndex) {
                    return children[fieldIndex];
                }

                @Override
                public void start() {
                }

                @Override
                public void end() {
                }
            };
        }
    }

    /** One primitive column: the file's physical type and annotation to the table's type. */
    static final class Leaf extends PrimitiveConverter {
        private final Type.Primitive expected;
        private final Sink sink;
        private final String path;
        private final long timeFactor;            // multiply (positive) or divide (negative) to get microseconds
        private final int fileScale;

        Leaf(PrimitiveType file, Type.Primitive expected, String path, Sink sink) {
            this.expected = expected;
            this.sink = sink;
            this.path = path;
            LogicalTypeAnnotation a = file.getLogicalTypeAnnotation();
            long factor = 1;
            if (a instanceof LogicalTypeAnnotation.TimestampLogicalTypeAnnotation ts) {
                factor = unit(ts.getUnit());
            } else if (a instanceof LogicalTypeAnnotation.TimeLogicalTypeAnnotation tm) {
                factor = unit(tm.getUnit());
            } else if ((expected.id() == Type.Id.TIME) && file.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.INT32) {
                factor = 1000;                         // TIME(MILLIS) in an INT32
            }
            this.timeFactor = factor;
            this.fileScale = a instanceof LogicalTypeAnnotation.DecimalLogicalTypeAnnotation d ? d.getScale() : expected.scale();
        }

        private static long unit(LogicalTypeAnnotation.TimeUnit u) {
            return switch (u) {
                case MILLIS -> 1000;
                case MICROS -> 1;
                case NANOS -> -1000;
            };
        }

        private long micros(long v) {
            return timeFactor >= 1 ? v * timeFactor : Math.floorDiv(v, -timeFactor);
        }

        private BigDecimal decimal(BigInteger unscaled) {
            BigDecimal d = new BigDecimal(unscaled, fileScale);
            return d.scale() == expected.scale() ? d : d.setScale(expected.scale(), RoundingMode.UNNECESSARY);
        }

        @Override
        public void addBoolean(boolean value) {
            sink.set(value);
        }

        @Override
        public void addInt(int value) {
            sink.set(switch (expected.id()) {
                case LONG -> (long) value;
                case DECIMAL -> decimal(BigInteger.valueOf(value));
                case TIME, TIMESTAMP, TIMESTAMPTZ -> micros(value);
                case DOUBLE -> (double) value;
                default -> value;
            });
        }

        @Override
        public void addLong(long value) {
            sink.set(switch (expected.id()) {
                case DECIMAL -> decimal(BigInteger.valueOf(value));
                case TIME, TIMESTAMP, TIMESTAMPTZ -> micros(value);
                case INT -> Math.toIntExact(value);
                default -> value;
            });
        }

        @Override
        public void addFloat(float value) {
            sink.set(expected.id() == Type.Id.DOUBLE ? (Object) (double) value : value);
        }

        @Override
        public void addDouble(double value) {
            sink.set(value);
        }

        @Override
        public void addBinary(Binary value) {
            sink.set(switch (expected.id()) {
                case STRING -> value.toStringUsingUTF8();
                case DECIMAL -> decimal(new BigInteger(value.getBytes()));
                case UUID -> {
                    ByteBuffer b = ByteBuffer.wrap(value.getBytes());
                    yield new UUID(b.getLong(), b.getLong());
                }
                case TIMESTAMP, TIMESTAMPTZ -> int96(value);
                case FIXED, BINARY -> value.getBytes();
                default -> throw new IllegalStateException("column " + path + ": binary data for " + expected);
            });
        }

        /** An INT96 timestamp (Hive and old Spark): nanoseconds of the day, then the Julian day, little-endian. */
        private static long int96(Binary b) {
            ByteBuffer buf = ByteBuffer.wrap(b.getBytes()).order(ByteOrder.LITTLE_ENDIAN);
            long nanos = buf.getLong();
            long julian = buf.getInt();
            return (julian - 2_440_588L) * 86_400_000_000L + nanos / 1000;
        }
    }
}
