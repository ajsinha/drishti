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
package com.ash.drishti.banking.compute.var;

import com.ash.drishti.compute.vector.MappedMatrix;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A date's aggregates read from its hot file's {@code AGGS} section, mapped (VAR_GUIDE.md section 26.3): every book's, cell's and node's
 * float64 vector stays in the file's pages, not on the heap (642 MB a date at 1,000,000 positions); the heap keeps the key to row index
 * only. {@link #get} returns a fresh copy of the row, the same float64 bits as written (the planner adds them as before, so every answer is
 * unchanged to the bit). Immutable; read after the date's unmapping, a row throws {@link MappedMatrix.Closed} (ask again), as the rows do.
 */
final class MappedAggregates extends AbstractMap<String, double[]> {

    private final MappedMatrix rows;
    private final Map<String, Integer> index;
    private final List<String> keys;

    MappedAggregates(MappedMatrix rows, List<String> keys) {
        this.rows = rows;
        this.keys = List.copyOf(keys);
        Map<String, Integer> ix = new HashMap<>(keys.size() * 2);
        for (int i = 0; i < keys.size(); i++) {
            ix.put(keys.get(i), i);
        }
        this.index = Collections.unmodifiableMap(ix);
    }

    MappedMatrix matrix() {
        return rows;
    }

    /** The heap the index takes (the keys are shared with the book's runs and nodes): about 64 bytes a key. */
    long heapBytes() {
        return 64L * keys.size();
    }

    @Override
    public double[] get(Object key) {
        Integer r = index.get(key);
        if (r == null) {
            return null;
        }
        double[] v = new double[rows.width()];
        rows.read(r, v);
        return v;
    }

    @Override
    public boolean containsKey(Object key) {
        return index.containsKey(key);
    }

    @Override
    public int size() {
        return keys.size();
    }

    @Override
    public Set<String> keySet() {
        return index.keySet();
    }

    @Override
    public Set<Entry<String, double[]>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<String, double[]>> iterator() {
                Iterator<String> it = keys.iterator();
                return new Iterator<>() {
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    public Entry<String, double[]> next() {
                        String k = it.next();
                        return new SimpleImmutableEntry<>(k, get(k));
                    }
                };
            }

            @Override
            public int size() {
                return keys.size();
            }
        };
    }

    /** The morning's aggregates with an intraday layer's changed ones over them (the rest read from the mapping, never copied). */
    static Map<String, double[]> overlay(Map<String, double[]> base, Map<String, double[]> changed) {
        if (!(base instanceof MappedAggregates)) {
            Map<String, double[]> m = new HashMap<>(base);
            m.putAll(changed);
            return m;
        }
        return new Overlay(base, changed);
    }

    /** An intraday layer's aggregates: its own changed ones on the heap, the morning's read from their mapping. */
    static final class Overlay extends AbstractMap<String, double[]> {
        private final Map<String, double[]> base;
        private final Map<String, double[]> own;

        Overlay(Map<String, double[]> base, Map<String, double[]> changed) {
            this.base = base;
            this.own = new HashMap<>(changed);
        }

        /** The heap the layer's own aggregates take. */
        long heapBytes(int width) {
            return (long) own.size() * (96 + 8L * width);
        }

        @Override
        public double[] get(Object key) {
            double[] v = own.get(key);
            return v != null ? v : base.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return own.containsKey(key) || base.containsKey(key);
        }

        @Override
        public Set<Entry<String, double[]>> entrySet() {
            Map<String, double[]> all = new HashMap<>(base);              // a full copy only when someone walks every entry
            all.putAll(own);
            return all.entrySet();
        }

        @Override
        public int size() {
            int n = base.size();
            for (String k : own.keySet()) {
                n += base.containsKey(k) ? 0 : 1;
            }
            return n;
        }
    }
}
