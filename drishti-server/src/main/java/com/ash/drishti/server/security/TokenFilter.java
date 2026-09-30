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

import com.ash.drishti.common.DrishtiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a {@link Principal} on every {@code /api/**} request. With security on, it requires a valid bearer
 * token and answers 401 problem+json otherwise; with security off, the caller is taken from
 * {@code X-Drishti-User} with every role.
 */
public final class TokenFilter extends OncePerRequestFilter {

    private final SecurityProperties props;
    private final TokenVerifier verifier;

    public TokenFilter(SecurityProperties props, TokenVerifier verifier) {
        this.props = props;
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/v1/") || uri.startsWith("/api/docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!props.enabled()) {
            req.setAttribute(Principal.ATTRIBUTE, Principal.anonymous(req.getHeader("X-Drishti-User")));
            chain.doFilter(req, res);
            return;
        }
        String auth = req.getHeader("Authorization");
        try {
            if (auth == null || !auth.startsWith("Bearer ")) {
                throw new DrishtiException(com.ash.drishti.common.ErrorCode.UNAUTHENTICATED, "missing bearer token");
            }
            req.setAttribute(Principal.ATTRIBUTE, verifier.verify(auth.substring(7).trim()));
        } catch (DrishtiException e) {
            res.setStatus(401);
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"title\":\"unauthenticated\",\"status\":401,\"code\":\"" + e.errorCode().code()
                    + "\",\"detail\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
