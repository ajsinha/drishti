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
import java.time.LocalDate;

/** A field of a record over business days: what {@code GET /api/v1/history/{kind}/{id}/series} answers. */
public interface History {

    /**
     * One field's value on each of the last {@code days} business days up to {@code to} (null: the person's date), each day masked for the
     * person; {@code path} is a Rachana-EL expression over the document ({@code $.mtm}, {@code legs[0].rate}).
     *
     * @throws Kernel.Refused the kind is closed to them, or the path does not read
     */
    Series series(Person p, EntityRef ref, String path, int days, LocalDate to);
}
