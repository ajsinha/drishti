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
package com.ash.drishti.kernel.api.services;

import com.ash.drishti.api.EntityRef;
import java.util.Optional;

/** Records as a person may see them: what {@code GET /api/v1/search} and {@code GET /api/v1/entities/{kind}/{id}/raw} answer. */
public interface Records {

    /**
     * A search ({@code TRD where CURRENCY = 'EUR' limit 100}) on the person's date, with their row rule joined and their masks applied,
     * across the cluster's shards as the REST path does.
     *
     * @throws Kernel.Refused the kind is closed to them, or switched off in their edition
     */
    SearchAnswer search(Person p, String query);

    /**
     * One record whole on the person's date, masked for them; empty when no source holds it.
     *
     * @throws Kernel.Refused the kind is closed to them
     */
    Optional<RawRecord> raw(Person p, EntityRef ref);
}
