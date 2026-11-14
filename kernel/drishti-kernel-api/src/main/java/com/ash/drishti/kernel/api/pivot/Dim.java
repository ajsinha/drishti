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
package com.ash.drishti.kernel.api.pivot;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A dimension of a view (ARCHITECTURE.md section 26.2): a field, and optionally how it is grouped (a date by day, week, month, quarter or
 * year; a number into buckets of a width). Written {@code TRADE_DATE:month} or {@code NOTIONAL_USD:width=1000000}; its id is that text.
 *
 * @param field the source's column
 * @param group null, {@code day}, {@code week}, {@code month}, {@code quarter}, {@code year} or {@code width=<n>}
 */
public record Dim(String field, String group) {

    /** The groupings a date or timestamp offers. */
    public static final Set<String> DATE_GROUPS = Set.of("day", "week", "month", "quarter", "year");

    /**
     * A list attribute shown as one joined label ({@code TAGS:join}: "fx, rates") instead of exploded (each value its own member, a row
     * counted under each of its values: PIVOT_GUIDE.md, list fields).
     */
    public static final String JOIN = "join";

    public Dim {
        if (field == null || field.isBlank()) {
            throw PivotView.bad("a dimension needs a field");
        }
        field = field.strip();
        group = group == null || group.isBlank() ? null : group.strip().toLowerCase(Locale.ROOT);
        if (group != null && !DATE_GROUPS.contains(group) && !JOIN.equals(group) && !group.matches("width=[0-9]+(\\.[0-9]+)?")) {
            throw PivotView.bad(field + ":" + group + ": a dimension groups a date by day, week, month, quarter or year, a number by "
                    + "width=<n>, or shows a list as one label by join");
        }
    }

    /** The id a view, a cell key and a cell reference name it by. */
    public String id() {
        return group == null ? field : field + ":" + group;
    }

    /** The width of a number grouping, or 0. */
    /** Whether a list attribute is shown as one joined label rather than exploded. */
    public boolean joined() {
        return JOIN.equals(group);
    }

    public double width() {
        return group != null && group.startsWith("width=") ? Double.parseDouble(group.substring(6)) : 0;
    }

    /** A dimension from its text ({@code FIELD} or {@code FIELD:group}) or a map ({@code {field, group}}). */
    public static Dim parse(Object o) {
        if (o instanceof Dim d) {
            return d;
        }
        if (o instanceof Map<?, ?> m) {
            Object f = m.get("field");
            Object g = m.get("group");
            return new Dim(f == null ? null : String.valueOf(f), g == null ? null : String.valueOf(g));
        }
        String s = o == null ? "" : String.valueOf(o).strip();
        int i = s.indexOf(':');
        return i < 0 ? new Dim(s, null) : new Dim(s.substring(0, i), s.substring(i + 1));
    }

    @Override
    public String toString() {
        return id();
    }
}
