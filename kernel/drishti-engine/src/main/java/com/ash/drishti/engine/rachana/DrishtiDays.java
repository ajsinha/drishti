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
package com.ash.drishti.engine.rachana;

import com.ash.drishti.engine.time.BusinessDates;
import com.ash.rachana.bind.AsOf;
import com.ash.rachana.control.BusinessDays;
import com.ash.rachana.el.BusinessCalendar;
import java.time.LocalDate;

/** Rachana's {@link BusinessDays} over Drishti's {@link BusinessDates}: today is {@code resolve(asOf).businessDate()}, the calendar the same. */
public record DrishtiDays(BusinessDates dates) implements BusinessDays {

    @Override
    public LocalDate today(AsOf asOf) {
        return dates.resolve(asOf == null ? com.ash.drishti.api.AsOf.LATEST : Hosted.asOf(asOf)).businessDate();
    }

    @Override
    public BusinessCalendar calendar() {
        return BusinessCalendar.of(dates.calendar().name());
    }
}
