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
package com.ash.drishti.server.admission;

import com.ash.drishti.common.DrishtiException;

/**
 * New work asked to wait because the server is very busy (docs/admin/OPERATIONS.md, "Busy protection"): answered {@code 503} with
 * {@code Retry-After} the seconds to wait and the work's code ({@code DRS-9501} to {@code DRS-9505}). The message is the calm sentence the
 * console, the API and {@code drishti.py} all show.
 */
public final class BusyRefusal extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final long retryAfterSeconds;
    private final transient Work work;
    private final String title;
    private final String because;

    BusyRefusal(Work work, String title, String message, String because, long retryAfterSeconds) {
        super(work.code(), message);
        this.work = work;
        this.title = title;
        this.because = because;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** The seconds to wait (the header {@code Retry-After}). */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Work work() {
        return work;
    }

    /** The calm headline ("The server is very busy right now"). */
    public String title() {
        return title;
    }

    /** What is busy, in words ("the server is short of memory: 93% in use after clean-up"). */
    public String because() {
        return because;
    }
}
