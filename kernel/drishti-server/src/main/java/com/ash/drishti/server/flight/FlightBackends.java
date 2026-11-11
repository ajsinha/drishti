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
package com.ash.drishti.server.flight;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.flight.FlightRefusal;

/** Shared helpers of the server's Flight forms. */
public final class FlightBackends {

    private FlightBackends() {}

    /**
     * A refusal of the query layer as a Flight refusal: a forbidden read is {@code DRS-5602} (with the layer's own words), a busy refusal
     * {@code DRS-9506}, anything the request got wrong {@code DRS-5603}; the layer's own code is kept in the words.
     */
    public static FlightRefusal refusal(DrishtiException e) {
        ErrorCode c = e.errorCode();
        String words = e.getMessage() + " (" + c.code() + ")";
        if (e instanceof com.ash.drishti.server.admission.BusyRefusal) {
            return new FlightRefusal(ErrorCode.BUSY_FLIGHT, words, e);
        }
        return switch (c.httpStatus()) {
            case 401 -> new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, words, e);
            case 403 -> new FlightRefusal(ErrorCode.FLIGHT_SCOPE, words, e);
            case 503 -> new FlightRefusal(ErrorCode.BUSY_FLIGHT, words, e);
            default -> new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, words, e);
        };
    }
}
