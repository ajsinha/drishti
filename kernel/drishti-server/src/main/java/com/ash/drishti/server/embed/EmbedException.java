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
package com.ash.drishti.server.embed;

import com.ash.drishti.common.ErrorCode;

/**
 * A refused embed exchange or call. {@code error} is the RFC 6749 section 5.2 name the token endpoint answers with
 * ({@code invalid_client}, {@code invalid_grant}, {@code invalid_target}, ...); {@code retryAfter} is set for a rate refusal.
 */
public final class EmbedException extends RuntimeException {

    private final transient ErrorCode code;
    private final int status;
    private final String error;
    private final long retryAfter;

    public EmbedException(ErrorCode code, int status, String error, String detail) {
        this(code, status, error, detail, 0);
    }

    public EmbedException(ErrorCode code, int status, String error, String detail, long retryAfter) {
        super(detail, null, false, false);
        this.code = code;
        this.status = status;
        this.error = error;
        this.retryAfter = retryAfter;
    }

    public ErrorCode errorCode() {
        return code;
    }

    public int status() {
        return status;
    }

    public String error() {
        return error;
    }

    /** Seconds until a refused call may be retried, or 0. */
    public long retryAfter() {
        return retryAfter;
    }
}
