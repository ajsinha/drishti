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
package com.ash.drishti.bicompute.context;

import com.ash.drishti.common.BusinessCalendar;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The dates of the balance and time-travel functions (HOW_IT_FITS.md §A28 3.3.4; HOW_IT_FITS.md §A28 2.5 rows 48 to 51 and 56), worked out
 * before a query runs from its as-of date and the model's calendar, so each plan is bound to its dates: {@code closing} restricts to
 * the last business day of each period up to the as-of date, {@code opening} is the closing of the period before, {@code latest} and
 * {@code earliest} the latest and earliest date of each group.
 */
public final class Periods {

    /** The periods of {@code opening} and {@code closing}. */
    public static final Set<String> PERIODS = Set.of("day", "week", "month", "quarter", "year");
    /** How far back {@code closing} looks for period ends when the query has no window (its rows can only be in what was read). */
    public static final int CLOSING_YEARS = 5;

    private Periods() {}

    /** The period a function names ({@code month} when none), refused in words when it is not one. */
    public static String period(String fn, String period) {
        String p = period == null ? "month" : period;
        if (!PERIODS.contains(p)) {
            throw new IllegalArgumentException(fn + "(): period is one of " + new java.util.TreeSet<>(PERIODS) + ", not '" + p + "'");
        }
        return p;
    }

    /** The first day of the period that holds {@code d}. */
    public static LocalDate start(LocalDate d, String period) {
        return switch (period) {
            case "day" -> d;
            case "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> d.withDayOfMonth(1);
            case "quarter" -> LocalDate.of(d.getYear(), ((d.getMonthValue() - 1) / 3) * 3 + 1, 1);
            default -> LocalDate.of(d.getYear(), 1, 1);
        };
    }

    /** The first day of the period after the one starting at {@code start}. */
    public static LocalDate next(LocalDate start, String period) {
        return switch (period) {
            case "day" -> start.plusDays(1);
            case "week" -> start.plusWeeks(1);
            case "month" -> start.plusMonths(1);
            case "quarter" -> start.plusMonths(3);
            default -> start.plusYears(1);
        };
    }

    /** The business day on or before {@code d}. */
    public static LocalDate onOrBefore(BusinessCalendar cal, LocalDate d) {
        return cal.isBusinessDay(d) ? d : cal.previous(d);
    }

    /**
     * {@code closing(agg, date, period)}: the closing business day of each period from the window's start ({@code from}, else
     * {@value #CLOSING_YEARS} years back) to the as-of date, the last one the as-of date's own (or the business day before it).
     */
    public static List<LocalDate> closingDays(LocalDate asOf, LocalDate from, String period, BusinessCalendar cal) {
        LocalDate lo = from != null && from.isBefore(asOf) ? from : asOf.minusYears(CLOSING_YEARS);
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate s = start(lo, period); !s.isAfter(asOf); s = next(s, period)) {
            LocalDate end = next(s, period).minusDays(1);
            LocalDate close = onOrBefore(cal, end.isAfter(asOf) ? asOf : end);
            if (!close.isBefore(lo) && !close.isBefore(s)) {
                days.add(close);
            }
        }
        return days;
    }

    /** {@code opening(agg, date, period)} when the answer does not group by the date: the business day before the as-of period starts. */
    public static LocalDate openingDay(LocalDate asOf, String period, BusinessCalendar cal) {
        return cal.previous(start(asOf, period));
    }
}
