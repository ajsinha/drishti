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
package com.ash.drishti.server.placement;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.module.runtime.placement.ModuleParts;
import com.ash.drishti.server.net.NetCallFilter;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.net.PeerCalls;
import com.ash.drishti.server.security.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The heavy-work guard (KERNEL_AND_MODULES.md section 4.11.3): a request for a module's part ({@code /api/v1/<prefix>/...}, the part by
 * its declared paths) runs on this node only when the part is placed here (a one-runner part: when this node owns it). Otherwise it is
 * forwarded, once, to a placed node that is up (the owner of a one-runner part), for the same person, and its answer passed back; with
 * no placed node up it is refused with {@code 503 DRS-1032}: unavailable, never run on an unplaced node. A request a peer forwarded that
 * this node is not placed for is refused the same way (one hop). Every answer says where the part ran ({@code X-Drishti-Placement}).
 * Today's in-process modules (banking) are placed on every node and pass untouched.
 */
public final class PlacementGuardFilter extends OncePerRequestFilter {

    /** The answer header naming the node that ran the request. */
    public static final String HEADER = "X-Drishti-Placement";
    private static final int MAX_BODY = 16 * 1024 * 1024;

    private final PlacementService placement;
    private final PeerCalls peers;

    public PlacementGuardFilter(PlacementService placement, PeerCalls peers) {
        this.placement = placement;
        this.peers = peers;
    }

    private Optional<ModuleParts> module(HttpServletRequest req) {
        String path = RequestPaths.routed(req);
        if (!path.startsWith("/api/v1/")) {
            return Optional.empty();
        }
        String rest = path.substring("/api/v1/".length());
        String prefix = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        return placement.byPrefix(prefix).filter(m -> !m.inProcess());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return module(request).isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        ModuleParts m = module(req).orElseThrow();
        String rest = RequestPaths.routed(req).substring(("/api/v1/" + m.prefix()).length());
        String part = m.partFor(rest).map(ModuleParts.Part::name).orElse("api");
        PlacementService.Route r = placement.route(m.module(), part);
        if (r.here()) {
            res.setHeader(HEADER, placement.self());
            chain.doFilter(req, res);
            return;
        }
        boolean fromPeer = NetCaller.fromPeer() || req.getAttribute(NetCallFilter.PEER) != null;
        Principal p = req.getAttribute(Principal.ATTRIBUTE) instanceof Principal x ? x : null;
        if (r.node() == null || fromPeer || p == null || !peers.clustered()) {
            String why = r.node() == null ? r.reason() : m.module() + "'s " + part + " is not placed on " + placement.self() + " (" + r.reason() + ")";
            NetCallFilter.problem(res, 503, "DRS-1032", why);
            return;
        }
        byte[] body = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            NetCallFilter.problem(res, 413, "DRS-5005", "a forwarded request is at most 16 MB");
            return;
        }
        String path = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        PeerCalls.Answer a = peers.call(r.node(), req.getMethod(), path, body, p, Duration.ofSeconds(60));
        if (a.status() == 0) {
            NetCallFilter.problem(res, 503, "DRS-1032", m.module() + "'s " + part + " is unavailable right now (" + r.node()
                    + " did not answer); try again later");
            return;
        }
        res.setStatus(a.status());
        res.setHeader(HEADER, r.node());
        res.setContentType("application/json");
        res.getOutputStream().write(a.body().getBytes(StandardCharsets.UTF_8));
        res.getOutputStream().flush();
    }
}
