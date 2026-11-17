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

import java.time.LocalDate;

/** The business dates: what {@code GET /api/v1/business-date} answers that a module reads. */
public interface BusinessDates {

    /** Today's business date. */
    LocalDate current();

    /** The date the person's date box resolves to, rolled back to a business day. */
    LocalDate selected(Person p);

    /** Whether the person looks at the live date (their date box is today's business date with no known-at). */
    boolean live(Person p);
}
