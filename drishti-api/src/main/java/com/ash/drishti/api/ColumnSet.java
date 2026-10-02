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
import java.util.Map;

/**
 * Some fields of every entity of a kind on one business date, column by column: what a source stores as columns
 * beside its documents (a Delta table's promoted columns), for searches and aggregates over millions of entities
 * without reading a document. Row {@code i} is the entity {@code ids[i]}.
 *
 * @param ids the entities' ids
 * @param numbers document path to its values; {@code NaN} where the entity has none
 * @param texts document path to its values; {@code null} where the entity has none
 * @param businessDate the business date the values are for, or null for undated data
 * @param incomplete null when the day is complete; else why some entities are missing (e.g. "1 unreadable line in
 *     2026-09-01/trade.jsonl"), so a search or aggregate over it is reported partial, with that reason
 */
public record ColumnSet(String[] ids, Map<String, double[]> numbers, Map<String, String[]> texts, LocalDate businessDate, String incomplete) {

    /** A complete day's columns. */
    public ColumnSet(String[] ids, Map<String, double[]> numbers, Map<String, String[]> texts, LocalDate businessDate) {
        this(ids, numbers, texts, businessDate, null);
    }

    public int size() {
        return ids.length;
    }

    public boolean has(String path) {
        return numbers.containsKey(path) || texts.containsKey(path);
    }

    /** The value at a path for row {@code i}: a Double, a String, or null. */
    public Object value(String path, int i) {
        double[] n = numbers.get(path);
        if (n != null) {
            return Double.isNaN(n[i]) ? null : n[i];
        }
        String[] t = texts.get(path);
        return t == null ? null : t[i];
    }
}
