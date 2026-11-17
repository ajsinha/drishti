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
package com.ash.drishti.server.kernelapi;

import com.ash.drishti.kernel.api.services.BusinessDates;
import com.ash.drishti.kernel.api.services.Person;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * {@link BusinessDates} in process: what {@code GET /api/v1/business-date} answers as {@code current}, {@code selected} and {@code live},
 * from the same calendar ({@code drishti.business-date.*}, a fixed {@code today} included).
 */
@Component
public class KernelDates implements BusinessDates {

    private final com.ash.drishti.engine.time.BusinessDates dates;

    public KernelDates(com.ash.drishti.engine.time.BusinessDates dates) {
        this.dates = dates;
    }

    @Override
    public LocalDate current() {
        return dates.current();
    }

    @Override
    public java.util.Set<LocalDate> holidays() {
        return new java.util.LinkedHashSet<>(dates.holidays(dates.earliest(), dates.current().plusYears(1)));
    }

    @Override
    public LocalDate selected(Person p) {
        return RequestPerson.of(p).asOf().businessDate();
    }

    @Override
    public boolean live(Person p) {
        return dates.isCurrent(RequestPerson.of(p).asOf());
    }
}
