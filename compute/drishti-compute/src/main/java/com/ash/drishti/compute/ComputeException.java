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
package com.ash.drishti.compute;

import java.util.Locale;

/**
 * Why a compute request did not produce a result, in words a panel can show. The {@link Code} says what kind of failure it was; an
 * engine's own exception text is never in the message (it goes to the server's log with the request id), except for a typed
 * {@code ComputeError} the engine raised on purpose ({@link Code#ENGINE}).
 */
public class ComputeException extends RuntimeException {

    /** The kinds of failure. */
    public enum Code {
        /** compute is switched off on this server */
        OFF,
        /** refused before any work: unknown pool or engine, requirements not approved, a bad request */
        REFUSED,
        /** the pool's queue for the request's class is full: try again in a moment */
        BUSY,
        /** the request's wall-time budget passed; the worker was stopped */
        TIMEOUT,
        /** every waiter went away, or someone cancelled it */
        CANCELLED,
        /** the worker process died */
        CRASHED,
        /** the worker passed its memory or CPU limit, or did something its sandbox refuses */
        LIMIT,
        /** the engine refused the request in words (a typed ComputeError) */
        ENGINE,
        /** the engine failed; the details are in the server's log under the request id */
        FAILED,
        /** the worker's answer broke the transport's rules (schema, size, a file outside the scratch folder) */
        INVALID,
        /** no node that serves the pool could be reached */
        UNAVAILABLE;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Code code;
    private final String requestId;

    public ComputeException(Code code, String message) {
        this(code, message, null, null);
    }

    public ComputeException(Code code, String message, String requestId, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.requestId = requestId;
    }

    public Code code() {
        return code;
    }

    public String requestId() {
        return requestId;
    }

    /** The same failure, naming the request it belongs to. */
    public ComputeException forRequest(String id) {
        return requestId != null ? this : new ComputeException(code, getMessage(), id, getCause());
    }
}
