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

import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The business date every read is for.
 *
 * @param calendar business-day calendar that defines the default date ({@code USNY}, or joint {@code USNY+GBLO})
 * @param zone time zone whose calendar date is "today"
 * @param history how far back users may go
 */
@ConfigurationProperties("drishti.business-date")
public record BusinessDateProperties(String calendar, String zone, Period history) {

    public BusinessDateProperties {
        calendar = calendar == null || calendar.isBlank() ? "USNY" : calendar;
        zone = zone == null || zone.isBlank() ? "America/New_York" : zone;
        history = history == null ? Period.ofYears(5) : history;
    }
}
