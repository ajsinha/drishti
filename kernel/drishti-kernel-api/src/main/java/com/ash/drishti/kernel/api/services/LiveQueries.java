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

/** A search as a query on a live table: the first page of what {@code GET /api/v1/rdp/searches} answers. */
public interface LiveQueries {

    /**
     * The first {@code limit} rows of a search's live set ({@code TRD where CURRENCY = 'EUR'}) on the person's date, masked for them.
     *
     * @throws Kernel.Refused the kind is closed to them, or switched off in their edition
     */
    LivePage query(Person p, String query, int limit);
}
