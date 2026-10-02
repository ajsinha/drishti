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

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Some fields of every entity of a kind on one business date, column by column: what a source stores as columns
 * beside its documents (a Delta table's promoted columns), for searches and aggregates over millions of entities
 * without reading a document. Row {@code i} is the entity {@code ids[i]}.
 *
 * <p>A path is in one of the three maps. A field that holds numbers in some entities and text in others (a JSON-lines
 * file with {@code "mtm": "N/A"} on one line) is in {@code mixed}, each value as the document holds it, so a search
 * answered from columns orders and compares it exactly as one answered from documents (QA 2026-10-01, DATA-07).
 *
 * @param ids the entities' ids
 * @param numbers document path to its values; {@code NaN} where the entity has none
 * @param texts document path to its values; {@code null} where the entity has none
 * @param businessDate the business date the values are for, or null for undated data
 * @param incomplete null when the set holds every entity the source holds for the day; else why it may not, in words
 *     (a JSON-lines day with unreadable lines: "2 unreadable lines in trading/2026-09-30/trade.jsonl"), for an answer
 *     built from it to say partial
 * @param mixed document path to its values where they are numbers for some entities and text for others: a
 *     {@link Double}, a {@link String} or {@code null} per entity
 */
public record ColumnSet(String[] ids, Map<String, double[]> numbers, Map<String, String[]> texts, LocalDate businessDate, String incomplete,
        Map<String, Object[]> mixed) {

    public ColumnSet {
        mixed = mixed == null ? Map.of() : mixed;
    }

    /** A complete set. */
    public ColumnSet(String[] ids, Map<String, double[]> numbers, Map<String, String[]> texts, LocalDate businessDate) {
        this(ids, numbers, texts, businessDate, null, Map.of());
    }

    /** A set with no field of mixed types. */
    public ColumnSet(String[] ids, Map<String, double[]> numbers, Map<String, String[]> texts, LocalDate businessDate, String incomplete) {
        this(ids, numbers, texts, businessDate, incomplete, Map.of());
    }

    public int size() {
        return ids.length;
    }

    public boolean has(String path) {
        return numbers.containsKey(path) || texts.containsKey(path) || mixed.containsKey(path);
    }

    /** The value at a path for row {@code i}: a Double, a String, or null. */
    public Object value(String path, int i) {
        double[] n = numbers.get(path);
        if (n != null) {
            return Double.isNaN(n[i]) ? null : n[i];
        }
        String[] t = texts.get(path);
        if (t != null) {
            return t[i];
        }
        Object[] m = mixed.get(path);
        return m == null ? null : m[i];
    }

    /**
     * The numbers at a path, {@code NaN} where an entity has none or has text there (as an aggregate over documents
     * skips text); null when the path holds only text or is not in the set.
     */
    public double[] numeric(String path) {
        double[] n = numbers.get(path);
        Object[] m = mixed.get(path);
        if (n != null || m == null) {
            return n;
        }
        double[] out = new double[m.length];
        for (int i = 0; i < m.length; i++) {
            out[i] = m[i] instanceof Double d ? d : Double.NaN;
        }
        return out;
    }

    /** The set with only these paths (those it has), and the same ids, date and completeness. */
    public ColumnSet select(Collection<String> paths) {
        Map<String, double[]> n = new LinkedHashMap<>();
        Map<String, String[]> t = new LinkedHashMap<>();
        Map<String, Object[]> m = new LinkedHashMap<>();
        for (String p : paths) {
            if (numbers.containsKey(p)) {
                n.put(p, numbers.get(p));
            } else if (texts.containsKey(p)) {
                t.put(p, texts.get(p));
            } else if (mixed.containsKey(p)) {
                m.put(p, mixed.get(p));
            }
        }
        return new ColumnSet(ids, n, t, businessDate, incomplete, m);
    }
}
