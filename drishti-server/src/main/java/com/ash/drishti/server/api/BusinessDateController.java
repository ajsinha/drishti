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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.engine.time.BusinessDates;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The business date: what "today" is, how far back users may go, and the holidays to grey out in the picker. */
@RestController
@RequestMapping("/api/v1")
public class BusinessDateController {

    private final BusinessDates dates;

    public BusinessDateController(BusinessDates dates) {
        this.dates = dates;
    }

    /**
     * {@code current} is today's business date; {@code selected} is the date this request resolves to (from the
     * {@code X-Drishti-As-Of} header or {@code asOf}), rolled back to a business day.
     */
    @GetMapping("/business-date")
    public Map<String, Object> businessDate(AsOf asOf) {
        LocalDate current = dates.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", current.toString());
        out.put("selected", asOf.businessDate().toString());
        out.put("live", dates.isCurrent(asOf));
        out.put("knownAt", asOf.knownAt() == null ? null : asOf.knownAt().toString());
        out.put("previous", dates.calendar().previous(current).toString());
        out.put("earliest", dates.earliest().toString());
        out.put("calendar", dates.calendar().name());
        out.put("zone", dates.zone().getId());
        out.put("holidays", dates.holidays(dates.earliest(), current.plusYears(1)).stream().map(LocalDate::toString).toList());
        return out;
    }
}
