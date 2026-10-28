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

import java.util.ArrayList;
import java.util.List;

/**
 * A range of a composite key over promoted text columns (a VaR shard: legal entity, then desk; VAR_SHARDING.md), for a vector read
 * that needs only the rows inside it. {@code from} is included, {@code to} excluded; either empty means the start or the end of the key
 * space. Keys compare column by column as text, a missing value as empty text (the order the VaR engine sorts trades in).
 *
 * <p>A source may use it to skip whole files whose statistics show no key inside (the first column's minimum and maximum); it never has
 * to: the reader checks every row again with {@link #contains}.
 *
 * @param columns the key's columns, in order
 * @param from the first key inside, or empty
 * @param to the first key after it, or empty
 */
public record KeyRange(List<String> columns, List<String> from, List<String> to) {

    public KeyRange {
        columns = List.copyOf(columns);
        from = clean(from);
        to = clean(to);
    }

    private static List<String> clean(List<String> key) {
        List<String> out = new ArrayList<>();
        if (key != null) {
            for (String v : key) {
                out.add(v == null ? "" : v);
            }
        }
        return java.util.Collections.unmodifiableList(out);
    }

    /** Compares two keys column by column, a missing value as empty. */
    public static int compare(List<String> a, List<String> b) {
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            String x = i < a.size() && a.get(i) != null ? a.get(i) : "";
            String y = i < b.size() && b.get(i) != null ? b.get(i) : "";
            int c = x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** True when the key (its values in {@link #columns} order) is inside the range. */
    public boolean contains(List<String> key) {
        return (from.isEmpty() || compare(key, from) >= 0) && (to.isEmpty() || compare(key, to) < 0);
    }

    /**
     * Whether a file may hold a key of the range, from its first key column's statistics: the smallest and largest value (null: not known)
     * and whether it may hold rows without a value (they sort first). False only when the statistics prove every row outside.
     */
    public boolean mayHold(String min, String max, boolean mayHaveNulls) {
        if (columns.isEmpty()) {
            return true;
        }
        if (!from.isEmpty() && max != null && max.compareTo(from.get(0)) < 0) {
            return false;                                              // every row before the range (rows without a value too)
        }
        String low = mayHaveNulls || min == null ? "" : min;
        return to.isEmpty() || low.compareTo(to.get(0)) <= 0;        // a row at the range's last first-column value may still be inside
    }
}
