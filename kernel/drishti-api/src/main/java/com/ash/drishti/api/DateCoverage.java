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
package com.ash.drishti.api;

/**
 * Whether a dated source holds a kind's data for a business date ({@link SourcePlugin#coverage}).
 *
 * <p>A source that {@link #HELD holds} the date is authoritative for it: an entity it does not list for that date is not
 * held, and no later source is asked for it (a recent store that dropped a trade is not overruled by the lake behind it).
 * Only for a date a source does {@link #NOT_HELD not hold} does a read pass to the next source. A source that cannot
 * tell says {@link #UNKNOWN}, and the next source is asked when it does not hold the entity, as before.
 */
public enum DateCoverage {
    /** The source holds the kind for the date: what it does not list for that date is not held. */
    HELD,
    /** The source holds no data of the kind for the date: the next source answers. */
    NOT_HELD,
    /** The source cannot tell (the default). */
    UNKNOWN
}
