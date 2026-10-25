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
package com.ash.drishti.compute.reactive;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.compute.arrow.ArrowTable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Documents to Arrow tables and back, for the bridge to Python engines (PYTHON_COMPUTE.md section 7.3). Each top-level field of the
 * documents becomes a column: whole numbers {@code int64}, other numbers {@code float64}, booleans {@code bool}, text {@code utf8}, and a
 * list of numbers of one length in every row a fixed-size vector of {@code float64} (a 509-day P&amp;L path). A masked or absent value is a
 * null; a field of mixed types is text; nested objects and ragged lists are left out (they are not columns).
 */
public final class ArrowRows {

    private ArrowRows() {}

    private enum Kind { LONG, DOUBLE, BOOL, TEXT, VECTOR, SKIP }

    /** The documents as one table. */
    public static ArrowTable table(List<DataNode> docs) {
        Set<String> fields = new LinkedHashSet<>();
        for (DataNode d : docs) {
            if (d instanceof DataNode.Obj o) {
                fields.addAll(o.fields().keySet());
            }
        }
        List<ArrowTable.Column> cols = new ArrayList<>();
        int n = docs.size();
        for (String f : fields) {
            Kind k = null;
            int width = -1;
            for (DataNode d : docs) {
                DataNode v = d.get(f);
                if (v.isNull() || v.isMasked()) {
                    continue;
                }
                Kind vk = kindOf(v);
                if (vk == Kind.VECTOR) {
                    width = width < 0 ? v.size() : width == v.size() ? width : -2;
                    if (width == -2) {
                        vk = Kind.SKIP;
                    }
                }
                k = k == null ? vk : merge(k, vk);
            }
            if (k == null || k == Kind.SKIP || k == Kind.VECTOR && width < 1) {
                continue;
            }
            BitSet nulls = new BitSet();
            switch (k) {
                case LONG -> {
                    long[] a = new long[n];
                    for (int i = 0; i < n; i++) {
                        DataNode v = docs.get(i).get(f);
                        if (v.isNull() || v.isMasked()) {
                            nulls.set(i);
                        } else {
                            a[i] = ((Number) v.unwrap()).longValue();
                        }
                    }
                    cols.add(new ArrowTable.Column(f, ArrowTable.Type.INT64, 1, a, nulls));
                }
                case DOUBLE -> {
                    double[] a = new double[n];
                    for (int i = 0; i < n; i++) {
                        DataNode v = docs.get(i).get(f);
                        if (v.isNull() || v.isMasked()) {
                            nulls.set(i);
                        } else {
                            a[i] = v.asDouble();
                        }
                    }
                    cols.add(new ArrowTable.Column(f, ArrowTable.Type.FLOAT64, 1, a, nulls));
                }
                case BOOL -> {
                    boolean[] a = new boolean[n];
                    for (int i = 0; i < n; i++) {
                        DataNode v = docs.get(i).get(f);
                        if (v.isNull() || v.isMasked()) {
                            nulls.set(i);
                        } else {
                            a[i] = v.asBoolean();
                        }
                    }
                    cols.add(new ArrowTable.Column(f, ArrowTable.Type.BOOL, 1, a, nulls));
                }
                case VECTOR -> {
                    double[] a = new double[n * width];
                    for (int i = 0; i < n; i++) {
                        DataNode v = docs.get(i).get(f);
                        if (v.isNull() || v.isMasked() || v.size() != width) {
                            nulls.set(i);
                            continue;
                        }
                        for (int j = 0; j < width; j++) {
                            a[i * width + j] = v.get(j).asDouble();
                        }
                    }
                    cols.add(new ArrowTable.Column(f, ArrowTable.Type.FLOAT64_VECTOR, width, a, nulls));
                }
                default -> {
                    String[] a = new String[n];
                    for (int i = 0; i < n; i++) {
                        DataNode v = docs.get(i).get(f);
                        if (v.isNull() || v.isMasked()) {
                            nulls.set(i);
                        } else {
                            a[i] = v.asText();
                        }
                    }
                    cols.add(new ArrowTable.Column(f, ArrowTable.Type.UTF8, 1, a, nulls));
                }
            }
        }
        if (cols.isEmpty() && n > 0) {
            cols.add(new ArrowTable.Column("ROW", ArrowTable.Type.INT64, 1, new long[n], null));
        }
        return new ArrowTable(cols);
    }

    private static Kind kindOf(DataNode v) {
        if (v instanceof DataNode.Arr a) {
            for (DataNode e : a.elements()) {
                if (!(e.unwrap() instanceof Number)) {
                    return Kind.SKIP;
                }
            }
            return a.size() == 0 ? Kind.SKIP : Kind.VECTOR;
        }
        Object u = v.unwrap();
        if (u instanceof Boolean) {
            return Kind.BOOL;
        }
        if (u instanceof Integer || u instanceof Long || u instanceof Short || u instanceof java.math.BigInteger) {
            return Kind.LONG;
        }
        if (u instanceof Number) {
            return Kind.DOUBLE;
        }
        if (u instanceof String) {
            return Kind.TEXT;
        }
        return Kind.SKIP;
    }

    private static Kind merge(Kind a, Kind b) {
        if (a == b) {
            return a;
        }
        if (a == Kind.SKIP || b == Kind.SKIP || a == Kind.VECTOR || b == Kind.VECTOR) {
            return Kind.SKIP;
        }
        if ((a == Kind.LONG || a == Kind.DOUBLE) && (b == Kind.LONG || b == Kind.DOUBLE)) {
            return Kind.DOUBLE;
        }
        return Kind.TEXT;
    }

    /** A table as rows of JSON values: vectors as lists of numbers. */
    public static List<Map<String, Object>> rows(ArrowTable t) {
        List<Map<String, Object>> out = new ArrayList<>(t.rows());
        for (Map<String, Object> r : t.toRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k, plain(v)));
            out.add(m);
        }
        return out;
    }

    /** A value as JSON: arrays of doubles as lists; non-finite numbers as null. */
    static Object plain(Object v) {
        if (v instanceof double[] a) {
            List<Object> l = new ArrayList<>(a.length);
            for (double x : a) {
                l.add(Double.isFinite(x) ? x : null);
            }
            return l;
        }
        if (v instanceof Double d && !Double.isFinite(d)) {
            return null;
        }
        return v;
    }
}
