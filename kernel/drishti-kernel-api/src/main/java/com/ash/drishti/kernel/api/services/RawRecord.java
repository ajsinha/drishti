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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import java.util.Objects;

/**
 * One record whole, field for field what {@code GET /api/v1/entities/{kind}/{id}/raw} answers.
 *
 * @param ref        the record
 * @param provenance where and when it was read
 * @param data       the document, masked for the person
 */
public record RawRecord(EntityRef ref, Provenance provenance, DataNode data) {

    /** Refuses a record without its reference or its data. */
    public RawRecord {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(data, "data");
    }
}
