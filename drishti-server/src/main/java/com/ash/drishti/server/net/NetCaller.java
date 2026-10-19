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
package com.ash.drishti.server.net;

import com.ash.drishti.server.security.Principal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Who the request being served is for, where the source router and the peer sources can see it (DRISHTI_NET.md section 7): the user
 * (whose roles choose the nodes a read may go to, and who the on-behalf-of token names), whether another node forwarded the request
 * (it is then answered from this node only: one hop), and the request's id. Set by a filter after the token filter for every API
 * request and inherited by the virtual threads the request starts, cleared when it ends.
 */
public final class NetCaller {

    /** One request's caller. {@code fromPeer} is the forwarding node's id, or null for a request of this node's own client. */
    public record Caller(Principal principal, String fromPeer, String requestId) {
        public boolean forwarded() {
            return fromPeer != null;
        }
    }

    private static final InheritableThreadLocal<Caller> CURRENT = new InheritableThreadLocal<>();

    private NetCaller() {
    }

    public static Optional<Caller> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** True while serving a request another node forwarded: peer-only packs are served, and nothing is forwarded again. */
    public static boolean fromPeer() {
        Caller c = CURRENT.get();
        return c != null && c.forwarded();
    }

    /** Runs {@code r} with {@code c} as the caller (a test, a background task acting for someone), restoring the previous one. */
    public static void with(Caller c, Runnable r) {
        Caller before = CURRENT.get();
        CURRENT.set(c);
        try {
            r.run();
        } finally {
            CURRENT.set(before);
        }
    }

    /** The filter that sets the caller: after the token filter (the principal is known), before the forwarding filter. */
    public static final class Filter extends OncePerRequestFilter {
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/api/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            Principal p = req.getAttribute(Principal.ATTRIBUTE) instanceof Principal x ? x : null;
            String peer = req.getAttribute(NetCallFilter.PEER) instanceof String s ? s : null;
            String id = req.getHeader(com.ash.drishti.net.security.NetHeaders.REQUEST);
            if (id == null || peer == null) {
                id = UUID.randomUUID().toString().substring(0, 13);
            }
            Caller before = CURRENT.get();
            CURRENT.set(new Caller(p, peer, id));
            try {
                chain.doFilter(req, res);
            } finally {
                CURRENT.set(before);
            }
        }
    }
}
