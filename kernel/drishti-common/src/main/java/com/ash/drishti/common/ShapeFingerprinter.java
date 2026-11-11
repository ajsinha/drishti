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
package com.ash.drishti.common;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes the shape signature of a document and its {@link Fingerprint}. The signature is canonical:
 * object keys are sorted, and an array's shape is the union of its elements' shapes, so neither values
 * nor array lengths change it. Stateless and thread-safe.
 *
 * <p>Signature grammar: {@code o{key:shape,...}}, {@code a[shape|shape]}, or a scalar code
 * ({@code s n b z}).
 */
public final class ShapeFingerprinter {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public Fingerprint fingerprint(DataNode node) {
        return new Fingerprint(fnv(signature(node)));
    }

    public String signature(DataNode node) {
        StringBuilder sb = new StringBuilder(256);
        append(shapeOf(node), sb);
        return sb.toString();
    }

    /** A structural summary: a scalar code, an object of shapes, or an array of distinct element shapes. */
    private Object shapeOf(DataNode node) {
        return switch (node) {
            case DataNode.Obj o -> {
                TreeMap<String, Object> m = new TreeMap<>();
                o.fields().forEach((k, v) -> m.put(k, shapeOf(v)));
                yield m;
            }
            case DataNode.Arr a -> {
                TreeMap<String, Object> distinct = new TreeMap<>();
                TreeMap<String, Object> mergedObject = null;
                for (DataNode e : a.elements()) {
                    Object s = shapeOf(e);
                    if (s instanceof TreeMap<?, ?> tm) {
                        mergedObject = merge(mergedObject, tm);
                    } else {
                        distinct.put(render(s), s);
                    }
                }
                List<Object> parts = new ArrayList<>(distinct.values());
                if (mergedObject != null) {
                    parts.add(mergedObject);
                }
                yield Collections.unmodifiableList(parts);
            }
            case DataNode.Val v -> v.type() == NodeType.NULL ? "z" : String.valueOf(v.type().code());
            case DataNode.Missing m -> "m";
        };
    }

    @SuppressWarnings("unchecked")
    private TreeMap<String, Object> merge(TreeMap<String, Object> acc, TreeMap<?, ?> next) {
        TreeMap<String, Object> out = acc == null ? new TreeMap<>() : acc;
        for (Map.Entry<?, ?> e : next.entrySet()) {
            String k = (String) e.getKey();
            Object prev = out.get(k);
            if (prev == null || "z".equals(prev)) {
                out.put(k, e.getValue());
            } else if (prev instanceof TreeMap<?, ?> p && e.getValue() instanceof TreeMap<?, ?> n) {
                out.put(k, merge((TreeMap<String, Object>) p, n));
            }
        }
        return out;
    }

    private void append(Object shape, StringBuilder sb) {
        if (shape instanceof Map<?, ?> m) {
            sb.append("o{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append(':');
                append(e.getValue(), sb);
            }
            sb.append('}');
        } else if (shape instanceof List<?> l) {
            sb.append("a[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append('|');
                }
                append(l.get(i), sb);
            }
            sb.append(']');
        } else {
            sb.append(shape);
        }
    }

    private String render(Object shape) {
        StringBuilder sb = new StringBuilder();
        append(shape, sb);
        return sb.toString();
    }

    static long fnv(String s) {
        long h = FNV_OFFSET;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        return h;
    }
}
