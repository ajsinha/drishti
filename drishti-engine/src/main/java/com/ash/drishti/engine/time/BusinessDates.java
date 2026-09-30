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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Turns what a user asked for into the concrete business date every source read uses. The default is today in
 * the configured zone, rolled back to a business day of the configured calendar (weekends and holidays roll to
 * the previous business day). A requested date is rolled back the same way; dates in the future or beyond the
 * history window are refused. Thread-safe.
 */
public final class BusinessDates {

    private final BusinessCalendar calendar;
    private final ZoneId zone;
    private final BusinessDateProperties props;
    private final Clock clock;

    public BusinessDates(BusinessDateProperties props, Clock clock) {
        this.props = props;
        this.calendar = BusinessCalendar.of(props.calendar());
        this.zone = ZoneId.of(props.zone());
        this.clock = clock;
    }

    public BusinessCalendar calendar() {
        return calendar;
    }

    /** Today's business date. */
    public LocalDate current() {
        return calendar.onOrBefore(LocalDate.now(clock.withZone(zone)));
    }

    public LocalDate earliest() {
        return calendar.onOrBefore(current().minus(props.history()));
    }

    /**
     * The concrete as-of for a request: a business date always set, rolled back to a business day. Live stays live
     * (today's date); a picked date stays a static snapshot.
     */
    public AsOf resolve(AsOf asked) {
        if (asked == null || asked.businessDate() == null) {
            return new AsOf(current(), asked == null ? null : asked.knownAt(), asked == null || asked.knownAt() == null);
        }
        LocalDate d = asked.businessDate();
        if (d.isAfter(LocalDate.now(clock.withZone(zone)))) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "business date " + d + " is in the future");
        }
        if (d.isBefore(earliest())) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "business date " + d + " is before the history window (" + earliest() + ")");
        }
        return new AsOf(calendar.onOrBefore(d), asked.knownAt(), false);
    }

    /** Parses a request value: {@code 2026-09-30} (a static snapshot), or blank / {@code live} for live data. */
    public AsOf parse(String date, String knownAt) {
        try {
            boolean live = date == null || date.isBlank() || date.trim().equalsIgnoreCase("live");
            LocalDate d = live ? null : LocalDate.parse(date.trim());
            java.time.Instant k = knownAt == null || knownAt.isBlank() ? null : java.time.Instant.parse(knownAt.trim());
            return resolve(new AsOf(d, k));
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "cannot read business date '" + date + "' (use yyyy-MM-dd)");
        }
    }

    /** True when {@code asOf} is live data: views stream only then. A picked date, even today, is static. */
    public boolean isCurrent(AsOf asOf) {
        return asOf == null || asOf.isLatest();
    }

    /** Holidays between two dates (weekdays only), for the date picker. */
    public List<LocalDate> holidays(LocalDate from, LocalDate to) {
        java.util.TreeSet<LocalDate> out = new java.util.TreeSet<>();
        for (int y = from.getYear(); y <= to.getYear(); y++) {
            calendar.holidays(y).stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).forEach(out::add);
        }
        return List.copyOf(out);
    }
}
