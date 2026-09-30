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
 * What a source can do beyond fetching.
 *
 * @param live the source can push updates through {@link SourcePlugin#subscribe}
 * @param reverseLookup the source answers {@link SourcePlugin#reverse} (for example netting set to trades)
 * @param search the source answers {@link SourcePlugin#search} for command-line suggestions
 * @param dated the source holds data per business date and honours {@link AsOf} (Delta Lake, dated folders,
 *     a {@code business_date} column); undated sources return the same data for every date
 */
public record SourceCapabilities(boolean live, boolean reverseLookup, boolean search, boolean dated) {

    public static final SourceCapabilities FETCH_ONLY = new SourceCapabilities(false, false, false, false);

    /** An undated source. */
    public SourceCapabilities(boolean live, boolean reverseLookup, boolean search) {
        this(live, reverseLookup, search, false);
    }
}
