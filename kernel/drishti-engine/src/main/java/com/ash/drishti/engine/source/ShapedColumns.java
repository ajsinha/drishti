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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.SourcePlugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A shaped connector's columnar read (the grid's pages, BI): the canonical columns asked for are read by their stored names, with the
 * columns its {@code where} tests; the rows it leaves out (and the rows of a system switched off) are dropped; the columns are renamed to
 * their canonical names and the constants of {@code set} filled in where a row lacks them.
 */
final class ShapedColumns {

    private ShapedColumns() {
    }

    static Optional<ColumnSet> read(SourcePlugin delegate, SourceShape shape, SourceSystems systems, String kind, Collection<String> paths, AsOf asOf)
            throws Exception {
        Set<String> stored = delegate.columnar(kind);
        boolean anyOff = systems != null && !systems.off().isEmpty();
        Map<String, String> nativeOf = new LinkedHashMap<>();
        Set<String> ask = new LinkedHashSet<>();
        for (String p : paths) {
            String n = shape.nativeName(p);
            nativeOf.put(p, n);
            if (stored.isEmpty() || stored.contains(n)) {
                ask.add(n);
            }
        }
        Set<String> tested = new LinkedHashSet<>(shape.where().fields());
        if (anyOff) {
            tested.add(shape.nativeName(SourceShape.SOURCE_SYSTEM));
        }
        for (String f : tested) {
            if (stored.isEmpty() || stored.contains(f)) {
                ask.add(f);
            } else if (shape.where().fields().contains(f)) {
                return Optional.empty();                      // a where on a field not kept as a column: the documents answer
            }
        }
        Optional<ColumnSet> read = delegate.columns(kind, new ArrayList<>(ask), asOf);
        if (read.isEmpty()) {
            return read;
        }
        ColumnSet cs = read.get();
        List<Integer> keep = new ArrayList<>();
        String sysNative = shape.nativeName(SourceShape.SOURCE_SYSTEM);
        Object sysConstant = shape.set().get(SourceShape.SOURCE_SYSTEM);
        for (int i = 0; i < cs.size(); i++) {
            if (!shape.where().all()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String f : shape.where().fields()) {
                    put(row, f, cs.value(f, i));
                }
                if (!shape.where().test(DataNode.of(row))) {
                    continue;
                }
            }
            if (anyOff) {
                Object s = cs.has(sysNative) ? cs.value(sysNative, i) : null;
                if (s == null || String.valueOf(s).isBlank()) {
                    s = sysConstant;
                }
                if (s != null && systems.off(String.valueOf(s))) {
                    continue;
                }
            }
            keep.add(i);
        }
        String[] ids = new String[keep.size()];
        for (int j = 0; j < ids.length; j++) {
            ids[j] = cs.ids()[keep.get(j)];
        }
        Map<String, double[]> numbers = new LinkedHashMap<>();
        Map<String, String[]> texts = new LinkedHashMap<>();
        Map<String, Object[]> mixed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : nativeOf.entrySet()) {
            String canonical = e.getKey();
            String n = e.getValue();
            Object always = shape.fixed().get(canonical);
            Object constant = always != null ? always : shape.set().get(canonical);
            SourceShape.Target target = shape.map().get(n);
            java.util.Map<String, Object> values = target == null ? java.util.Map.of() : target.values();
            if (cs.numbers().containsKey(n) && constant == null && values.isEmpty()) {
                double[] src = cs.numbers().get(n);
                double[] out = new double[ids.length];
                for (int j = 0; j < out.length; j++) {
                    out[j] = src[keep.get(j)];
                }
                numbers.put(canonical, out);
            } else if (cs.has(n) || constant != null) {
                boolean text = constant == null ? cs.texts().containsKey(n) : constant instanceof String;
                Object[] vals = new Object[ids.length];
                for (int j = 0; j < vals.length; j++) {
                    Object v = always != null || !cs.has(n) ? null : cs.value(n, keep.get(j));
                    if (v != null && values.containsKey(String.valueOf(v))) {
                        v = values.get(String.valueOf(v));
                    }
                    vals[j] = v == null || v instanceof String s && s.isBlank() ? constant == null ? v : constant : v;
                }
                if (text && java.util.Arrays.stream(vals).allMatch(v -> v == null || v instanceof String)) {
                    String[] t = new String[vals.length];
                    for (int j = 0; j < t.length; j++) {
                        t[j] = vals[j] == null ? null : (String) vals[j];
                    }
                    texts.put(canonical, t);
                } else {
                    mixed.put(canonical, vals);
                }
            }
        }
        return Optional.of(new ColumnSet(ids, numbers, texts, cs.businessDate(), cs.incomplete(), mixed));
    }

    /** {@code A.B} as {@code {A: {B: v}}}, so the predicate reads the path. */
    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> row, String path, Object v) {
        String[] parts = path.split("\\.");
        Map<String, Object> at = row;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = at.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
            if (!(next instanceof Map<?, ?>)) {
                return;
            }
            at = (Map<String, Object>) next;
        }
        at.put(parts[parts.length - 1], v);
    }
}
