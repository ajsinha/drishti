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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.kernel.api.services.Access;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.server.security.Entitlements;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@link Access}: the entitlements the REST paths apply, for what a module reads itself. */
@Component
public class KernelAccess implements Access {

    private final Entitlements entitlements;
    private final KernelDoors doors;

    public KernelAccess(Entitlements entitlements, KernelDoors doors) {
        this.entitlements = entitlements;
        this.doors = doors;
    }

    @Override
    public void requireOpen(Person person, String kind) {
        RequestPerson p = RequestPerson.of(person);
        doors.now(() -> {
            entitlements.requireOpen(p.principal(), kind);
            return null;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> redact(Person person, String kind, Map<String, Object> data) {
        RequestPerson p = RequestPerson.of(person);
        if (data == null) {
            return Map.of();
        }
        Object out = entitlements.redact(p.principal(), DataNode.of(data)).unwrap();
        return out instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
