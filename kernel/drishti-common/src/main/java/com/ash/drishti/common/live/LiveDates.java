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
package com.ash.drishti.common.live;

import java.time.LocalDate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The live date (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 5): live rows belong to one business date at a time, the current
 * business date of the server (the same rule as every read: today in {@code drishti.business-date.zone}, rolled back to a business day of
 * its calendar), and it rolls when that date does. A record of the live date is applied; of the next business date, held and applied at
 * the roll; of an earlier date, rejected ({@code closed-date}); of a later one, rejected ({@code future-date}).
 */
public interface LiveDates {

    /** The live date now; null when no dates are checked (a feed outside a server, a test). */
    LocalDate live();

    /** The business date after {@code d}. */
    LocalDate next(LocalDate d);

    /** No dates checked: every record is applied whatever its date. */
    LiveDates NONE = of(() -> null, d -> d.plusDays(1));

    /** The live date from {@code current}, the next business date from {@code next}. */
    static LiveDates of(Supplier<LocalDate> current, UnaryOperator<LocalDate> next) {
        return new LiveDates() {
            @Override
            public LocalDate live() {
                return current.get();
            }

            @Override
            public LocalDate next(LocalDate d) {
                return next.apply(d);
            }
        };
    }
}
