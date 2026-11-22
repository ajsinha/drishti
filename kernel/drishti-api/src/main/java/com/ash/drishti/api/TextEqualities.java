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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The part of a connector's {@code where} a store can apply at the source (docs/guides/DATA_LAKE_GUIDE.md, "Several source systems"): its
 * comparisons of a column with text constants ({@code =} and {@code in}), by the store's column, the values of several comparisons of one
 * column intersected. A row these leave out the whole predicate leaves out too; the reader still tests every row. The one rule for every
 * store that pushes a {@code where} down (the lakes' file pruning, a database's {@code WHERE}).
 */
public final class TextEqualities {

    private TextEqualities() {
    }

    /**
     * Column to the text values a row may hold there, from the filters whose path {@code column} maps to one of the store's text columns
     * (null: not one the store can test); empty when none is usable.
     */
    public static Map<String, Set<String>> of(List<ScanRequest.Filter> where, Function<String, String> column) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (where == null) {
            return out;
        }
        for (ScanRequest.Filter f : where) {
            String c = column.apply(f.path());
            if (c == null || (f.op() != ScanRequest.Op.EQ && f.op() != ScanRequest.Op.IN) || f.values().isEmpty()
                    || !f.values().stream().allMatch(v -> v instanceof String)) {
                continue;
            }
            Set<String> values = new HashSet<>();
            f.values().forEach(v -> values.add((String) v));
            out.merge(c, values, (a, b) -> {
                Set<String> both = new HashSet<>(a);
                both.retainAll(b);
                return both;
            });
        }
        return out;
    }
}
