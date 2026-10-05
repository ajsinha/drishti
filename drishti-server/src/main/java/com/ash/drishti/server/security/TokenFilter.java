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

    /** A verified personal token: who it stands for, its id for the audit trail and its scopes. */
    public record Grant(String tokenId, Principal principal, java.util.List<String> scopes) {}

    private final SecurityProperties props;
    private final TokenVerifier verifier;
    private final TokenScopes scopes;
    /** Personal API tokens ({@code drk_…}): the grant they stand for, if any. */
    private final java.util.function.Function<String, java.util.Optional<Grant>> apiTokens;
    /** Whether the account behind a signed token still exists and is enabled (a token outlives a deleted or disabled user otherwise). */
    private final java.util.function.Predicate<Principal> account;
    /** Where a write (or a refused attempt) done with a personal token is recorded. */
    private final com.ash.drishti.identity.AuditLog audit;

    public TokenFilter(SecurityProperties props, TokenVerifier verifier) {
        this(props, verifier, t -> java.util.Optional.empty(), p -> true, null);
    }

    public TokenFilter(SecurityProperties props, TokenVerifier verifier, java.util.function.Function<String, java.util.Optional<Grant>> apiTokens,
            java.util.function.Predicate<Principal> account, com.ash.drishti.identity.AuditLog audit) {
        this.account = account;
        this.props = props;
        this.verifier = verifier;
        this.apiTokens = apiTokens;
        this.scopes = new TokenScopes(props);
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // the path that is served, not the raw request line (SEC-02): /api/v1;x/… and /api/%761/… are /api/v1/…
        return !RequestPaths.routed(request).startsWith("/api/v1/");
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
            String bearer = auth.substring(7).trim();
            if (com.ash.drishti.identity.ApiTokenStore.looksLikeToken(bearer)) {
                // a personal API token: it acts as its user, and only within the scopes it was given
                Grant g = apiTokens.apply(bearer).orElseThrow(() -> new DrishtiException(
                        com.ash.drishti.common.ErrorCode.UNAUTHENTICATED, "API token unknown, revoked or expired"));
                String method = req.getMethod();
                String path = RequestPaths.routed(req);
                TokenScopes.Verdict v = scopes.check(g.scopes(), method, path);
                if (v != TokenScopes.Verdict.ALLOWED) {
                    record(g, "token-denied", method, path, 403);
                    java.util.List<String> opening = scopes.opening(method, path);
                    String detail = v == TokenScopes.Verdict.NEVER ? "API tokens may not call this endpoint"
                            : opening.isEmpty() ? "API tokens only read"
                            : "this token lacks the scope " + String.join(" or ", opening);
                    res.setStatus(403);
                    res.setContentType("application/problem+json");
                    res.getWriter().write("{\"title\":\"forbidden\",\"status\":403,\"code\":\"DRS-5002\",\"detail\":\"" + detail + "\"}");
                    return;
                }
                req.setAttribute(Principal.ATTRIBUTE, g.principal());
                boolean write = !scopes.reads(method, path);
                try {
                    chain.doFilter(req, res);
                } finally {
                    if (write) {
                        record(g, "token-write", method, path, res.getStatus());
                    }
                }
                return;
            }
            Principal signed = verifier.verify(bearer);
            if (!account.test(signed)) {
                throw new DrishtiException(com.ash.drishti.common.ErrorCode.UNAUTHENTICATED, "the account of this token does not exist or is disabled");
            }
            req.setAttribute(Principal.ATTRIBUTE, signed);
        } catch (DrishtiException e) {
            res.setStatus(401);
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"title\":\"unauthenticated\",\"status\":401,\"code\":\"" + e.errorCode().code()
                    + "\",\"detail\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private void record(Grant g, String action, String method, String path, int status) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(g.principal().user(), action, path, "token " + g.tokenId() + " " + method + " " + path + " -> " + status);
        } catch (RuntimeException e) {
            logger.warn("could not audit a token write: " + e.getMessage());
        }
    }
}
