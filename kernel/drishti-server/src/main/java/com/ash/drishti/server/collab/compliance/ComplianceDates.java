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
package com.ash.drishti.server.collab.compliance;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Reads the {@code from} and {@code to} of a hold, a search or an export: an ISO instant, or a date (whole day, UTC). */
final class ComplianceDates {

    private ComplianceDates() {}

    /** The start of the range: a date means 00:00 UTC of that day. Null or blank is no bound. */
    static Instant from(String text) {
        return parse(text, false);
    }

    /** The end of the range: a date means the last millisecond of that day (UTC), so a day is one {@code from=to=date}. */
    static Instant to(String text) {
        return parse(text, true);
    }

    private static Instant parse(String text, boolean end) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip();
        try {
            if (t.length() <= 10) {
                LocalDate d = LocalDate.parse(t);
                return end ? d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1) : d.atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            return Instant.parse(t);
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "\"" + t + "\" is not a date (2026-09-30) or an instant (2026-09-30T18:00:00Z)");
        }
    }

    static void requireOrdered(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "from is after to");
        }
    }
}
