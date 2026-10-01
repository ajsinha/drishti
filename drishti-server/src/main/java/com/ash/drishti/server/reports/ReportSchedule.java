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
package com.ash.drishti.server.reports;

import com.ash.drishti.common.BusinessCalendar;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.scheduling.support.CronExpression;

/**
 * When a report runs, written the way people say it: {@code daily 07:00}, {@code weekdays 18:30},
 * {@code business-days 18:30} (weekdays that are not holidays in the server's calendar), {@code hourly}, or a Spring
 * cron expression after {@code cron} ({@code cron 0 0 8,12,16 * * MON-FRI}). Times are in the business-date zone.
 */
public final class ReportSchedule {

    private static final Pattern AT = Pattern.compile("(daily|weekdays|business-days)\\s+([01]?\\d|2[0-3]):([0-5]\\d)");
    private final String text;
    private final CronExpression cron;
    private final boolean businessDaysOnly;

    private ReportSchedule(String text, CronExpression cron, boolean businessDaysOnly) {
        this.text = text;
        this.cron = cron;
        this.businessDaysOnly = businessDaysOnly;
    }

    /** Parses a schedule; a mistake throws {@link IllegalArgumentException} with what is accepted. */
    public static ReportSchedule parse(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (t.equals("hourly")) {
            return new ReportSchedule(t, CronExpression.parse("0 0 * * * *"), false);
        }
        Matcher m = AT.matcher(t);
        if (m.matches()) {
            String days = m.group(1).equals("daily") ? "*" : "MON-FRI";
            return new ReportSchedule(t, CronExpression.parse("0 " + Integer.parseInt(m.group(3)) + " " + Integer.parseInt(m.group(2)) + " * * " + days),
                    m.group(1).equals("business-days"));
        }
        if (t.startsWith("cron ")) {
            try {
                return new ReportSchedule(t, CronExpression.parse(text.trim().substring(5).trim()), false);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + text + "' is not a cron expression (six fields: second minute hour day month weekday): "
                        + e.getMessage());
            }
        }
        throw new IllegalArgumentException("'" + text + "' is not a schedule: use daily HH:MM, weekdays HH:MM, business-days HH:MM, hourly, "
                + "or cron <six fields>");
    }

    /** The next run strictly after {@code after}, skipping holidays for {@code business-days}; null when there is none. */
    public ZonedDateTime next(ZonedDateTime after, ZoneId zone, BusinessCalendar calendar) {
        ZonedDateTime t = after.withZoneSameInstant(zone);
        for (int i = 0; i < 400; i++) {
            t = cron.next(t);
            if (t == null) {
                return null;
            }
            if (!businessDaysOnly || calendar.isBusinessDay(t.toLocalDate())) {
                return t;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return text;
    }
}
