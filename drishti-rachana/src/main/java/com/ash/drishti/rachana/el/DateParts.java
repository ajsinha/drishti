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
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * More dates (FUNCTION_CATALOGUE.md section 3), beside {@link DateFunctions}: {@code date} (ISO text, or year, month and day with Excel's
 * overflow: month 14 is February of the next year), the parts {@code year}, {@code quarter}, {@code month}, {@code week} (ISO),
 * {@code day}, {@code hour}, {@code minute}, {@code second} (of a timestamp's text; 0 for a plain date), {@code weekday} (with Excel's
 * return types), {@code days360} (US or European), {@code datedif} (complete years, months, days, Excel's units) and
 * {@code shift_date} (by business days on the bank's calendars, or days, weeks, months, quarters, years). A date is ISO text and a date
 * result is ISO text, as everywhere in Rachana.
 */
final class DateParts {

    private DateParts() {}

    /** The units of {@code shift_date}. */
    static final Set<String> SHIFT_UNITS = Set.of("business_day", "day", "week", "month", "quarter", "year");

    /** The units of {@code datedif}, Excel's. */
    static final Set<String> DATEDIF_UNITS = Set.of("Y", "M", "D", "MD", "YM", "YD");

    /** The most business days {@code shift_date} walks: about 400 years, a bound on the work. */
    static final int MAX_BUSINESS_SHIFT = 100_000;

    static void register(Functions.Table t) {
        t.fn("date", 1, 3, (a, c) -> date(a));
        part(t, "year", LocalDate::getYear);
        part(t, "quarter", d -> (d.getMonthValue() - 1) / 3 + 1);
        part(t, "month", LocalDate::getMonthValue);
        part(t, "week", d -> d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        part(t, "day", LocalDate::getDayOfMonth);
        time(t, "hour", 0);
        time(t, "minute", 1);
        time(t, "second", 2);
        t.fn("weekday", 1, 2, (a, c) -> weekday(a));
        t.fn("days360", 2, 3, (a, c) -> {
            LocalDate x = Args.date("days360", a.get(0));
            LocalDate y = Args.date("days360", a.get(1));
            Object m = a.size() > 2 ? Values.simplify(a.get(2)) : Boolean.FALSE;
            if (x == null || y == null || m == null) {
                return null;
            }
            boolean european = m instanceof Boolean b ? b : Args.decimal("days360", m).signum() != 0;
            return european ? DateFunctions.days360European(x, y) : days360Us(x, y);
        });
        t.fn("datedif", 3, 3, (a, c) -> datedif(a)).check("datedif", args -> Functions.literal(args, 2) instanceof String u
                && !DATEDIF_UNITS.contains(u.toUpperCase(Locale.ROOT)) ? unitProblem(u) : null);
        t.fn("shift_date", 2, 4, (a, c) -> shift(a)).check("shift_date", args -> Functions.literal(args, 2) instanceof String u
                && !SHIFT_UNITS.contains(u) ? "unknown unit '" + u + "' (known: " + String.join(", ", new java.util.TreeSet<>(SHIFT_UNITS)) + ")"
                : null);
    }

    private static void part(Functions.Table t, String name, java.util.function.ToIntFunction<LocalDate> f) {
        t.fn(name, 1, 1, (a, c) -> {
            LocalDate d = Args.date(name, a.get(0));
            return d == null ? null : (Object) (long) f.applyAsInt(d);
        });
    }

    /** {@code hour}, {@code minute}, {@code second}: of a timestamp ({@code 2026-10-16T09:30:15Z}) or a time ({@code 09:30}); 0 for a date. */
    private static void time(Functions.Table t, String name, int index) {
        t.fn(name, 1, 1, (a, c) -> {
            String s = Args.text(name, a.get(0));
            if (s == null) {
                return null;
            }
            String x = s.strip();
            String clock;
            if (x.length() >= 10 && Args.parseDate(x) != null) {
                if (x.length() == 10) {
                    return 0L;
                }
                clock = x.substring(11);
            } else {
                clock = x;
            }
            java.util.regex.Matcher m = CLOCK.matcher(clock);
            if (!m.lookingAt()) {
                throw Args.typeError(name, a.get(0), "a timestamp or a time (hh:mm[:ss])");
            }
            int h = Integer.parseInt(m.group(1));
            int mi = Integer.parseInt(m.group(2));
            int se = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (h > 23 || mi > 59 || se > 59) {
                throw Args.typeError(name, a.get(0), "a time of day");
            }
            return (long) (index == 0 ? h : index == 1 ? mi : se);
        });
    }

    private static final java.util.regex.Pattern CLOCK = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

    /** {@code date(text)} or {@code date(year, month, day)}: Excel's DATE, months and days past their end carried over. */
    private static Object date(List<Object> a) {
        if (a.size() == 1) {
            return Args.out(Args.date("date", a.get(0)));
        }
        if (a.size() != 3) {
            throw Args.error("date", "write date('2026-10-16') or date(2026, 10, 16)");
        }
        Integer y = Args.whole("date", a.get(0));
        Integer m = Args.whole("date", a.get(1));
        Integer d = Args.whole("date", a.get(2));
        if (y == null || m == null || d == null) {
            return null;
        }
        if (y < 1 || y > 9999) {
            throw Args.error("date", "the year " + y + " is not from 1 to 9999");
        }
        try {
            LocalDate out = LocalDate.of(y, 1, 1).plusMonths(m - 1L).plusDays(d - 1L);
            if (out.getYear() < 1 || out.getYear() > 9999) {
                throw Args.error("date", "the date falls outside the years 1 to 9999");
            }
            return Args.out(out);
        } catch (DateTimeException e) {
            throw Args.error("date", "the date falls outside the years 1 to 9999");
        }
    }

    /**
     * {@code weekday(d, [type])}: the day of the week as Excel's WEEKDAY numbers it: type 1 (the default) Sunday 1 to Saturday 7; 2 Monday
     * 1 to Sunday 7; 3 Monday 0 to Sunday 6; 11 to 17 a week starting Monday (11) … Sunday (17), numbered 1 to 7.
     */
    private static Object weekday(List<Object> a) {
        LocalDate d = Args.date("weekday", a.get(0));
        Integer type = a.size() > 1 ? Args.whole("weekday", a.get(1)) : Integer.valueOf(1);
        if (d == null || type == null) {
            return null;
        }
        int iso = d.getDayOfWeek().getValue();             // Monday 1 … Sunday 7
        return switch (type) {
            case 1, 17 -> (long) (iso % 7 + 1);
            case 2, 11 -> (long) iso;
            case 3 -> (long) (iso - 1);
            case 12, 13, 14, 15, 16 -> {
                int start = type - 10;                     // 12: Tuesday (2) … 16: Saturday (6)
                yield (long) (Math.floorMod(iso - start, 7) + 1);
            }
            default -> throw Args.error("weekday", "the type " + type + " is not 1, 2, 3 or 11 to 17");
        };
    }

    /** Excel's DAYS360 with method FALSE (US): a start on the 31st is the 30th; an end on the 31st is the 30th when the start is ≥ 30. */
    static long days360Us(LocalDate a, LocalDate b) {
        int d1 = a.getDayOfMonth();
        int d2 = b.getDayOfMonth();
        if (a.getMonthValue() == 2 && d1 == a.lengthOfMonth()) {
            d1 = 30;
        }
        if (d1 == 31) {
            d1 = 30;
        }
        if (d2 == 31 && d1 >= 30) {
            d2 = 30;
        }
        return 360L * (b.getYear() - a.getYear()) + 30L * (b.getMonthValue() - a.getMonthValue()) + (d2 - d1);
    }

    private static String unitProblem(String u) {
        return "unknown unit '" + u + "' (known: Y, M, D, MD, YM, YD)";
    }

    /**
     * {@code datedif(start, end, unit)}: complete units from start to end, Excel's DATEDIF: {@code 'Y'} years, {@code 'M'} months,
     * {@code 'D'} days, {@code 'MD'} days ignoring months and years, {@code 'YM'} months ignoring years, {@code 'YD'} days ignoring years.
     * The end before the start is a problem (Excel's #NUM!); {@code intck} counts boundaries crossed instead, either way.
     */
    private static Object datedif(List<Object> a) {
        LocalDate x = Args.date("datedif", a.get(0));
        LocalDate y = Args.date("datedif", a.get(1));
        String u = Args.text("datedif", a.get(2));
        if (x == null || y == null || u == null) {
            return null;
        }
        String unit = u.toUpperCase(Locale.ROOT);
        if (!DATEDIF_UNITS.contains(unit)) {
            throw Args.error("datedif", unitProblem(u));
        }
        if (y.isBefore(x)) {
            throw Args.error("datedif", "the end " + y + " is before the start " + x);
        }
        return switch (unit) {
            case "Y" -> ChronoUnit.YEARS.between(x, y);
            case "M" -> ChronoUnit.MONTHS.between(x, y);
            case "D" -> ChronoUnit.DAYS.between(x, y);
            case "YM" -> ChronoUnit.MONTHS.between(x, y) % 12;
            case "MD" -> {
                LocalDate base = x.plusMonths(ChronoUnit.MONTHS.between(x, y));
                yield ChronoUnit.DAYS.between(base, y);
            }
            default -> {
                LocalDate base = x.plusYears(ChronoUnit.YEARS.between(x, y));
                yield ChronoUnit.DAYS.between(base, y);
            }
        };
    }

    /**
     * {@code shift_date(d, n, [unit], [calendar])}: d moved by n units: {@code 'business_day'} (the default, on the calendar, USNY by
     * default: weekends and its holidays skipped; from a holiday, n = 0 stays put), {@code 'day'}, {@code 'week'}, {@code 'month'},
     * {@code 'quarter'}, {@code 'year'} (a month's 31st into a shorter month: its last day). Excel's WORKDAY.INTL and EDATE; SAS's INTNX
     * with 'same'.
     */
    private static Object shift(List<Object> a) {
        LocalDate d = Args.date("shift_date", a.get(0));
        Integer n = Args.whole("shift_date", a.get(1));
        String unit = a.size() > 2 ? Args.text("shift_date", a.get(2)) : "business_day";
        if (d == null || n == null || unit == null) {
            return null;
        }
        if (!SHIFT_UNITS.contains(unit)) {
            throw Args.error("shift_date", "unknown unit '" + unit + "' (known: " + String.join(", ", new java.util.TreeSet<>(SHIFT_UNITS)) + ")");
        }
        if (a.size() > 3 && !"business_day".equals(unit)) {
            throw Args.error("shift_date", "a calendar moves only 'business_day', not '" + unit + "'");
        }
        try {
            return switch (unit) {
                case "day" -> Args.out(d.plusDays(n));
                case "week" -> Args.out(d.plusWeeks(n));
                case "month" -> Args.out(d.plusMonths(n));
                case "quarter" -> Args.out(d.plusMonths(3L * n));
                case "year" -> Args.out(d.plusYears(n));
                default -> {
                    if (Math.abs(n) > MAX_BUSINESS_SHIFT) {
                        throw Args.error("shift_date", "moves at most " + MAX_BUSINESS_SHIFT + " business days");
                    }
                    String spec = a.size() > 3 ? Args.text("shift_date", a.get(3)) : DateFunctions.DEFAULT_CALENDAR;
                    if (spec == null) {
                        yield null;
                    }
                    BusinessCalendar cal;
                    try {
                        cal = BusinessCalendar.of(spec.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw Args.error("shift_date", e.getMessage());
                    }
                    LocalDate x = d;
                    int step = n < 0 ? -1 : 1;
                    for (int left = Math.abs(n); left > 0;) {
                        x = x.plusDays(step);
                        if (x.getDayOfWeek() != DayOfWeek.SATURDAY && x.getDayOfWeek() != DayOfWeek.SUNDAY && cal.isBusinessDay(x)) {
                            left--;
                        }
                    }
                    yield Args.out(x);
                }
            };
        } catch (DateTimeException e) {
            throw Args.error("shift_date", "the date falls outside the calendar's years");
        }
    }
}
