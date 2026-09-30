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

import java.time.Instant;
import java.time.LocalDate;

/**
 * The point in time a read is for. {@code businessDate} is the business day whose data is wanted (end-of-day
 * books, curves and risk as of that date); {@code knownAt}, when set, asks for that data as it was known at an
 * instant, before later corrections (bitemporal time travel, for sources that keep versions such as Delta Lake).
 * {@code live} means the user asked for live data: the current business date, streaming. A date the user picks
 * is a static snapshot, even when it is today.
 *
 * <p>Sources that are not dated ({@link SourceCapabilities#dated()} false) ignore it and return what they have.
 * Dated sources always receive a concrete {@code businessDate}; {@link #LATEST} before resolution means "live".
 *
 * @param businessDate the business date, or {@code null} before the server has resolved "live" to today's
 * @param knownAt the knowledge time, or {@code null} for the latest version
 * @param live the user asked for live data (the view streams)
 */
public record AsOf(LocalDate businessDate, Instant knownAt, boolean live) {

    public static final AsOf LATEST = new AsOf(null, null, true);

    /** A picked date: static. */
    public AsOf(LocalDate businessDate, Instant knownAt) {
        this(businessDate, knownAt, false);
    }

    public static AsOf of(LocalDate businessDate) {
        return new AsOf(businessDate, null, false);
    }

    /** True when this is live data (the current business date, latest version): views stream. */
    public boolean isLatest() {
        return live && knownAt == null;
    }

    /** The business date to read, given the current one. */
    public LocalDate dateOr(LocalDate current) {
        return businessDate != null ? businessDate : current;
    }

    @Override
    public String toString() {
        return (live ? "live" : "") + (businessDate == null ? "" : (live ? " " : "") + businessDate) + (knownAt == null ? "" : "@" + knownAt);
    }
}
