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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * With security on, the operational endpoints are not open to anyone who can reach the server:
 * <ul>
 *   <li>{@code /actuator/health/**} stays open (load balancers and Kubernetes probes);</li>
 *   <li>the rest of {@code /actuator} (metrics, Prometheus, info) needs an admin token, or the scrape token
 *       {@code drishti.security.metrics-token} ({@code DRISHTI_METRICS_TOKEN}) as a bearer token;</li>
 *   <li>the API description ({@code /api/docs}) needs any valid token.</li>
 * </ul>
 * With security off (local development) everything is open, as before.
 */
public final class ManagementGuard extends OncePerRequestFilter {

    private final SecurityProperties props;
    private final TokenVerifier verifier;
    private final byte[] metricsToken;

    public ManagementGuard(SecurityProperties props, TokenVerifier verifier, String metricsToken) {
        this.props = props;
        this.verifier = verifier;
        this.metricsToken = metricsToken == null || metricsToken.isBlank() ? null : metricsToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !props.enabled() || uri.equals("/actuator/health") || uri.startsWith("/actuator/health/")
                || !(uri.startsWith("/actuator") || uri.startsWith("/api/docs"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String auth = req.getHeader("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        boolean docs = req.getRequestURI().startsWith("/api/docs");
        if (token != null) {
            if (!docs && metricsToken != null && MessageDigest.isEqual(metricsToken, token.getBytes(StandardCharsets.UTF_8))) {
                chain.doFilter(req, res);
                return;
            }
            try {
                Principal p = verifier.verify(token);
                if (docs || p.roles().contains("admin")) {
                    chain.doFilter(req, res);
                    return;
                }
            } catch (RuntimeException e) {
                // falls through to the refusal
            }
        }
        res.setStatus(token == null ? 401 : 403);
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"title\":\"" + (token == null ? "unauthenticated" : "forbidden") + "\",\"status\":" + res.getStatus()
                + ",\"detail\":\"" + (docs ? "the API description needs a token" : "operational endpoints need an admin token or the metrics token")
                + "\"}");
    }
}
