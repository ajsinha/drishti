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
package com.ash.drishti.server.collab;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;

/** Over a collaboration rate limit: {@code 429 DRS-7003} with a {@code Retry-After} header. */
public final class RateLimitedException extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final long retryAfterSeconds;

    public RateLimitedException(String what, long retryAfterSeconds) {
        super(ErrorCode.COLLAB_RATE_LIMITED, "too many " + what + "; try again in " + retryAfterSeconds + " s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
