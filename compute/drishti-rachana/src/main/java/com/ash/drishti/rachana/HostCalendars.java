/*
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary: see LICENSE.
 */
package com.ash.drishti.rachana;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.rachana.el.Calendars;
import java.time.LocalDate;

/** G4 rehearsal adapter: Drishti's {@link BusinessCalendar} as Core's {@link Calendars.Calendar} (the host calendar hook, SPLIT_PLAN 8.5). */
public final class HostCalendars {
    private HostCalendars() {
    }

    public static Calendars.Calendar core(BusinessCalendar c) {
        return c == null ? null : new Calendars.Calendar() {
            public String name() { return c.name(); }
            public boolean isBusinessDay(LocalDate d) { return c.isBusinessDay(d); }
            public LocalDate previous(LocalDate d) { return c.previous(d); }
            public LocalDate next(LocalDate d) { return c.next(d); }
        };
    }
}
