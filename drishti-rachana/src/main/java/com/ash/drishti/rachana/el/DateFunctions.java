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
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dates, on the bank's calendars (FUNCTION_CATALOGUE.md section 3): {@code today}, {@code days_between}, {@code eomonth},
 * {@code edate}, {@code yearfrac}, {@code intck}, {@code is_business_day}. A date is ISO text ({@code 2026-10-16}; a
 * timestamp counts by its date) and a date result is ISO text. Business days are the platform's own calendars
 * ({@link BusinessCalendar}: {@code USNY}, {@code GBLO}, {@code EUTA}, {@code JPTO}, {@code CATO}, {@code WEEKENDS}, or a
 * joint {@code USNY+GBLO}), the ones the business date, the history windows and BI's {@code business_days_between} use;
 * {@value #DEFAULT_CALENDAR} when none is given.
 */
final class DateFunctions {

    private DateFunctions() {}

    /** The calendar when a function is given none: the platform's default business-date calendar. */
    static final String DEFAULT_CALENDAR = "USNY";

    /** The day-count bases of {@code yearfrac}, by their one name. */
    static final List<String> BASES = List.of("ACT/360", "ACT/365F", "30/360", "30E/360", "ACT/ACT");

    /** The intervals of {@code intck}. */
    static final Set<String> INTERVALS = Set.of("day", "week", "month", "quarter", "year", "business_day");

    static void register(Functions.Table t) {
        // asof(): the view's or the query's as-of date; today() is read as asof() (BI_FOUNDATION_2911E.md Q7)
        t.fn("asof", 0, 0, (a, c) -> c.asOf() == null ? null : c.asOf().toString());
        t.old("today", "asof", 0, 0);
        DatePartFunctions.register(t);
        t.fn("days_between", 2, 2, (a, c) -> {
            LocalDate x = Args.date("days_between", a.get(0));
            LocalDate y = Args.date("days_between", a.get(1));
            return x == null || y == null ? null : (Object) ChronoUnit.DAYS.between(x, y);
        });
        // read for compatibility: existing Sutras write days(from, to), which gave empty (not a problem) for text that is not a date
        t.old("days", "days_between", 2, 2, (a, c) -> {
            LocalDate x = looseDate(a.get(0));
            LocalDate y = looseDate(a.get(1));
            return x == null || y == null ? null : (Object) ChronoUnit.DAYS.between(x, y);
        });
        t.fn("eomonth", 1, 2, (a, c) -> {
            LocalDate d = Args.date("eomonth", a.get(0));
            Integer n = a.size() > 1 ? Args.whole("eomonth", a.get(1)) : Integer.valueOf(0);
            return d == null || n == null ? null : Args.out(d.plusMonths(n).with(TemporalAdjusters.lastDayOfMonth()));
        });
        t.fn("edate", 2, 2, (a, c) -> {
            LocalDate d = Args.date("edate", a.get(0));
            Integer n = Args.whole("edate", a.get(1));
            return d == null || n == null ? null : Args.out(d.plusMonths(n));     // the 31st into a shorter month: its last day
        });
        t.fn("yearfrac", 3, 3, (a, c) -> yearfrac(a)).check("yearfrac", args -> Functions.literal(args, 2) instanceof String b
                && !BASES.contains(b) ? basisProblem(b) : null);
        t.fn("intck", 3, 4, (a, c) -> intck(a)).check("intck", DateFunctions::checkIntck);
        t.fn("is_business_day", 1, 2, (a, c) -> {
            LocalDate d = Args.date("is_business_day", a.get(0));
            BusinessCalendar cal = calendar("is_business_day", a, 1);
            return d == null || cal == null ? null : (Object) cal.isBusinessDay(d);
        }).check("is_business_day", args -> calendarProblem(Functions.literal(args, 1)));
    }

    /** How {@code days} has always read a date: the first ten characters of the text, or empty. */
    private static LocalDate looseDate(Object v) {
        String t = Values.text(Values.simplify(v)).trim();
        if (t.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(t.substring(0, 10));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static final ConcurrentHashMap<String, BusinessCalendar> CALENDARS = new ConcurrentHashMap<>();

    /** The calendar of argument {@code i} ({@value #DEFAULT_CALENDAR} when not given); null when given empty. */
    static BusinessCalendar calendar(String fn, List<Object> a, int i) {
        String spec = a.size() > i ? Args.text(fn, a.get(i)) : DEFAULT_CALENDAR;
        if (spec == null) {
            return null;
        }
        String key = spec.trim().toUpperCase(Locale.ROOT);
        BusinessCalendar cal = CALENDARS.get(key);
        if (cal == null) {
            try {
                cal = BusinessCalendar.of(key);
            } catch (IllegalArgumentException e) {
                throw Args.error(fn, e.getMessage());
            }
            if (CALENDARS.size() < 64) {
                CALENDARS.put(key, cal);                                  // holidays are worked out once per year per calendar
            }
        }
        return cal;
    }

    static String calendarProblem(Object literal) {
        if (literal instanceof String spec) {
            try {
                BusinessCalendar.of(spec.trim());
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        return null;
    }

    private static String basisProblem(String basis) {
        return "unknown day-count basis '" + basis + "' (known: " + String.join(", ", BASES) + ")";
    }

    /**
     * {@code yearfrac(a, b, basis)}: the fraction of a year from a to b on a day-count basis, as Excel's YEARFRAC (the dates
     * in either order; the result is never negative). {@code ACT/360} and {@code ACT/365F}: actual days over 360 or 365;
     * {@code 30/360}: the US (NASD) 30/360 of YEARFRAC's basis 0, February's last day counting as the 30th; {@code 30E/360}:
     * the European 30/360 of basis 4; {@code ACT/ACT}: ISDA's, each year's days over that year's length (366 in a leap
     * year). A decimal to 16 significant digits.
     */
    private static Object yearfrac(List<Object> a) {
        LocalDate x = Args.date("yearfrac", a.get(0));
        LocalDate y = Args.date("yearfrac", a.get(1));
        String basis = Args.text("yearfrac", a.get(2));
        if (x == null || y == null || basis == null) {
            return null;
        }
        if (!BASES.contains(basis)) {
            throw Args.error("yearfrac", basisProblem(basis));
        }
        if (x.isAfter(y)) {
            LocalDate t = x;
            x = y;
            y = t;
        }
        BigDecimal f = switch (basis) {
            case "ACT/360" -> ratio(ChronoUnit.DAYS.between(x, y), 360);
            case "ACT/365F" -> ratio(ChronoUnit.DAYS.between(x, y), 365);
            case "30/360" -> ratio(days360Us(x, y), 360);
            case "30E/360" -> ratio(days360European(x, y), 360);
            default -> actActIsda(x, y);
        };
        return Args.out(f.round(MathContext.DECIMAL64));
    }

    private static BigDecimal ratio(long days, int year) {
        return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(year), MathContext.DECIMAL128);
    }

    /** US (NASD) 30/360 as Excel's YEARFRAC basis 0 counts it, including its February month-end rules. */
    static long days360Us(LocalDate a, LocalDate b) {
        int d1 = a.getDayOfMonth();
        int d2 = b.getDayOfMonth();
        boolean aFebEnd = a.getMonthValue() == 2 && d1 == a.lengthOfMonth();
        boolean bFebEnd = b.getMonthValue() == 2 && d2 == b.lengthOfMonth();
        if (d1 == 31 && d2 == 31) {
            d1 = 30;
            d2 = 30;
        } else if (d1 == 31) {
            d1 = 30;
        } else if (d1 == 30 && d2 == 31) {
            d2 = 30;
        } else if (aFebEnd && bFebEnd) {
            d1 = 30;
            d2 = 30;
        } else if (aFebEnd) {
            d1 = 30;
        }
        return days360(a, b, d1, d2);
    }

    /** European 30/360 (Excel's basis 4): a 31st counts as the 30th, at either end. */
    static long days360European(LocalDate a, LocalDate b) {
        return days360(a, b, Math.min(a.getDayOfMonth(), 30), Math.min(b.getDayOfMonth(), 30));
    }

    private static long days360(LocalDate a, LocalDate b, int d1, int d2) {
        return 360L * (b.getYear() - a.getYear()) + 30L * (b.getMonthValue() - a.getMonthValue()) + (d2 - d1);
    }

    /** ACT/ACT (ISDA): the days in each calendar year over that year's length, summed. */
    private static BigDecimal actActIsda(LocalDate a, LocalDate b) {
        BigDecimal sum = BigDecimal.ZERO;
        LocalDate from = a;
        while (from.isBefore(b)) {
            LocalDate yearEnd = LocalDate.of(from.getYear() + 1, 1, 1);
            LocalDate to = b.isBefore(yearEnd) ? b : yearEnd;
            sum = sum.add(ratio(ChronoUnit.DAYS.between(from, to), from.lengthOfYear()));
            from = to;
        }
        return sum;
    }

    /**
     * {@code intck(interval, a, b, [calendar])}: how many interval boundaries lie between a and b (SAS's INTCK, discrete):
     * {@code 'day'}, {@code 'week'} (weeks start on Sunday), {@code 'month'}, {@code 'quarter'}, {@code 'year'}, or
     * {@code 'business_day'}: the business days after a up to and including b on the calendar. Negative when b is before a.
     */
    private static Object intck(List<Object> a) {
        String interval = Args.text("intck", a.get(0));
        LocalDate x = Args.date("intck", a.get(1));
        LocalDate y = Args.date("intck", a.get(2));
        if (interval == null || x == null || y == null) {
            return null;
        }
        if (!INTERVALS.contains(interval)) {
            throw Args.error("intck", intervalProblem(interval));
        }
        if (a.size() > 3 && !"business_day".equals(interval)) {
            throw Args.error("intck", "a calendar counts only 'business_day', not '" + interval + "'");
        }
        return switch (interval) {
            case "day" -> ChronoUnit.DAYS.between(x, y);
            case "week" -> Math.floorDiv(sundayWeek(y), 7) - Math.floorDiv(sundayWeek(x), 7);
            case "month" -> months(y) - months(x);
            case "quarter" -> quarters(y) - quarters(x);
            case "year" -> (long) y.getYear() - x.getYear();
            default -> {
                BusinessCalendar cal = calendar("intck", a, 3);
                yield cal == null ? null : (Object) businessDays(cal, x, y);
            }
        };
    }

    /** Days since a Sunday, so a week boundary falls between each Saturday and Sunday. */
    private static long sundayWeek(LocalDate d) {
        return d.toEpochDay() - LocalDate.of(1969, 12, 28).toEpochDay();  // 1969-12-28 was a Sunday
    }

    private static long months(LocalDate d) {
        return d.getYear() * 12L + d.getMonthValue();
    }

    private static long quarters(LocalDate d) {
        return d.getYear() * 4L + (d.getMonthValue() - 1) / 3;
    }

    /** The most days {@code intck('business_day', ...)} walks: about 400 years, a bound on the work. */
    static final long MAX_BUSINESS_SPAN = 146_100;

    private static long businessDays(BusinessCalendar cal, LocalDate a, LocalDate b) {
        if (Math.abs(ChronoUnit.DAYS.between(a, b)) > MAX_BUSINESS_SPAN) {
            throw Args.error("intck", "'business_day' counts at most " + MAX_BUSINESS_SPAN + " days apart");
        }
        boolean forward = !b.isBefore(a);
        LocalDate from = forward ? a : b;
        LocalDate to = forward ? b : a;
        long n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && cal.isBusinessDay(d)) {
                n++;
            }
        }
        return forward ? n : -n;
    }

    private static String intervalProblem(String interval) {
        return "unknown interval '" + interval + "' (known: " + String.join(", ", new java.util.TreeSet<>(INTERVALS)) + ")";
    }

    private static String checkIntck(List<Expr> args) {
        if (Functions.literal(args, 0) instanceof String interval) {
            if (!INTERVALS.contains(interval)) {
                return intervalProblem(interval);
            }
            if (args.size() > 3 && !"business_day".equals(interval)) {
                return "a calendar counts only 'business_day', not '" + interval + "'";
            }
        }
        return calendarProblem(Functions.literal(args, 3));
    }
}
