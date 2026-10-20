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
package com.ash.drishti.engine.history;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The business dates a history asks for (HISTORY_QUERIES.md sections 4.1 and 7.3), resolved against the business calendar.
 *
 * <ul>
 *   <li>{@code data-days}: a bare number N: the last N business dates on which the entity has data, ending at the date box; the walk
 *       may reach back {@code max-days} business dates. One more date with data is asked for: the baseline.</li>
 *   <li>{@code calendar}: {@code 30D}, {@code 2W}, {@code 3M}, {@code 1Y}, {@code YTD}, {@code MTD}, {@code QTD}: every business date of the
 *       span ending at the date box, plus the business date before it as the baseline.</li>
 *   <li>{@code between}: {@code from} (and {@code to}, default the date box), plus the business date before {@code from}.</li>
 * </ul>
 *
 * @param asked the range as asked ({@code 30}, {@code 3M}, {@code FROM 2026-09-01 TO 2026-09-30})
 * @param mode {@code data-days}, {@code calendar} or {@code between}
 * @param from the first date of the window
 * @param to the last date of the window (the date box unless {@code to} said otherwise)
 * @param dates every business date to read, oldest first, the baseline candidate included
 * @param lastDays for {@code data-days}: N + 1 (the rows and the baseline); otherwise 0
 * @param days for {@code data-days}: N; otherwise the business dates of the window
 */
public record HistoryWindow(String asked, String mode, LocalDate from, LocalDate to, List<LocalDate> dates, int lastDays, int days) {

    public static final String DATA_DAYS = "data-days";
    public static final String CALENDAR = "calendar";
    public static final String BETWEEN = "between";

    private static final Pattern SPAN = Pattern.compile("(?i)^(\\d{1,5})\\s*([DWMY]?)$");

    /**
     * Resolves a range. Exactly one of {@code days}, {@code range} and {@code from} is expected (none: the default number of days).
     *
     * @param end the date box (the current business date when live)
     * @param current the current business date (a {@code FROM} after it is in the future)
     * @param earliest the earliest business date the server reads
     * @param maxDays the most business dates allowed (the kind's, else the server's)
     */
    public static HistoryWindow resolve(Integer days, String range, String from, String to, int defaultDays, LocalDate end, LocalDate current,
            LocalDate earliest, int maxDays, BusinessCalendar cal) {
        LocalDate last = to == null || to.isBlank() ? end : date(to, "to");
        if (from != null && !from.isBlank()) {
            LocalDate first = date(from, "from");
            if (first.isAfter(current)) {
                throw new DrishtiException(ErrorCode.HISTORY_BAD_RANGE, "FROM " + first + " is in the future (the business date is " + current + ")");
            }
            if (first.isAfter(last)) {
                throw new DrishtiException(ErrorCode.HISTORY_BAD_RANGE, "FROM " + first + " is after TO " + last);
            }
            String asked = "FROM " + first + (to == null || to.isBlank() ? "" : " TO " + last);
            return span(asked, BETWEEN, first, last, earliest, maxDays, cal);
        }
        String r = range == null ? null : range.trim().toUpperCase(Locale.ROOT);
        if ((r == null || r.isEmpty()) && days == null) {
            days = defaultDays;
        }
        if (r == null || r.isEmpty()) {
            return dataDays(String.valueOf(days), days, last, earliest, maxDays, cal);
        }
        switch (r) {
            case "YTD":
                return span(r, CALENDAR, last.withDayOfYear(1), last, earliest, maxDays, cal);
            case "MTD":
                return span(r, CALENDAR, last.withDayOfMonth(1), last, earliest, maxDays, cal);
            case "QTD":
                return span(r, CALENDAR, LocalDate.of(last.getYear(), ((last.getMonthValue() - 1) / 3) * 3 + 1, 1), last, earliest, maxDays, cal);
            default:
                break;
        }
        Matcher m = SPAN.matcher(r);
        if (!m.matches()) {
            throw new DrishtiException(ErrorCode.HISTORY_BAD_RANGE, "'" + range + "' is not a range: use a number of days with data (30), "
                    + "a span (30D, 2W, 3M, 1Y), YTD, MTD, QTD, or from=yyyy-mm-dd");
        }
        int n = Integer.parseInt(m.group(1));
        String unit = m.group(2);
        if (unit.isEmpty()) {
            return dataDays(r, n, last, earliest, maxDays, cal);
        }
        if (n < 1) {
            throw new DrishtiException(ErrorCode.HISTORY_BAD_RANGE, "the range " + r + " is empty");
        }
        LocalDate first = switch (unit) {
            case "D" -> last.minusDays(n - 1L);
            case "W" -> last.minusWeeks(n).plusDays(1);
            case "M" -> last.minusMonths(n).plusDays(1);
            default -> last.minusYears(n).plusDays(1);
        };
        return span(r, CALENDAR, first, last, earliest, maxDays, cal);
    }

    private static HistoryWindow dataDays(String asked, int n, LocalDate last, LocalDate earliest, int maxDays, BusinessCalendar cal) {
        if (n < 1) {
            throw new DrishtiException(ErrorCode.HISTORY_BAD_RANGE, "a history of " + n + " days is empty: ask for 1 or more");
        }
        if (n > maxDays) {
            throw tooLarge(n, maxDays);
        }
        List<LocalDate> back = new ArrayList<>();
        // the walk may pass days without data: up to max-days business dates, and one more for the baseline
        for (LocalDate d = cal.onOrBefore(last); back.size() < maxDays + 1 && !d.isBefore(earliest); d = cal.previous(d)) {
            back.add(0, d);
        }
        LocalDate first = back.isEmpty() ? last : back.get(0);
        return new HistoryWindow(asked, DATA_DAYS, first, last, List.copyOf(back), n + 1, n);
    }

    private static HistoryWindow span(String asked, String mode, LocalDate first, LocalDate last, LocalDate earliest, int maxDays,
            BusinessCalendar cal) {
        LocalDate start = first.isBefore(earliest) ? earliest : first;
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(1)) {
            if (cal.isBusinessDay(d)) {
                dates.add(d);
                if (dates.size() > maxDays) {
                    throw tooLarge(countBusiness(start, last, cal), maxDays);
                }
            }
        }
        LocalDate before = cal.previous(dates.isEmpty() ? start : dates.get(0));
        if (!before.isBefore(earliest)) {
            dates.add(0, before);                                     // the baseline candidate
        }
        int inWindow = (int) dates.stream().filter(d -> !d.isBefore(start)).count();
        return new HistoryWindow(asked, mode, start, last, List.copyOf(dates), 0, inWindow);
    }

    private static int countBusiness(LocalDate a, LocalDate b, BusinessCalendar cal) {
        int n = 0;
        for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
            n += cal.isBusinessDay(d) ? 1 : 0;
        }
        return n;
    }

    private static DrishtiException tooLarge(int asked, int max) {
        return new DrishtiException(ErrorCode.HISTORY_RANGE_TOO_LARGE, max + " business days at most (asked " + asked
                + "): narrow it with FROM ... TO, or a shorter range");
    }

    private static LocalDate date(String s, String what) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.HISTORY_BAD_RANGE, what + " '" + s + "' is not a date (yyyy-mm-dd)");
        }
    }

    /** The first date the request may read (the baseline candidate included). */
    public LocalDate readFrom() {
        return dates.isEmpty() ? from : dates.get(0);
    }
}
