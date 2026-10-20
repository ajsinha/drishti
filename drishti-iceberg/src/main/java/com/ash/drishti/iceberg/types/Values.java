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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

/**
 * Values of primitive types in their internal form ({@link Type}): the spec's single-value binary serialization (the
 * lower and upper bounds of manifests and the partition summaries of manifest lists), ordering, and promotion of a
 * value read at a narrower type (int to long, float to double, a decimal of lower precision).
 */
public final class Values {

    private Values() {}

    /** A bound or summary value from its binary form ("Binary single-value serialization" of the spec). */
    public static Object fromBytes(Type.Primitive type, byte[] b) {
        if (b == null) {
            return null;
        }
        ByteBuffer le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type.id()) {
            case BOOLEAN -> b.length > 0 && b[0] != 0;
            case INT, DATE -> le.getInt();
            case LONG, TIME, TIMESTAMP, TIMESTAMPTZ -> b.length == 4 ? (long) le.getInt() : le.getLong();   // a bound written before int to long
            case FLOAT -> le.getFloat();
            case DOUBLE -> b.length == 4 ? (double) le.getFloat() : le.getDouble();                       // likewise float to double
            case DECIMAL -> new BigDecimal(new BigInteger(b), type.scale());
            case STRING -> new String(b, StandardCharsets.UTF_8);
            case UUID -> {
                ByteBuffer be = ByteBuffer.wrap(b);
                yield new UUID(be.getLong(), be.getLong());
            }
            case FIXED, BINARY -> b.clone();
            default -> throw new IllegalArgumentException("no single-value form for " + type);
        };
    }

    /** The binary single-value form of a value. */
    public static byte[] toBytes(Type.Primitive type, Object v) {
        return switch (type.id()) {
            case BOOLEAN -> new byte[] {(byte) ((Boolean) v ? 1 : 0)};
            case INT, DATE -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(((Number) v).intValue()).array();
            case LONG, TIME, TIMESTAMP, TIMESTAMPTZ -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(((Number) v).longValue()).array();
            case FLOAT -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(((Number) v).floatValue()).array();
            case DOUBLE -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(((Number) v).doubleValue()).array();
            case DECIMAL -> ((BigDecimal) v).setScale(type.scale(), RoundingMode.UNNECESSARY).unscaledValue().toByteArray();
            case STRING -> v.toString().getBytes(StandardCharsets.UTF_8);
            case UUID -> {
                UUID u = (UUID) v;
                yield ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
            }
            case FIXED, BINARY -> ((byte[]) v).clone();
            default -> throw new IllegalArgumentException("no single-value form for " + type);
        };
    }

    /**
     * Orders two non-null values of one primitive type as Iceberg does: numbers by value, strings by Unicode code point
     * (the order of their UTF-8 bytes), binary and fixed unsigned byte by byte, UUIDs as unsigned 128-bit numbers.
     */
    public static int compare(Type.Primitive type, Object a, Object b) {
        return switch (type.id()) {
            case BOOLEAN -> Boolean.compare((Boolean) a, (Boolean) b);
            case INT, DATE -> Integer.compare(((Number) a).intValue(), ((Number) b).intValue());
            case LONG, TIME, TIMESTAMP, TIMESTAMPTZ -> Long.compare(((Number) a).longValue(), ((Number) b).longValue());
            case FLOAT -> Float.compare(((Number) a).floatValue(), ((Number) b).floatValue());
            case DOUBLE -> Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            case DECIMAL -> ((BigDecimal) a).compareTo((BigDecimal) b);
            case STRING -> compareCodePoints(a.toString(), b.toString());
            case UUID -> {
                UUID x = (UUID) a;
                UUID y = (UUID) b;
                int c = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
                yield c != 0 ? c : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
            }
            case FIXED, BINARY -> Arrays.compareUnsigned((byte[]) a, (byte[]) b);
            default -> throw new IllegalArgumentException("not ordered: " + type);
        };
    }

    /** Strings in code point order (UTF-16's order differs for characters outside the basic plane). */
    public static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** Equality as Iceberg means it (byte arrays by content, decimals by value). */
    public static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof byte[] x && b instanceof byte[] y) {
            return Arrays.equals(x, y);
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        if (a instanceof Number x && b instanceof Number y && !(a instanceof BigDecimal) && !(b instanceof BigDecimal)) {
            if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
                return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
            }
            return x.longValue() == y.longValue();
        }
        return a.equals(b);
    }

    /** A value read at the file's (narrower) type, as the table's type now is: int to long, float to double, a decimal's precision. */
    public static Object promote(Type.Primitive to, Object v) {
        if (v == null) {
            return null;
        }
        return switch (to.id()) {
            case LONG -> v instanceof Integer i ? (Object) i.longValue() : v;
            case DOUBLE -> v instanceof Float f ? (Object) f.doubleValue() : v;
            default -> v;
        };
    }

    /** A partition or literal value from its JSON text in metadata (a single-value JSON form: {@code 20240101}, "2024-01-01"). */
    public static Object fromJson(Type.Primitive type, com.fasterxml.jackson.databind.JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        return switch (type.id()) {
            case BOOLEAN -> n.asBoolean();
            case INT -> n.asInt();
            case DATE -> n.isTextual() ? (int) LocalDate.parse(n.asText()).toEpochDay() : n.asInt();
            case LONG, TIME, TIMESTAMP, TIMESTAMPTZ -> n.asLong();
            case FLOAT -> (float) n.asDouble();
            case DOUBLE -> n.asDouble();
            case DECIMAL -> new BigDecimal(n.asText()).setScale(type.scale(), RoundingMode.UNNECESSARY);
            case STRING -> n.asText();
            case UUID -> UUID.fromString(n.asText());
            case FIXED, BINARY -> java.util.HexFormat.of().parseHex(n.asText());
            default -> throw new IllegalArgumentException("no JSON form for " + type);
        };
    }
}
