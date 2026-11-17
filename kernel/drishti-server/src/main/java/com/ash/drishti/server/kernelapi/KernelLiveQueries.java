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

import com.ash.drishti.kernel.api.services.LivePage;
import com.ash.drishti.kernel.api.services.LiveQueries;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.server.liveset.LivePages;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * {@link LiveQueries} in process: the body of {@code GET /api/v1/live/query}, the search read afresh ({@code again=true}) as the landings
 * ask, its first page.
 */
@Component
public class KernelLiveQueries implements LiveQueries {

    private final LivePages pages;
    private final KernelDoors doors;
    private final ObjectMapper json;

    public KernelLiveQueries(LivePages pages, KernelDoors doors, ObjectMapper json) {
        this.pages = pages;
        this.doors = doors;
        this.json = json;
    }

    @Override
    public LivePage query(Person person, String query, int limit) {
        RequestPerson p = RequestPerson.of(person);
        return json.convertValue(doors.call(p, "GET", "/api/v1/live/query",
                () -> pages.query(query, 0, limit, null, false, null, true, p.asOf(), p.principal())), LivePage.class);
    }
}
