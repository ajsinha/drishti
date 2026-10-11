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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.server.alerts.measure.AlertLogic.Reading;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;

/**
 * Reads the number an alert watches, <b>as one person</b>: the dataset engine applies that person's row rules and masks, a Sutra field is read through that
 * person's field masks. A number the person may not see comes back as {@link Reading.Status#MASKED}, never as a value.
 */
@FunctionalInterface
public interface ValueReader {

    /**
     * @param date a business date to read the number on, or null for the latest
     * @return the reading; never throws for a source that cannot be read (that is {@link Reading.Status#ERROR}, or {@link Reading.Status#NO_DATA} when the data
     *     is not there)
     */
    Reading read(AlertSpec spec, Principal who, LocalDate date);
}
