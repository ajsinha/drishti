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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.common.BusinessCalendar;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A report's {@code as-of} setting (2.6.7 patch; docs/guides/BI_GUIDE.md, "Which date a report reads"): which business date each table of a visual
 * reads when the reader asks for one date (a day in the time picker, or Now).
 *
 * <ul>
 *   <li>{@code per-table} (the default): each table at its own latest date on or before the asked one; a fact and a dimension may differ, and the
 *       answer says so ({@code meta.dataAsOf}).</li>
 *   <li>{@code aligned}: every table at the newest date on or before the asked one that all of them hold (a reconciliation).</li>
 *   <li>a date {@code yyyy-mm-dd}: always that date (a month-end pack).</li>
 *   <li>a relative name ({@link RelativeDates}: {@code previous-month}, {@code last-5-business-days}, ..., and {@code last-business-day}): the end of its
 *       window at the asked date ({@code previous-month}: the last day of the month before).</li>
 * </ul>
 * A window the reader picks (two dates) is read as it is.
 */
public final class AsOfRule {

    public static final String PER_TABLE = "per-table";
    public static final String ALIGNED = "aligned";
    /** The one name this rule adds to {@link RelativeDates}: the last business day on or before the asked date. */
    public static final String LAST_BUSINESS_DAY = "last-business-day";

    private AsOfRule() {}

    /** The rule as written ({@code per-table} when absent); an unknown one is DRS-9030 naming the forms. */
    static String parse(JsonNode n, List<ReportParser.Problem> out) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return PER_TABLE;
        }
        String v = n.asText("").trim();
        if (!n.isValueNode() || !valid(v)) {
            out.add(ReportParser.error(ReportParser.INVALID, "as-of", "as-of is per-table (the default: each table at its latest date on or before the asked one), aligned (every"
                    + " table at the newest date all of them hold), a date yyyy-mm-dd, or a relative name (last-business-day, previous-month, last-5-business-days, this-quarter, ...),"
                    + " not '" + n.asText() + "'"));
            return PER_TABLE;
        }
        return v;
    }

    /** True when the rule is one of the forms above. */
    public static boolean valid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        if (PER_TABLE.equals(v) || ALIGNED.equals(v) || LAST_BUSINESS_DAY.equals(v)) {
            return true;
        }
        if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                LocalDate.parse(v);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return !"all".equals(v) && RelativeDates.valid(v);
    }

    /**
     * The reader's state with the report's rule worked out: one date asked (or none: the current business date) becomes the date the rule means. A
     * window, and the default rule, leave the state as it is.
     *
     * @param aligned the newest date on or before the given one that all the visual's tables hold, or null when they cannot say
     */
    public static ReportState apply(Report report, ReportState state, Supplier<LocalDate> current, BusinessCalendar calendar, Function<LocalDate, LocalDate> aligned) {
        String rule = report.asOf() == null ? PER_TABLE : report.asOf();
        if (PER_TABLE.equals(rule)) {
            return state;
        }
        String from = state.from() != null ? state.from() : report.dates() == null ? null : report.dates().from();
        String to = state.to() != null ? state.to() : report.dates() == null ? null : report.dates().to();
        if (from != null && to != null && !from.equals(to)) {
            return state;                                                       // a window: read as it is
        }
        LocalDate asked;
        try {
            asked = to != null ? LocalDate.parse(to) : from != null ? LocalDate.parse(from) : current.get();
        } catch (RuntimeException e) {
            return state;                                                       // not a date: the visual query says so
        }
        LocalDate d = resolve(rule, asked, calendar, aligned);
        return d == null ? state : state.withDates(d.toString(), d.toString());
    }

    /** The date a rule means at the asked date; null when it means the asked date itself (or the sources cannot say). */
    static LocalDate resolve(String rule, LocalDate asked, BusinessCalendar calendar, Function<LocalDate, LocalDate> aligned) {
        if (asked == null || PER_TABLE.equals(rule)) {
            return null;
        }
        if (ALIGNED.equals(rule)) {
            return aligned == null ? null : aligned.apply(asked);
        }
        if (rule.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(rule);
        }
        RelativeDates.Window w = RelativeDates.resolve(LAST_BUSINESS_DAY.equals(rule) ? "last-1-business-days" : rule, asked, calendar);
        return w == null ? null : w.to();
    }
}
