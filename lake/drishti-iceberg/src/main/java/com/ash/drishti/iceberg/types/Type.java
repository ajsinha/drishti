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
package com.ash.drishti.iceberg.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Iceberg type (table spec, "Schemas and Data Types"), format versions 1 and 2: the primitives, {@code struct},
 * {@code list} and {@code map}. Version 3's types ({@code timestamp_ns}, {@code variant}, {@code geometry},
 * {@code unknown}, …) are refused by name when a schema is read ({@link UnsupportedFeature}).
 *
 * <p>Values are held in their internal form throughout the engine: {@code boolean} Boolean, {@code int} Integer,
 * {@code long} Long, {@code float} Float, {@code double} Double, {@code decimal} BigDecimal (at the type's scale),
 * {@code date} Integer (days from 1970-01-01), {@code time} Long (microseconds from midnight), {@code timestamp} and
 * {@code timestamptz} Long (microseconds from the epoch), {@code string} String, {@code uuid} UUID, {@code fixed} and
 * {@code binary} byte[]; a struct is an {@code Object[]} in field order, a list a {@code List}, a map a {@code Map}.
 */
public sealed interface Type permits Type.Primitive, Type.Struct, Type.ListType, Type.MapType {

    /** The kinds of types. */
    enum Id {
        BOOLEAN, INT, LONG, FLOAT, DOUBLE, DECIMAL, DATE, TIME, TIMESTAMP, TIMESTAMPTZ, STRING, UUID, FIXED, BINARY, STRUCT, LIST, MAP
    }

    Id id();

    default boolean isPrimitive() {
        return this instanceof Primitive;
    }

    /** A primitive type; {@code precision} and {@code scale} for decimal, {@code length} for fixed (else 0). */
    record Primitive(Id id, int precision, int scale, int length) implements Type {

        public static final Primitive BOOLEAN = new Primitive(Id.BOOLEAN, 0, 0, 0);
        public static final Primitive INT = new Primitive(Id.INT, 0, 0, 0);
        public static final Primitive LONG = new Primitive(Id.LONG, 0, 0, 0);
        public static final Primitive FLOAT = new Primitive(Id.FLOAT, 0, 0, 0);
        public static final Primitive DOUBLE = new Primitive(Id.DOUBLE, 0, 0, 0);
        public static final Primitive DATE = new Primitive(Id.DATE, 0, 0, 0);
        public static final Primitive TIME = new Primitive(Id.TIME, 0, 0, 0);
        public static final Primitive TIMESTAMP = new Primitive(Id.TIMESTAMP, 0, 0, 0);
        public static final Primitive TIMESTAMPTZ = new Primitive(Id.TIMESTAMPTZ, 0, 0, 0);
        public static final Primitive STRING = new Primitive(Id.STRING, 0, 0, 0);
        public static final Primitive UUID = new Primitive(Id.UUID, 0, 0, 0);
        public static final Primitive BINARY = new Primitive(Id.BINARY, 0, 0, 0);

        public static Primitive decimal(int precision, int scale) {
            return new Primitive(Id.DECIMAL, precision, scale, 0);
        }

        public static Primitive fixed(int length) {
            return new Primitive(Id.FIXED, 0, 0, length);
        }

        private static final Pattern DECIMAL = Pattern.compile("decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
        private static final Pattern FIXED = Pattern.compile("fixed\\[\\s*(\\d+)\\s*]");
        private static final Map<String, Primitive> NAMED = Map.ofEntries(Map.entry("boolean", BOOLEAN), Map.entry("int", INT),
                Map.entry("long", LONG), Map.entry("float", FLOAT), Map.entry("double", DOUBLE), Map.entry("date", DATE),
                Map.entry("time", TIME), Map.entry("timestamp", TIMESTAMP), Map.entry("timestamptz", TIMESTAMPTZ),
                Map.entry("string", STRING), Map.entry("uuid", UUID), Map.entry("binary", BINARY));

        /** The primitive a schema names ({@code long}, {@code decimal(9,2)}, {@code fixed[16]}); refuses a type it does not know. */
        public static Primitive parse(String name) {
            String n = name.trim().toLowerCase(Locale.ROOT);
            Primitive p = NAMED.get(n);
            if (p != null) {
                return p;
            }
            Matcher m = DECIMAL.matcher(n);
            if (m.matches()) {
                return decimal(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
            m = FIXED.matcher(n);
            if (m.matches()) {
                return fixed(Integer.parseInt(m.group(1)));
            }
            throw new UnsupportedFeature("the Iceberg type '" + name + "'"
                    + (n.startsWith("timestamp_ns") || n.equals("variant") || n.startsWith("geometry") || n.startsWith("geography") || n.equals("unknown")
                            ? " (format version 3)" : ""));
        }

        @Override
        public String toString() {
            return switch (id) {
                case DECIMAL -> "decimal(" + precision + ", " + scale + ")";
                case FIXED -> "fixed[" + length + "]";
                default -> id.name().toLowerCase(Locale.ROOT);
            };
        }
    }

    /** A struct of fields. */
    record Struct(List<NestedField> fields) implements Type {
        public Struct {
            fields = List.copyOf(fields);
        }

        @Override
        public Id id() {
            return Id.STRUCT;
        }

        /** The field of this id among the direct children, or null. */
        public NestedField field(int fieldId) {
            for (NestedField f : fields) {
                if (f.id() == fieldId) {
                    return f;
                }
            }
            return null;
        }

        /** The direct child named {@code name} (case as written), or null. */
        public NestedField field(String name) {
            for (NestedField f : fields) {
                if (f.name().equals(name)) {
                    return f;
                }
            }
            return null;
        }

        /** The index of the direct child of this id, or -1. */
        public int indexOf(int fieldId) {
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).id() == fieldId) {
                    return i;
                }
            }
            return -1;
        }

        public Struct with(NestedField added) {
            List<NestedField> all = new ArrayList<>(fields);
            all.add(added);
            return new Struct(all);
        }
    }

    /** A list; its element is a field with its own id. */
    record ListType(int elementId, boolean elementRequired, Type element) implements Type {
        @Override
        public Id id() {
            return Id.LIST;
        }

        public NestedField elementField() {
            return new NestedField(elementId, "element", elementRequired, element, null);
        }
    }

    /** A map; its key (always required) and value are fields with their own ids. */
    record MapType(int keyId, Type key, int valueId, boolean valueRequired, Type value) implements Type {
        @Override
        public Id id() {
            return Id.MAP;
        }

        public NestedField keyField() {
            return new NestedField(keyId, "key", true, key, null);
        }

        public NestedField valueField() {
            return new NestedField(valueId, "value", valueRequired, value, null);
        }
    }
}
