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
package com.ash.drishti.rachana.el;

import com.ash.drishti.common.BusinessCalendar;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The scalars DEL had that Rachana gains with the fold (BI_FOUNDATION_2911E.md 2.5, rows 2, 6 and 10 to 18, 57): {@code round},
 * {@code len}, {@code date}, {@code timestamp}, {@code year}, {@code quarter}, {@code month}, {@code week}, {@code day},
 * {@code business_days_between} and {@code shift_date}. Each keeps its DEL entry's edge cases: null in, null out; a date is ISO text and
 * a date result ISO text; {@code week} is the ISO week; business days are the platform's calendars ({@code USNY} when none is named).
 * The engine's compiler writes the same functions in SQL, and the differential test proves the two agree value for value.
 */
final class DatePartFunctions {

    private DatePartFunctions() {}

    /** The units of {@code shift_date}. */
    static final Set<String> UNITS = Set.of("business-day", "day", "week", "month", "quarter", "year");

    static void register(Functions.Table t) {
        t.fn("round", 1, 2, (a, c) -> round(a));
        t.fn("len", 1, 1, (a, c) -> {
            String s = Args.text("len", a.get(0));
            return s == null ? null : (Object) (long) s.codePointCount(0, s.length());
        });
        t.fn("date", 1, 1, (a, c) -> Args.out(Args.date("date", a.get(0))));
        t.fn("timestamp", 1, 1, (a, c) -> timestamp(a.get(0)));
        t.fn("year", 1, 1, (a, c) -> part("year", a.get(0)));
        t.fn("quarter", 1, 1, (a, c) -> part("quarter", a.get(0)));
        t.fn("month", 1, 1, (a, c) -> part("month", a.get(0)));
        t.fn("week", 1, 1, (a, c) -> part("week", a.get(0)));
        t.fn("day", 1, 1, (a, c) -> part("day", a.get(0)));
        t.fn("business_days_between", 2, 3, (a, c) -> businessDays(a)).check("business_days_between",
                args -> DateFunctions.calendarProblem(Functions.literal(args, 2)));
        t.fn("shift_date", 2, 4, (a, c) -> shift(a)).check("shift_date", DatePartFunctions::checkShift);
    }

    /** {@code round(x, [n])}: to n places (0 by default), a half away from zero, as the database rounds. */
    private static Object round(List<Object> a) {
        java.math.BigDecimal x = Args.decimal("round", a.get(0));
        Integer n = a.size() > 1 && !Values.isNull(a.get(1)) ? Args.whole("round", a.get(1)) : Integer.valueOf(0);
        if (x == null || n == null) {
            return null;
        }
        return Args.out(x.setScale(n, java.math.RoundingMode.HALF_UP));
    }

    private static Object timestamp(Object v) {
        String s = Args.text("timestamp", v);
        if (s == null) {
            return null;
        }
        if (Args.parseDate(s) == null) {
            throw Args.typeError("timestamp", v, "a timestamp (yyyy-mm-ddThh:mm)");
        }
        return s.trim().replace(' ', 'T');
    }

    /** A part of a date: a whole number. */
    static Object part(String fn, Object v) {
        LocalDate d = Args.date(fn, v);
        if (d == null) {
            return null;
        }
        return switch (fn) {
            case "year" -> (long) d.getYear();
            case "quarter" -> (long) ((d.getMonthValue() - 1) / 3 + 1);
            case "month" -> (long) d.getMonthValue();
            case "week" -> (long) d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            default -> (long) d.getDayOfMonth();
        };
    }

    /** The calendar of argument {@code i}: the one named, or the default when none is (null or left out). */
    private static BusinessCalendar calendar(String fn, List<Object> a, int i) {
        List<Object> given = a.size() > i && !Values.isNull(a.get(i)) ? a : List.of();
        return DateFunctions.calendar(fn, given.isEmpty() ? List.of() : given, i);
    }

    /**
     * {@code business_days_between(a, b, [calendar])}: the business days after a up to and including b (negative when b is before a),
     * as DEL counted them: weekdays less the calendar's holidays.
     */
    private static Object businessDays(List<Object> a) {
        LocalDate x = Args.date("business_days_between", a.get(0));
        LocalDate y = Args.date("business_days_between", a.get(1));
        BusinessCalendar cal = calendar("business_days_between", a, 2);
        if (x == null || y == null) {
            return null;
        }
        boolean back = y.isBefore(x);
        LocalDate from = back ? y : x;
        LocalDate to = back ? x : y;
        long n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (cal.isBusinessDay(d)) {
                n++;
            }
        }
        return back ? -n : n;
    }

    /** {@code shift_date(d, n, [unit], [calendar])}: d moved by n units; business days skip weekends and the calendar's holidays. */
    private static Object shift(List<Object> a) {
        LocalDate d = Args.date("shift_date", a.get(0));
        Integer n = Args.whole("shift_date", a.get(1));
        String unit = a.size() > 2 && !Values.isNull(a.get(2)) ? Args.text("shift_date", a.get(2)).toLowerCase(Locale.ROOT) : "business-day";
        if (!UNITS.contains(unit)) {
            throw Args.error("shift_date", "unit is one of " + new java.util.TreeSet<>(UNITS) + ", not '" + unit + "'");
        }
        if (d == null || n == null) {
            return null;
        }
        return Args.out(shift(d, n, unit, "business-day".equals(unit) ? calendar("shift_date", a, 3) : null));
    }

    /** A date moved by {@code n} units (business days on the calendar: holidays and weekends skipped). */
    static LocalDate shift(LocalDate d, int n, String unit, BusinessCalendar cal) {
        return switch (unit) {
            case "day" -> d.plusDays(n);
            case "week" -> d.plusWeeks(n);
            case "month" -> d.plusMonths(n);
            case "quarter" -> d.plusMonths(3L * n);
            case "year" -> d.plusYears(n);
            default -> {
                LocalDate x = d;
                for (int i = 0; i < Math.abs(n); i++) {
                    x = n < 0 ? cal.previous(x) : cal.next(x);
                }
                yield x;
            }
        };
    }

    private static String checkShift(List<Expr> args) {
        List<String> problems = new ArrayList<>();
        if (Functions.literal(args, 2) instanceof String u && !UNITS.contains(u)) {
            problems.add("unit is one of " + new java.util.TreeSet<>(UNITS) + ", not '" + u + "'");
        }
        String cal = DateFunctions.calendarProblem(Functions.literal(args, 3));
        if (cal != null) {
            problems.add(cal);
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }
}
