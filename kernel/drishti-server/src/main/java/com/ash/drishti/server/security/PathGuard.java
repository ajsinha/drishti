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
package com.ash.drishti.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before the token filter and the management guard on {@code /api/**} and {@code /actuator/**}: a request whose
 * request line does not spell its path canonically ({@link RequestPaths#nonCanonical}) is answered 400 problem+json
 * ({@code DRS-5001}) whether security is on or off, so every later filter and the dispatcher see one and the same path.
 */
public final class PathGuard extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String uri = req.getRequestURI();
        String context = req.getContextPath();
        String raw = context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
        Optional<String> why = RequestPaths.nonCanonical(raw);
        if (why.isPresent()) {
            res.setStatus(400);
            res.setContentType("application/problem+json");
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("{\"title\":\"bad request\",\"status\":400,\"code\":\"" + com.ash.drishti.common.ErrorCode.BAD_REQUEST.code()
                    + "\",\"detail\":\"" + why.get().replace("\\", "\\\\").replace("\"", "'") + "\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
