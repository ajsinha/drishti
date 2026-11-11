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
package com.ash.drishti.server.caches;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;

/**
 * A date refused for room (capacity or disk) and remembered ({@link Refusals}): answered at once with the refusal's code and a
 * {@code Retry-After} of the seconds left, without reading the lake again.
 */
public final class RefusedForNow extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final long retryAfterSeconds;

    public RefusedForNow(ErrorCode code, String message, long retryAfterSeconds) {
        super(code, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** The seconds until the date is tried again (the header {@code Retry-After}). */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
