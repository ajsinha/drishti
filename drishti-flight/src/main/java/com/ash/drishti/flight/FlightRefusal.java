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
package com.ash.drishti.flight;

import com.ash.drishti.common.ErrorCode;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.ErrorFlightMetadata;
import org.apache.arrow.flight.FlightRuntimeException;

/**
 * A Flight request refused, with Drishti's error code (API_GUIDE.md, the DRS-56xx codes and the codes the query layer already answers). It
 * reaches the client as a Flight status whose description starts with the code ({@code DRS-5604: ...}) and whose metadata carries
 * {@code drishti-code}, so every client library can show and test it.
 */
public final class FlightRefusal extends RuntimeException {

    /** The metadata key that carries the code. */
    public static final String CODE_KEY = "drishti-code";

    private final transient ErrorCode code;

    public FlightRefusal(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public FlightRefusal(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    /** The Flight status: the gRPC code nearest the HTTP status of the Drishti code. */
    public FlightRuntimeException toFlight() {
        ErrorFlightMetadata meta = new ErrorFlightMetadata();
        meta.insert(CODE_KEY, code.code());
        return status(code.httpStatus()).withDescription(code.code() + ": " + getMessage()).withMetadata(meta).toRuntimeException();
    }

    private static CallStatus status(int http) {
        return switch (http) {
            case 400, 422 -> CallStatus.INVALID_ARGUMENT;
            case 401 -> CallStatus.UNAUTHENTICATED;
            case 403 -> CallStatus.UNAUTHORIZED;
            case 404, 410 -> CallStatus.NOT_FOUND;
            case 409 -> CallStatus.CANCELLED;
            case 421, 502, 503 -> CallStatus.UNAVAILABLE;
            case 429 -> CallStatus.RESOURCE_EXHAUSTED;
            case 504 -> CallStatus.TIMED_OUT;
            default -> CallStatus.INTERNAL;
        };
    }
}
