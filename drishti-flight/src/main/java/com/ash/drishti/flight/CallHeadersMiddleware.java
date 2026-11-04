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

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallInfo;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.RequestContext;

/**
 * Keeps a call's headers for the producer: Flight SQL reads the model a statement asks from the header {@code model} (the Flight SQL JDBC
 * driver sends every connection property it does not know as a header, so {@code jdbc:arrow-flight-sql://host:18815/?model=risk-summary}
 * names it).
 */
public final class CallHeadersMiddleware implements FlightServerMiddleware {

    public static final Key<CallHeadersMiddleware> KEY = Key.of("drishti-call-headers");

    private final CallHeaders headers;

    private CallHeadersMiddleware(CallHeaders headers) {
        this.headers = headers;
    }

    /** A header of the call, or null. */
    public String get(String name) {
        return headers == null ? null : headers.get(name);
    }

    /** A header of the call the context belongs to, or null (no middleware, no header). */
    public static String header(FlightProducer.CallContext context, String name) {
        CallHeadersMiddleware m = context == null ? null : context.getMiddleware(KEY);
        return m == null ? null : m.get(name);
    }

    @Override
    public void onBeforeSendingHeaders(CallHeaders outgoing) {
        // nothing to add
    }

    @Override
    public void onCallCompleted(CallStatus status) {
        // nothing to do
    }

    @Override
    public void onCallErrored(Throwable err) {
        // nothing to do
    }

    /** One middleware per call. */
    public static final class Factory implements FlightServerMiddleware.Factory<CallHeadersMiddleware> {
        @Override
        public CallHeadersMiddleware onCallStarted(CallInfo info, CallHeaders incoming, RequestContext context) {
            return new CallHeadersMiddleware(incoming);
        }
    }
}
