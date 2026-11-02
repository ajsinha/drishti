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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The requests being answered now, for busy protection's "request handlers busy" (docs/admin/OPERATIONS.md, "Busy protection"). A
 * streamed answer (an event stream) leaves the count when its handler returns, so a long-lived stream does not count as busy.
 */
public final class RequestsInFlight extends OncePerRequestFilter {

    private final AtomicInteger inFlight = new AtomicInteger();

    public AtomicInteger counter() {
        return inFlight;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        inFlight.incrementAndGet();
        try {
            chain.doFilter(req, res);
        } finally {
            inFlight.decrementAndGet();
        }
    }
}
