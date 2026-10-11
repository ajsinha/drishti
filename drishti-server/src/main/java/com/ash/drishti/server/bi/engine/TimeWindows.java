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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.common.BusinessCalendar;
import java.time.LocalDate;
import java.time.Month;

/**
 * The date windows of the time-intelligence functions (RUPAKA.md section 6): {@code ytd}, {@code mtd}, {@code qtd}, {@code prior_period} and
 * {@code same_period_last_year}. A window is worked out once per query, in Java, from the query's <em>as-of date</em> (the end of its dates, or the
 * latest business date) and the pack's business-day calendar; the SQL only compares the date column with two bound dates.
 *
 * <p>Business-day aware where it matters: the prior period counts business days (a window of five business days is followed by the five business days
 * before it, holidays skipped), and a same-period-last-year window whose ends fall on a weekend or a holiday last year is pulled in to the business days
 * inside it.
 */
public final class TimeWindows {

    /** What the windows are measured from: the as-of date, the start of the query's dates (or null) and the calendar. */
    public record Context(LocalDate asOf, LocalDate from, BusinessCalendar calendar) {

        /** A context for tests and callers without a business date: today, weekends only. */
        public static Context weekends(LocalDate asOf) {
            return new Context(asOf, null, BusinessCalendar.of("WEEKENDS"));
        }
    }

    /** An inclusive window of dates. */
    public record Window(LocalDate lo, LocalDate hi) {

        public boolean contains(LocalDate d) {
            return !d.isBefore(lo) && !d.isAfter(hi);
        }
    }

    private TimeWindows() {}

    /** The window of a time function at a context; {@code function} is one of {@code Node.TIME}. */
    public static Window of(String function, Context ctx) {
        LocalDate end = ctx.asOf();
        return switch (function) {
            case "ytd" -> new Window(LocalDate.of(end.getYear(), Month.JANUARY, 1), end);
            case "mtd" -> new Window(end.withDayOfMonth(1), end);
            case "qtd" -> new Window(LocalDate.of(end.getYear(), ((end.getMonthValue() - 1) / 3) * 3 + 1, 1), end);
            case "prior_period" -> prior(ctx);
            case "same_period_last_year" -> lastYear(ctx);
            default -> throw new IllegalArgumentException("not a time function: " + function);
        };
    }

    /** The current window of {@code prior_period} and {@code same_period_last_year}: the query's dates, or the month to date when it names none. */
    public static Window current(Context ctx) {
        LocalDate start = ctx.from() != null && !ctx.from().isAfter(ctx.asOf()) ? ctx.from() : ctx.asOf().withDayOfMonth(1);
        return new Window(start, ctx.asOf());
    }

    private static Window prior(Context ctx) {
        Window cur = current(ctx);
        BusinessCalendar cal = ctx.calendar();
        int days = 0;
        for (LocalDate d = cur.lo(); !d.isAfter(cur.hi()); d = d.plusDays(1)) {
            if (cal.isBusinessDay(d)) {
                days++;
            }
        }
        LocalDate hi = cal.previous(cur.lo());
        LocalDate lo = hi;
        for (int i = 1; i < Math.max(1, days); i++) {
            lo = cal.previous(lo);
        }
        return new Window(lo, hi);
    }

    private static Window lastYear(Context ctx) {
        Window cur = current(ctx);
        BusinessCalendar cal = ctx.calendar();
        LocalDate lo = cur.lo().minusYears(1);
        LocalDate hi = cur.hi().minusYears(1);
        return new Window(cal.isBusinessDay(lo) ? lo : cal.next(lo), cal.onOrBefore(hi));
    }
}
