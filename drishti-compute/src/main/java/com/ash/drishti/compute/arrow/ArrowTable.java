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
package com.ash.drishti.compute.arrow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A columnar table as the compute workers exchange it (docs/architecture/PYTHON_COMPUTE.md section 7.3): typed columns held in primitive
 * arrays, so a million rows cost what their values cost. Five column types cover what the engines need: 64-bit integers, doubles, booleans,
 * text, and a fixed-length vector of doubles per row (a 509-day P&amp;L path). Immutable once built; {@link #slice} and {@link #concat} are
 * how a request is split into chunks for several workers and how their answers are gathered.
 */
public final class ArrowTable {

    /** The column types. {@code FLOAT64_VECTOR} is Arrow's FixedSizeList of doubles: {@code width} values per row. */
    public enum Type { INT64, FLOAT64, BOOL, UTF8, FLOAT64_VECTOR }

    /**
     * One column. {@code data} is a {@code long[]}, {@code double[]}, {@code boolean[]}, {@code String[]} or, for a vector column, a
     * {@code double[]} of {@code rows * width} values, row after row. {@code nulls} marks null rows (null or empty: none).
     */
    public record Column(String name, Type type, int width, Object data, BitSet nulls) {
        public Column {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(data, "data");
            if (type == Type.FLOAT64_VECTOR && width < 1) {
                throw new IllegalArgumentException("vector column " + name + " needs a width of at least 1");
            }
            width = type == Type.FLOAT64_VECTOR ? width : 1;
            nulls = nulls == null || nulls.isEmpty() ? null : (BitSet) nulls.clone();
        }

        /** The number of rows the data holds. */
        public int rows() {
            return switch (type) {
                case INT64 -> ((long[]) data).length;
                case FLOAT64 -> ((double[]) data).length;
                case BOOL -> ((boolean[]) data).length;
                case UTF8 -> ((String[]) data).length;
                case FLOAT64_VECTOR -> ((double[]) data).length / width;
            };
        }

        public boolean isNull(int row) {
            return nulls != null && nulls.get(row);
        }

        public int nullCount() {
            return nulls == null ? 0 : nulls.cardinality();
        }

        /** The value of a row as an object (Long, Double, Boolean, String, double[]), or null. */
        public Object value(int row) {
            if (isNull(row)) {
                return null;
            }
            return switch (type) {
                case INT64 -> ((long[]) data)[row];
                case FLOAT64 -> ((double[]) data)[row];
                case BOOL -> ((boolean[]) data)[row];
                case UTF8 -> ((String[]) data)[row];
                case FLOAT64_VECTOR -> Arrays.copyOfRange((double[]) data, row * width, (row + 1) * width);
            };
        }

        Column slice(int from, int to) {
            Object d = switch (type) {
                case INT64 -> Arrays.copyOfRange((long[]) data, from, to);
                case FLOAT64 -> Arrays.copyOfRange((double[]) data, from, to);
                case BOOL -> Arrays.copyOfRange((boolean[]) data, from, to);
                case UTF8 -> Arrays.copyOfRange((String[]) data, from, to);
                case FLOAT64_VECTOR -> Arrays.copyOfRange((double[]) data, from * width, to * width);
            };
            return new Column(name, type, width, d, nulls == null ? null : nulls.get(from, to));
        }
    }

    private final List<Column> columns;
    private final int rows;

    public ArrowTable(List<Column> columns) {
        this.columns = List.copyOf(columns);
        int n = -1;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Column c : this.columns) {
            if (!names.add(c.name())) {
                throw new IllegalArgumentException("two columns are named " + c.name());
            }
            if (n >= 0 && c.rows() != n) {
                throw new IllegalArgumentException("column " + c.name() + " has " + c.rows() + " rows, the others " + n);
            }
            n = c.rows();
        }
        this.rows = Math.max(n, 0);
    }

    /** An empty table of no columns. */
    public static ArrowTable empty() {
        return new ArrowTable(List.of());
    }

    public int rows() {
        return rows;
    }

    public List<Column> columns() {
        return columns;
    }

    public List<String> names() {
        return columns.stream().map(Column::name).toList();
    }

    public Column column(String name) {
        for (Column c : columns) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("no column " + name + " (columns: " + names() + ")");
    }

    public double[] doubles(String name) {
        return (double[]) typed(name, Type.FLOAT64).data();
    }

    public long[] longs(String name) {
        return (long[]) typed(name, Type.INT64).data();
    }

    public String[] strings(String name) {
        return (String[]) typed(name, Type.UTF8).data();
    }

    /** One row of a vector column. */
    public double[] vector(String name, int row) {
        return (double[]) typed(name, Type.FLOAT64_VECTOR).value(row);
    }

    private Column typed(String name, Type t) {
        Column c = column(name);
        if (c.type() != t) {
            throw new IllegalArgumentException("column " + name + " is " + c.type() + ", not " + t);
        }
        return c;
    }

    /** Rows {@code from} (inclusive) to {@code to} (exclusive), copied. */
    public ArrowTable slice(int from, int to) {
        if (from < 0 || to > rows || from > to) {
            throw new IndexOutOfBoundsException("slice " + from + ".." + to + " of " + rows + " rows");
        }
        List<Column> out = new ArrayList<>(columns.size());
        columns.forEach(c -> out.add(c.slice(from, to)));
        return new ArrowTable(out);
    }

    /** The tables one after another; they must have the same columns (names, types, widths) in the same order. */
    public static ArrowTable concat(List<ArrowTable> parts) {
        List<ArrowTable> ps = parts.stream().filter(p -> !p.columns.isEmpty()).toList();
        if (ps.isEmpty()) {
            return parts.isEmpty() ? empty() : parts.get(0);
        }
        ArrowTable first = ps.get(0);
        int total = ps.stream().mapToInt(ArrowTable::rows).sum();
        List<Column> out = new ArrayList<>();
        for (int c = 0; c < first.columns.size(); c++) {
            Column head = first.columns.get(c);
            for (ArrowTable p : ps) {
                Column o = p.columns.size() > c ? p.columns.get(c) : null;
                if (o == null || !o.name().equals(head.name()) || o.type() != head.type() || o.width() != head.width()) {
                    throw new IllegalArgumentException("the parts differ at column " + head.name());
                }
            }
            Object data = switch (head.type()) {
                case INT64 -> new long[total];
                case FLOAT64, FLOAT64_VECTOR -> new double[total * head.width()];
                case BOOL -> new boolean[total];
                case UTF8 -> new String[total];
            };
            BitSet nulls = new BitSet();
            int at = 0;
            for (ArrowTable p : ps) {
                Column o = p.columns.get(c);
                int n = o.rows();
                System.arraycopy(o.data(), 0, data, at * head.width(), n * head.width());
                if (o.nulls() != null) {
                    final int base = at;
                    o.nulls().stream().forEach(i -> nulls.set(base + i));
                }
                at += n;
            }
            out.add(new Column(head.name(), head.type(), head.width(), data, nulls));
        }
        return new ArrowTable(out);
    }

    /** Rows as maps (column name to value), for small results and tests; vectors come back as {@code double[]}. */
    public List<Map<String, Object>> toRows() {
        List<Map<String, Object>> out = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Column c : columns) {
                m.put(c.name(), c.value(r));
            }
            out.add(Collections.unmodifiableMap(m));
        }
        return out;
    }

    /** A builder of columns of one length. */
    public static Builder builder() {
        return new Builder();
    }

    /** Collects columns; {@link #build} checks they have the same length. */
    public static final class Builder {
        private final List<Column> columns = new ArrayList<>();

        public Builder longs(String name, long... values) {
            columns.add(new Column(name, Type.INT64, 1, values, null));
            return this;
        }

        public Builder doubles(String name, double... values) {
            columns.add(new Column(name, Type.FLOAT64, 1, values, null));
            return this;
        }

        public Builder bools(String name, boolean... values) {
            columns.add(new Column(name, Type.BOOL, 1, values, null));
            return this;
        }

        /** Text; a null element is a null row. */
        public Builder strings(String name, String... values) {
            BitSet nulls = new BitSet();
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    nulls.set(i);
                }
            }
            columns.add(new Column(name, Type.UTF8, 1, values, nulls));
            return this;
        }

        /** A vector column: {@code values} holds {@code width} doubles per row, row after row. */
        public Builder vectors(String name, int width, double[] values) {
            if (values.length % width != 0) {
                throw new IllegalArgumentException(name + ": " + values.length + " values is not a whole number of rows of " + width);
            }
            columns.add(new Column(name, Type.FLOAT64_VECTOR, width, values, null));
            return this;
        }

        public Builder column(Column c) {
            columns.add(c);
            return this;
        }

        public ArrowTable build() {
            return new ArrowTable(columns);
        }
    }
}
