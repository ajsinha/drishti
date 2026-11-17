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
package com.ash.drishti.engine.time;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The business date every read is for.
 *
 * @param calendar business-day calendar that defines the default date ({@code USNY}, or joint {@code USNY+GBLO})
 * @param zone time zone whose calendar date is "today"
 * @param history how far back users may go
 * @param today a fixed "today" ({@code yyyy-MM-dd}) in place of the clock's, for a site whose data ends on a known date (a frozen demo, a
 *              test lake built to a fixed date): live reads are for it and later dates are in the future; blank: the clock's
 */
@ConfigurationProperties("drishti.business-date")
public record BusinessDateProperties(String calendar, String zone, Period history, String today) {

    public BusinessDateProperties {
        calendar = calendar == null || calendar.isBlank() ? "USNY" : calendar;
        zone = zone == null || zone.isBlank() ? "America/New_York" : zone;
        history = history == null ? Period.ofYears(5) : history;
        today = today == null || today.isBlank() ? null : today.strip();
        if (today != null) {
            try {
                LocalDate.parse(today);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("drishti.business-date.today '" + today + "' is not a date: use yyyy-MM-dd, or leave it blank");
            }
        }
    }

    /** The fixed "today", or null for the clock's. */
    public LocalDate fixedToday() {
        return today == null ? null : LocalDate.parse(today);
    }
}
