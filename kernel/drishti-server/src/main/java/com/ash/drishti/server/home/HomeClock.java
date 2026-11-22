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
package com.ash.drishti.server.home;

import com.ash.drishti.engine.time.BusinessDates;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** The reference cut-off: the previous business day at the Home's cut-off time in the site's zone (ROLE_HOME_SUTRAS.md 4.1). */
public final class HomeClock {

    private final BusinessDates dates;
    private final java.time.Clock clock;

    public HomeClock(BusinessDates dates, java.time.Clock clock) {
        this.dates = dates;
        this.clock = clock;
    }

    public LocalDate today() {
        return dates.current();
    }

    /** The previous business day. */
    public LocalDate previous() {
        return dates.calendar().onOrBefore(today().minusDays(1));
    }

    public ZoneId zone() {
        return dates.zone();
    }

    public Instant cutoff(LocalDate day, LocalTime at) {
        return day.atTime(at).atZone(zone()).toInstant();
    }

    public Instant now() {
        return clock.instant();
    }

    /** "yesterday 17:00" or "Fri 17:00": the cut-off in words for the panel's title. */
    public String words(LocalTime at) {
        LocalDate prev = previous();
        String day = prev.equals(today().minusDays(1)) ? "yesterday" : prev.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH);
        return day + " " + at;
    }
}
