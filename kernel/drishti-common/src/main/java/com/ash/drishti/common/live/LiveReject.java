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
package com.ash.drishti.common.live;

import com.ash.drishti.common.ErrorCode;
import java.util.Locale;
import java.util.Optional;

/**
 * Why a live message was rejected (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 3.4). A reject is never an error on anybody's
 * page: it goes to the feed's reject log, its counters and, when a companion asks, a dead-letter destination. The reason's name is what
 * the log, Health and Admin show; its code is the one in the API reference's error table.
 */
public enum LiveReject {
    NOT_JSON(ErrorCode.LIVE_NOT_JSON),
    NO_KEY(ErrorCode.LIVE_NO_KEY),
    KEY_MISMATCH(ErrorCode.LIVE_KEY_MISMATCH),
    CONTRACT(ErrorCode.LIVE_CONTRACT),
    UNKNOWN_KIND(ErrorCode.LIVE_UNKNOWN_KIND),
    CLOSED_DATE(ErrorCode.LIVE_CLOSED_DATE),
    FUTURE_DATE(ErrorCode.LIVE_FUTURE_DATE),
    PATCH_WITHOUT_BASE(ErrorCode.LIVE_PATCH_WITHOUT_BASE),
    REPLACE_INCOMPLETE(ErrorCode.LIVE_REPLACE_INCOMPLETE),
    NOT_ALLOWED(ErrorCode.LIVE_NOT_ALLOWED),
    TOO_LARGE(ErrorCode.LIVE_TOO_LARGE);

    private final ErrorCode code;

    LiveReject(ErrorCode code) {
        this.code = code;
    }

    /** The error code ({@code DRS-1062} ...). */
    public ErrorCode code() {
        return code;
    }

    /** The reason as the log and the screens write it: {@code not-json}, {@code key-mismatch}, ... */
    public String reason() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The reason of a written name ({@code key-mismatch}), if it is one. */
    public static Optional<LiveReject> of(String reason) {
        if (reason == null) {
            return Optional.empty();
        }
        String n = reason.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (LiveReject r : values()) {
            if (r.name().equals(n)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
