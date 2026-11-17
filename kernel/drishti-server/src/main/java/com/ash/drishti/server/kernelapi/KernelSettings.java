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

import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.kernel.api.services.PersonSettings;
import com.ash.drishti.server.api.MySettings;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@link PersonSettings} in process: the body of {@code GET /api/v1/me/settings}. */
@Component
public class KernelSettings implements PersonSettings {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final MySettings settings;
    private final KernelDoors doors;
    private final ObjectMapper json;

    public KernelSettings(MySettings settings, KernelDoors doors, ObjectMapper json) {
        this.settings = settings;
        this.doors = doors;
        this.json = json;
    }

    @Override
    public Map<String, Object> of(Person person) {
        RequestPerson p = RequestPerson.of(person);
        return json.convertValue(doors.call(p, "GET", "/api/v1/me/settings", () -> settings.of(p.user())), MAP);
    }
}
