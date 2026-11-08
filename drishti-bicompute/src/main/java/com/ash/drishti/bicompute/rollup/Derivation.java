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
package com.ash.drishti.bicompute.rollup;

import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.Dim;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

/**
 * How a dimension of a view is found from a rollup's grain (aggregate awareness, ARCHITECTURE.md section 26.6): the same dimension, or a
 * coarser grouping of a date the grain holds finer (a date or its day gives week, month, quarter and year; a month gives quarter and year; a
 * quarter gives year). The values are written exactly as the SQL compiler writes them ({@code 2026-10}, {@code 2026-Q4}, {@code 2026}, the
 * Monday of the week), and a value that is not a date is the blank, as SQL makes it.
 */
final class Derivation {

    /** How to find one dimension: the grain dimension read and how it is coarsened. */
    record Rule(String from, String to) {}

    private Derivation() {}

    /**
     * The rule for {@code dim} from a grain (dimension ids), or null when the grain cannot give it. {@code dateFields}: the source's fields
     * of type date (a raw date dimension's values are days).
     */
    static Rule rule(String dim, List<String> grain, java.util.Set<String> dateFields) {
        if (grain.contains(dim)) {
            return new Rule(dim, null);
        }
        Dim d = Dim.parse(dim);
        if (d.group() == null || !Dim.DATE_GROUPS.contains(d.group())) {
            return null;
        }
        for (String g : grain) {
            Dim gd = Dim.parse(g);
            if (!gd.field().equals(d.field())) {
                continue;
            }
            String from = gd.group() == null ? (dateFields.contains(gd.field()) ? "day" : null) : gd.group();
            if (from != null && finer(from, d.group())) {
                return new Rule(g, from + ">" + d.group());
            }
        }
        return null;
    }

    private static boolean finer(String from, String to) {
        return switch (from) {
            case "day" -> List.of("week", "month", "quarter", "year", "day").contains(to);
            case "month" -> List.of("quarter", "year").contains(to);
            case "quarter" -> "year".equals(to);
            default -> false;
        };
    }

    /** The dimension's value in a grain key by its rule. */
    static String value(Rule r, CellKey key) {
        String v = key.coords().get(r.from());
        if (v == null) {
            return null;
        }
        if (r.to() == null || CellKey.BLANK.equals(v) || CellKey.OTHERS.equals(v)) {
            return v;
        }
        String[] ft = r.to().split(">");
        try {
            return switch (ft[0]) {
                case "day" -> fromDay(LocalDate.parse(v.length() >= 10 ? v.substring(0, 10) : v), ft[1]);
                case "month" -> {
                    int y = Integer.parseInt(v.substring(0, 4));
                    int m = Integer.parseInt(v.substring(5, 7));
                    yield "quarter".equals(ft[1]) ? y + "-Q" + ((m - 1) / 3 + 1) : Integer.toString(y);
                }
                case "quarter" -> v.substring(0, 4);
                default -> CellKey.BLANK;
            };
        } catch (RuntimeException e) {
            return CellKey.BLANK;
        }
    }

    private static String fromDay(LocalDate d, String to) {
        return switch (to) {
            case "day" -> d.toString();
            case "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            case "month" -> String.format("%04d-%02d", d.getYear(), d.getMonthValue());
            case "quarter" -> d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1);
            default -> Integer.toString(d.getYear());
        };
    }

    /** True when every rule finds the key's coordinate values in {@code values} (a filter's or a restriction's). */
    static boolean all(Map<String, Rule> rules) {
        return rules.values().stream().allMatch(java.util.Objects::nonNull);
    }
}
