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
import com.ash.drishti.iceberg.types.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A partition transform (table spec, "Partition Transforms"): {@code identity}, {@code bucket[N]} (the spec's 32-bit
 * Murmur3 hash of each type's bytes), {@code truncate[W]}, {@code year}, {@code month}, {@code day}, {@code hour} and
 * {@code void}. A transform the engine does not know is kept by name: its partitions are read, never pruned.
 *
 * <p>Each one also projects a filter on its source column onto the partition value ({@link #project}), inclusively: a
 * partition the projection rules out cannot hold a matching row (the spec's "inclusive projection"). Values are in the
 * internal form ({@link Type}): a {@code day} is an Integer of days, {@code year}, {@code month} and {@code hour}
 * Integers counted from 1970.
 */
public sealed interface Transform {

    // toString() is the transform as metadata writes it (bucket[16], day)

    /** The partition value of a source value (null stays null). */
    Object apply(Type.Primitive source, Object v);

    /** The partition value's type. */
    Type.Primitive resultType(Type.Primitive source);

    /** Whether the partition value orders as the source does (so a range of sources maps to a range of partitions). */
    default boolean preservesOrder() {
        return false;
    }

    /** The comparison on the partition value that every row matching {@code p} satisfies, or empty when there is none. */
    default Optional<Expr.Pred> project(Type.Primitive source, Expr.Pred p, int partitionIndex) {
        return switch (p.op()) {
            case IS_NULL, NOT_NULL -> Optional.of(new Expr.Pred(partitionIndex, p.op(), List.of()));
            case EQ, IN -> {
                List<Object> out = new ArrayList<>();
                for (Object v : p.values()) {
                    out.add(apply(source, v));
                }
                yield Optional.of(new Expr.Pred(partitionIndex, p.op(), out));
            }
            case LT, LE -> preservesOrder() ? Optional.of(new Expr.Pred(partitionIndex, Expr.Op.LE, List.of(apply(source, p.value()))))
                    : Optional.empty();
            case GT, GE -> preservesOrder() ? Optional.of(new Expr.Pred(partitionIndex, Expr.Op.GE, List.of(apply(source, p.value()))))
                    : Optional.empty();
        };
    }

    Pattern BUCKET = Pattern.compile("bucket\\[(\\d+)]");
    Pattern TRUNCATE = Pattern.compile("truncate\\[(\\d+)]");

    static Transform parse(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        Matcher m = BUCKET.matcher(t);
        if (m.matches()) {
            return new Bucket(Integer.parseInt(m.group(1)));
        }
        m = TRUNCATE.matcher(t);
        if (m.matches()) {
            return new Truncate(Integer.parseInt(m.group(1)));
        }
        return switch (t) {
            case "identity" -> Identity.INSTANCE;
            case "year", "years" -> new Time(TimeUnit.YEAR);
            case "month", "months" -> new Time(TimeUnit.MONTH);
            case "day", "days" -> new Time(TimeUnit.DAY);
            case "hour", "hours" -> new Time(TimeUnit.HOUR);
            case "void" -> Void.INSTANCE;
            default -> new Unknown(text);
        };
    }

    /** The source value itself. */
    enum Identity implements Transform {
        INSTANCE;

        @Override
        public Object apply(Type.Primitive source, Object v) {
            return v;
        }

        @Override
        public Type.Primitive resultType(Type.Primitive source) {
            return source;
        }

        @Override
        public boolean preservesOrder() {
            return true;
        }

        @Override
        public Optional<Expr.Pred> project(Type.Primitive source, Expr.Pred p, int partitionIndex) {
            return Optional.of(new Expr.Pred(partitionIndex, p.op(), p.values()));
        }

        @Override
        public String toString() {
            return "identity";
        }
    }

    /** Always null: a dropped partition field of a v1 table. */
    enum Void implements Transform {
        INSTANCE;

        @Override
        public Object apply(Type.Primitive source, Object v) {
            return null;
        }

        @Override
        public Type.Primitive resultType(Type.Primitive source) {
            return source;
        }

        @Override
        public Optional<Expr.Pred> project(Type.Primitive source, Expr.Pred p, int partitionIndex) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "void";
        }
    }

    /** A transform this engine does not know: never pruned. */
    record Unknown(String text) implements Transform {
        @Override
        public Object apply(Type.Primitive source, Object v) {
            throw new com.ash.drishti.iceberg.types.UnsupportedFeature("the partition transform '" + text + "' for writing");
        }

        @Override
        public Type.Primitive resultType(Type.Primitive source) {
            return source;
        }

        @Override
        public Optional<Expr.Pred> project(Type.Primitive source, Expr.Pred p, int partitionIndex) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** {@code bucket[N]}: the 32-bit Murmur3 hash (x86, seed 0) of the value's bytes, as the spec defines them per type. */
    record Bucket(int n) implements Transform {
        public String name() {
            return "bucket[" + n + "]";
        }

        @Override
        public Object apply(Type.Primitive source, Object v) {
            return v == null ? null : (hash(source, v) & Integer.MAX_VALUE) % n;
        }

        @Override
        public Type.Primitive resultType(Type.Primitive source) {
            return Type.Primitive.INT;
        }

        @Override
        public Optional<Expr.Pred> project(Type.Primitive source, Expr.Pred p, int partitionIndex) {
            return p.op() == Expr.Op.EQ || p.op() == Expr.Op.IN ? Transform.super.project(source, p, partitionIndex) : Optional.empty();
        }

        @Override
        public String toString() {
            return name();
        }

        /** The spec's hash: int, long, date, time and timestamps as an 8-byte little-endian long; decimals' unscaled bytes. */
        static int hash(Type.Primitive type, Object v) {
            return switch (type.id()) {
                case INT, DATE, LONG, TIME, TIMESTAMP, TIMESTAMPTZ -> Murmur3.hash(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(((Number) v).longValue()).array());
                case DECIMAL -> Murmur3.hash(((BigDecimal) v).unscaledValue().toByteArray());
                case STRING -> Murmur3.hash(v.toString().getBytes(StandardCharsets.UTF_8));
                case UUID -> {
                    UUID u = (UUID) v;
                    yield Murmur3.hash(ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array());
                }
                case FIXED, BINARY -> Murmur3.hash((byte[]) v);
                default -> throw new IllegalArgumentException("bucket does not apply to " + type);
            };
        }
    }

    /** {@code truncate[W]}: numbers rounded down to a multiple of W, strings to W code points, binary to W bytes. */
    record Truncate(int width) implements Transform {
        public String name() {
            return "truncate[" + width + "]";
        }

        @Override
        public Object apply(Type.Primitive source, Object v) {
            if (v == null) {
                return null;
            }
            return switch (source.id()) {
                case INT -> {
                    int x = (Integer) v;
                    yield x - (((x % width) + width) % width);
                }
                case LONG -> {
                    long x = (Long) v;
                    yield x - (((x % width) + width) % width);
                }
                case DECIMAL -> {
                    BigDecimal d = (BigDecimal) v;
                    BigInteger u = d.unscaledValue();
                    BigInteger w = BigInteger.valueOf(width);
                    yield new BigDecimal(u.subtract(u.mod(w)), d.scale());
                }
                case STRING -> {
                    String s = v.toString();
                    yield s.codePointCount(0, s.length()) <= width ? s : s.substring(0, s.offsetByCodePoints(0, width));
                }
                case BINARY, FIXED -> {
                    byte[] b = (byte[]) v;
                    yield b.length <= width ? b : java.util.Arrays.copyOf(b, width);
                }
                default -> throw new IllegalArgumentException("truncate does not apply to " + source);
            };
        }

        @Override
        public Type.Primitive resultType(Type.Primitive source) {
            return source;
        }

        @Override
        public boolean preservesOrder() {
            return true;
        }

        @Override
        public String toString() {
            return name();
        }
    }

    /** The calendar units of the time transforms. */
    enum TimeUnit {
        YEAR, MONTH, DAY, HOUR
    }

    /** {@code year}, {@code month}, {@code day}, {@code hour} of a date or timestamp, counted from 1970 (UTC). */
    record Time(TimeUnit unit) implements Transform {
        static final long MICROS_PER_DAY = 86_400_000_000L;
        static final long MICROS_PER_HOUR = 3_600_000_000L;

        public String name() {
            return unit.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public Object apply(Type.Primitive source, Object v) {
            if (v == null) {
                return null;
            }
            boolean date = source.id() == Type.Id.DATE;
            if (unit == TimeUnit.HOUR) {
                if (date) {
                    throw new IllegalArgumentException("hour does not apply to a date");
                }
                return (int) Math.floorDiv((Long) v, MICROS_PER_HOUR);
            }
            long days = date ? ((Number) v).longValue() : Math.floorDiv((Long) v, MICROS_PER_DAY);
            if (unit == TimeUnit.DAY) {
                return (int) days;
            }
            LocalDate d = LocalDate.ofEpochDay(days);
            return unit == TimeUnit.YEAR ? d.getYear() - 1970 : (d.getYear() - 1970) * 12 + d.getMonthValue() - 1;
        }

        @Override
        public Type.Primitive resultType(Type.Primitive source) {
            return unit == TimeUnit.DAY ? Type.Primitive.DATE : Type.Primitive.INT;
        }

        @Override
        public boolean preservesOrder() {
            return true;
        }

        @Override
        public String toString() {
            return name();
        }
    }

    /** Murmur3 x86 32-bit with seed 0, as the spec and Guava compute it. */
    final class Murmur3 {
        private Murmur3() {}

        static int hash(byte[] data) {
            int h = 0;
            int len = data.length;
            int i = 0;
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            for (; i + 4 <= len; i += 4) {
                h = mix(h, b.getInt(i));
                h = Integer.rotateLeft(h, 13) * 5 + 0xe6546b64;
            }
            int k = 0;
            for (int j = len - 1; j >= i; j--) {
                k = (k << 8) | (data[j] & 0xff);
            }
            if (len > i) {
                h = mix(h, k);
            }
            h ^= len;
            h ^= h >>> 16;
            h *= 0x85ebca6b;
            h ^= h >>> 13;
            h *= 0xc2b2ae35;
            h ^= h >>> 16;
            return h;
        }

        private static int mix(int h, int k) {
            k *= 0xcc9e2d51;
            k = Integer.rotateLeft(k, 15);
            k *= 0x1b873593;
            return h ^ k;
        }
    }
}
