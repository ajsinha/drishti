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
package com.ash.drishti.server.dist;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.server.reports.ReportSchedule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a subscription runs, as people say it. Everything the scheduled reports accept ({@code daily 07:00}, {@code weekdays 18:30},
 * {@code business-days 07:30}, {@code hourly}, {@code cron <six fields>}) and:
 *
 * <ul>
 *   <li>{@code weekly mon 07:30}: that weekday;
 *   <li>{@code monthly 1 07:30} or {@code monthly last 18:00}: that day of the month (a short month uses its last day);
 *   <li>either of those followed by {@code business}: a day that is a holiday in the server's calendar moves to the next business day
 *       ({@code monthly last 18:00 business} is the last business day of the month);
 *   <li>{@code after-load}, optionally followed by a kind ({@code after-load TRD}): not a time but an event, the data load that finished ready;
 *       the subscription runs shortly after it.
 * </ul>
 *
 * Times are in the business-date zone, as for scheduled reports.
 */
public final class SubscriptionSchedule {

    private static final Pattern WEEKLY = Pattern.compile("weekly (mon|tue|wed|thu|fri|sat|sun) ([01]?\\d|2[0-3]):([0-5]\\d)( business)?");
    private static final Pattern MONTHLY = Pattern.compile("monthly (last|[1-9]|[12]\\d|3[01]) ([01]?\\d|2[0-3]):([0-5]\\d)( business)?");
    private static final Pattern AFTER = Pattern.compile("after-load(?: ([A-Za-z0-9_.-]+))?", Pattern.CASE_INSENSITIVE);
    private static final String[] DAYS = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};

    private enum Mode { TIMED, WEEKLY, MONTHLY, LOAD }

    private final String text;
    private final Mode mode;
    private final ReportSchedule timed;
    private final DayOfWeek weekday;
    private final String day;
    private final LocalTime time;
    private final boolean business;
    private final String kind;

    private SubscriptionSchedule(String text, Mode mode, ReportSchedule timed, DayOfWeek weekday, String day, LocalTime time, boolean business, String kind) {
        this.text = text;
        this.mode = mode;
        this.timed = timed;
        this.weekday = weekday;
        this.day = day;
        this.time = time;
        this.business = business;
        this.kind = kind;
    }

    /** Parses a schedule; a mistake throws {@link IllegalArgumentException} saying what is accepted. */
    public static SubscriptionSchedule parse(String raw) {
        String t = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        String low = t.toLowerCase(Locale.ROOT);
        Matcher a = AFTER.matcher(t);
        if (a.matches()) {
            return new SubscriptionSchedule(t, Mode.LOAD, null, null, null, null, false, a.group(1));
        }
        Matcher w = WEEKLY.matcher(low);
        if (w.matches()) {
            DayOfWeek d = DayOfWeek.of(java.util.Arrays.asList(DAYS).indexOf(w.group(1)) + 1);
            return new SubscriptionSchedule(low, Mode.WEEKLY, null, d, null, LocalTime.of(Integer.parseInt(w.group(2)), Integer.parseInt(w.group(3))), w.group(4) != null, null);
        }
        Matcher m = MONTHLY.matcher(low);
        if (m.matches()) {
            return new SubscriptionSchedule(low, Mode.MONTHLY, null, null, m.group(1), LocalTime.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))), m.group(4) != null, null);
        }
        try {
            return new SubscriptionSchedule(t, Mode.TIMED, ReportSchedule.parse(t), null, null, null, false, null);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a schedule: use daily HH:MM, weekdays HH:MM, business-days HH:MM, weekly mon HH:MM [business], "
                    + "monthly 1|last HH:MM [business], hourly, cron <six fields>, or after-load [kind]");
        }
    }

    /** True for {@code after-load}: the subscription waits for a data load rather than a clock. */
    public boolean isAfterLoad() {
        return mode == Mode.LOAD;
    }

    /** For {@code after-load KIND}: the kind that must have loaded; null for any load. */
    public String loadKind() {
        return kind;
    }

    /** The next run strictly after {@code after}; null for {@code after-load} (an event decides) or when there is none. */
    public ZonedDateTime next(ZonedDateTime after, ZoneId zone, BusinessCalendar calendar) {
        ZonedDateTime from = after.withZoneSameInstant(zone);
        return switch (mode) {
            case LOAD -> null;
            case TIMED -> timed.next(from, zone, calendar);
            case WEEKLY -> weekly(from, zone, calendar);
            case MONTHLY -> monthly(from, zone, calendar);
        };
    }

    private ZonedDateTime weekly(ZonedDateTime from, ZoneId zone, BusinessCalendar calendar) {
        LocalDate d = from.toLocalDate().minusDays(10);
        for (int i = 0; i < 430; i++, d = d.plusDays(1)) {
            if (d.getDayOfWeek() == weekday) {
                ZonedDateTime at = d.atTime(time).atZone(zone);
                if (business && !calendar.isBusinessDay(d)) {
                    at = calendar.next(d).atTime(time).atZone(zone);
                }
                if (at.isAfter(from)) {
                    return at;
                }
            }
        }
        return null;
    }

    private ZonedDateTime monthly(ZonedDateTime from, ZoneId zone, BusinessCalendar calendar) {
        YearMonth ym = YearMonth.from(from.toLocalDate().minusDays(40));
        for (int i = 0; i < 20; i++, ym = ym.plusMonths(1)) {
            LocalDate d;
            if (day.equals("last")) {
                d = ym.atEndOfMonth();
                if (business) {
                    d = calendar.onOrBefore(d);
                }
            } else {
                d = ym.atDay(Math.min(Integer.parseInt(day), ym.lengthOfMonth()));
                if (business && !calendar.isBusinessDay(d)) {
                    d = calendar.next(d);
                }
            }
            ZonedDateTime at = d.atTime(time).atZone(zone);
            if (at.isAfter(from)) {
                return at;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return text;
    }
}
