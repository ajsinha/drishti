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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The windows of the relative-date slicer (docs/guides/REPORTS.md): a name such as {@code last-30-days} or {@code this-quarter} is worked out, on the server,
 * from the report's as-of date (the end of its dates, else the current business date) and the pack's business-day calendar, so the same name means the same
 * dates on every screen, in an export and in a subscription.
 *
 * <ul>
 *   <li>{@code last-N-days}: the N calendar days ending on the as-of date (the as-of date counts as one).</li>
 *   <li>{@code last-N-business-days}: the N business days ending on the as-of date, or on the business day before it when the as-of date is a weekend or a
 *       holiday; holidays are skipped, so the window can reach back further than N days.</li>
 *   <li>{@code last-N-weeks}: the 7N days ending on the as-of date. {@code last-N-months}: from the day after the same date N months earlier.</li>
 *   <li>{@code this-week|month|quarter|year}: from the start of the period (a week starts on Monday) to the as-of date.</li>
 *   <li>{@code previous-week|month|quarter|year}: the whole period before the one the as-of date is in.</li>
 *   <li>{@code all}: no restriction.</li>
 * </ul>
 */
public final class RelativeDates {

    /** An inclusive window of dates. */
    public record Window(LocalDate from, LocalDate to) {}

    /** The names offered when a slicer lists none of its own. */
    public static final List<String> DEFAULT_OPTIONS = List.of("last-7-days", "last-30-days", "last-90-days", "last-5-business-days", "last-4-weeks", "last-3-months", "last-12-months",
            "this-week", "this-month", "this-quarter", "this-year", "previous-week", "previous-month", "previous-quarter", "previous-year", "all");

    /** The most days, weeks or months a name may reach back. */
    public static final int MAX_N = 3660;

    private static final Pattern LAST = Pattern.compile("last-(\\d{1,4})-(days|business-days|weeks|months)");
    private static final Pattern PERIOD = Pattern.compile("(this|previous)-(week|month|quarter|year)");

    private RelativeDates() {}

    /** True when {@link #resolve} understands the name. */
    public static boolean valid(String name) {
        if ("all".equals(name)) {
            return true;
        }
        Matcher m = LAST.matcher(String.valueOf(name));
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1));
            return n >= 1 && n <= MAX_N;
        }
        return PERIOD.matcher(String.valueOf(name)).matches();
    }

    /**
     * The window a name means at {@code asOf}; null for {@code all}.
     *
     * @throws IllegalArgumentException when the name is not one of the forms above
     */
    public static Window resolve(String name, LocalDate asOf, BusinessCalendar calendar) {
        if ("all".equals(name)) {
            return null;
        }
        Matcher m = LAST.matcher(String.valueOf(name));
        if (m.matches() && valid(name)) {
            int n = Integer.parseInt(m.group(1));
            return switch (m.group(2)) {
                case "days" -> new Window(asOf.minusDays(n - 1L), asOf);
                case "weeks" -> new Window(asOf.minusDays(7L * n - 1), asOf);
                case "months" -> new Window(asOf.minusMonths(n).plusDays(1), asOf);
                default -> businessDays(n, asOf, calendar);
            };
        }
        Matcher p = PERIOD.matcher(String.valueOf(name));
        if (p.matches()) {
            LocalDate start = start(p.group(2), asOf);
            if ("this".equals(p.group(1))) {
                return new Window(start, asOf);
            }
            LocalDate before = start.minusDays(1);
            return new Window(start(p.group(2), before), before);
        }
        throw new IllegalArgumentException("'" + name + "' is not a relative date: last-N-days, last-N-business-days, last-N-weeks, last-N-months, this-week|month|quarter|year, "
                + "previous-week|month|quarter|year or all");
    }

    /**
     * Works out the relative-date slicers of a report (REPORTS.md section 4.6): a slicer's choice {@code {relative: "last-30-days"}}, or its {@code default}
     * when the person made none, becomes {@code {from, to, day: true}} from the report's as-of date (the end of its dates, else the current business date) and
     * the business-day calendar. Done on the server, so a screen, an export and a subscription mean the same dates.
     */
    public static ReportState apply(Report report, ReportState state, java.util.function.Supplier<LocalDate> current, BusinessCalendar calendar) {
        java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> slicers = null;
        LocalDate asOf = null;
        for (Report.Page page : report.pages()) {
            for (Report.Visual v : page.visuals()) {
                if (!"slicer".equals(v.type()) || !"relative-date".equals(v.format().get("mode"))) {
                    continue;
                }
                if (slicers == null) {
                    slicers = new java.util.LinkedHashMap<>(state.slicers());
                }
                com.fasterxml.jackson.databind.JsonNode chosen = slicers.get(v.id());
                if (chosen != null && chosen.isObject() && !chosen.has("relative")) {
                    continue;                                                       // already dates
                }
                String name = chosen == null || chosen.isNull() ? (v.format().get("default") == null ? "all" : String.valueOf(v.format().get("default")))
                        : chosen.isTextual() ? chosen.asText() : chosen.path("relative").asText("");
                if (!valid(name)) {
                    throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.REPORT_STATE,
                            "'" + name + "' is not a relative date for the slicer '" + v.id() + "'");
                }
                if (asOf == null) {
                    String end = state.to() != null ? state.to() : state.from() != null ? state.from() : report.dates() != null && report.dates().to() != null ? report.dates().to()
                            : report.dates() != null && report.dates().from() != null ? report.dates().from() : null;
                    try {
                        asOf = end == null ? current.get() : LocalDate.parse(end);
                    } catch (java.time.format.DateTimeParseException e) {
                        asOf = current.get();
                    }
                }
                Window w = resolve(name, asOf, calendar);
                if (w == null) {
                    slicers.remove(v.id());
                } else {
                    slicers.put(v.id(), new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("from", w.from().toString()).put("to", w.to().toString()).put("day", true));
                }
            }
        }
        return slicers == null ? state : state.withSlicers(slicers);
    }

    private static LocalDate start(String period, LocalDate d) {
        return switch (period) {
            case "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> d.withDayOfMonth(1);
            case "quarter" -> LocalDate.of(d.getYear(), ((d.getMonthValue() - 1) / 3) * 3 + 1, 1);
            default -> LocalDate.of(d.getYear(), Month.JANUARY, 1);
        };
    }

    private static Window businessDays(int n, LocalDate asOf, BusinessCalendar calendar) {
        LocalDate last = calendar.onOrBefore(asOf);
        LocalDate first = last;
        for (int i = 1; i < n; i++) {
            first = calendar.previous(first);
        }
        return new Window(first, last);
    }
}
