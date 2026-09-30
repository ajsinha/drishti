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
package com.ash.drishti.api;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable, source-neutral document tree. Every source converts its native records into this
 * form; everything downstream (fingerprints, Sutra bindings, inference) reads only this form.
 *
 * <p>Navigation never throws: a missing field or index yields {@link #missing()}.
 */
public sealed interface DataNode permits DataNode.Obj, DataNode.Arr, DataNode.Val, DataNode.Missing {

    NodeType type();

    /** Field of an object; {@link #missing()} for any other node or an absent field. */
    default DataNode get(String field) {
        return Missing.INSTANCE;
    }

    /** Element of an array; {@link #missing()} for any other node or an index out of range. */
    default DataNode get(int index) {
        return Missing.INSTANCE;
    }

    /** Number of fields or elements; zero for scalars. */
    default int size() {
        return 0;
    }

    default boolean isMissing() {
        return type() == NodeType.MISSING;
    }

    default boolean isNull() {
        return type() == NodeType.NULL || type() == NodeType.MISSING;
    }

    /** The scalar value as text; empty for containers and missing nodes. */
    default String asText() {
        return "";
    }

    /** The scalar value as a double; {@code NaN} when it is not numeric. */
    default double asDouble() {
        return Double.NaN;
    }

    default boolean asBoolean() {
        return false;
    }

    /** The raw Java value: {@code Map}, {@code List}, {@code String}, {@code Number}, {@code Boolean} or null. */
    Object unwrap();

    /** Follows a dotted path with optional indexes: {@code legs[0].cashflows}. A leading {@code $.} is ignored. */
    default DataNode at(String path) {
        return PathWalker.walk(this, path);
    }

    static DataNode missing() {
        return Missing.INSTANCE;
    }

    static DataNode nullValue() {
        return Val.NULL;
    }

    static DataNode of(Object value) {
        if (value instanceof DataNode n) {
            return n;
        }
        if (value == null) {
            return Val.NULL;
        }
        if (value instanceof Map<?, ?> m) {
            LinkedHashMap<String, DataNode> fields = new LinkedHashMap<>(Math.max(4, m.size() * 2));
            m.forEach((k, v) -> fields.put(String.valueOf(k), of(v)));
            return new Obj(fields);
        }
        if (value instanceof List<?> l) {
            return new Arr(l.stream().map(DataNode::of).toList());
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return new Val(value);
        }
        return new Val(value.toString());
    }

    /** An object node. Field order is preserved: it is the order the source produced. */
    record Obj(Map<String, DataNode> fields) implements DataNode {
        public Obj {
            fields = Collections.unmodifiableMap(fields);
        }

        @Override
        public NodeType type() {
            return NodeType.OBJECT;
        }

        @Override
        public DataNode get(String field) {
            DataNode n = fields.get(field);
            return n == null ? Missing.INSTANCE : n;
        }

        @Override
        public int size() {
            return fields.size();
        }

        @Override
        public Object unwrap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            fields.forEach((k, v) -> m.put(k, v.unwrap()));
            return m;
        }
    }

    /** An array node. */
    record Arr(List<DataNode> elements) implements DataNode {
        public Arr {
            elements = List.copyOf(elements);
        }

        @Override
        public NodeType type() {
            return NodeType.ARRAY;
        }

        @Override
        public DataNode get(int index) {
            int i = index < 0 ? elements.size() + index : index;
            return i >= 0 && i < elements.size() ? elements.get(i) : Missing.INSTANCE;
        }

        @Override
        public int size() {
            return elements.size();
        }

        @Override
        public Object unwrap() {
            return elements.stream().map(DataNode::unwrap).toList();
        }
    }

    /** A scalar: string, number, boolean or null. */
    record Val(Object value) implements DataNode {
        static final Val NULL = new Val(null);
        static final Val TRUE = new Val(Boolean.TRUE);
        static final Val FALSE = new Val(Boolean.FALSE);

        @Override
        public NodeType type() {
            if (value == null) {
                return NodeType.NULL;
            }
            if (value instanceof Number) {
                return NodeType.NUMBER;
            }
            if (value instanceof Boolean) {
                return NodeType.BOOLEAN;
            }
            return NodeType.STRING;
        }

        @Override
        public String asText() {
            if (value == null) {
                return "";
            }
            if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
                return Long.toString(d.longValue());
            }
            if (value instanceof BigDecimal b) {
                return b.toPlainString();
            }
            return value.toString();
        }

        @Override
        public double asDouble() {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            if (value instanceof String s) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    return Double.NaN;
                }
            }
            return Double.NaN;
        }

        @Override
        public boolean asBoolean() {
            return value instanceof Boolean b ? b : value != null && "true".equalsIgnoreCase(value.toString());
        }

        @Override
        public Object unwrap() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Val v && Objects.equals(value, v.value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    /** The absent node: the result of navigating to something that does not exist. */
    final class Missing implements DataNode {
        static final Missing INSTANCE = new Missing();

        private Missing() {}

        @Override
        public NodeType type() {
            return NodeType.MISSING;
        }

        @Override
        public Object unwrap() {
            return null;
        }

        @Override
        public String toString() {
            return "MISSING";
        }
    }
}
