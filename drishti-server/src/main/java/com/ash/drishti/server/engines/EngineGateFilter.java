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
package com.ash.drishti.server.engines;

import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RequestPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * An engine's API on this node (docs/guides/ADMIN_GUIDE.md sections 5.1 and 9): a request under an engine's path prefixes
 * ({@code /api/v1/frtb/**}) is answered here when the engine runs here (and counted, so turning it off drains it); else, in a cluster, it is
 * forwarded whole to a node that runs it (the least loaded, signed, for the caller), and its answer passed back; else {@code 404 DRS-1020} in
 * words. A request another node forwarded is answered here only (one hop).
 */
public final class EngineGateFilter extends OncePerRequestFilter {

    private static final Set<String> DROP = Set.of("connection", "keep-alive", "transfer-encoding", "content-length", "date", "server", "upgrade", "vary");
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Where a request for an engine that does not run here may go: a node running it, and the call. */
    public interface Peers {
        /** A node that runs the engine now, the least loaded, or empty. */
        Optional<String> runs(String engine);

        /** Whether the request came from another node (it is answered here only). */
        boolean fromPeer(HttpServletRequest request);

        /** One forwarded call; IOException when the node cannot be reached. */
        PeerHttp.Answer call(String node, String method, String pathAndQuery, byte[] body, Principal p) throws IOException;

        /** No cluster: nothing is forwarded. */
        Peers NONE = new Peers() {
            @Override
            public Optional<String> runs(String engine) {
                return Optional.empty();
            }

            @Override
            public boolean fromPeer(HttpServletRequest request) {
                return false;
            }

            @Override
            public PeerHttp.Answer call(String node, String method, String pathAndQuery, byte[] body, Principal p) throws IOException {
                throw new IOException("no cluster");
            }
        };
    }

    private final EngineRegistry registry;
    private final Peers peers;

    public EngineGateFilter(EngineRegistry registry, Peers peers) {
        this.registry = registry;
        this.peers = peers == null ? Peers.NONE : peers;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.routed(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Optional<EngineCatalogue.Entry> found = registry.catalogue().forPath(RequestPaths.routed(req));
        if (found.isEmpty()) {
            chain.doFilter(req, res);
            return;
        }
        String id = found.get().id();
        if (registry.running(id)) {
            AutoCloseable counted;
            try {
                counted = registry.enter(id);
            } catch (EngineOffException e) {
                off(res, id);
                return;
            }
            try {
                chain.doFilter(req, res);
            } finally {
                try {
                    counted.close();
                } catch (Exception ignored) {
                    // a counter's decrement does not fail
                }
            }
            return;
        }
        Principal p = req.getAttribute(Principal.ATTRIBUTE) instanceof Principal x ? x : null;
        Optional<String> node = p == null || peers.fromPeer(req) ? Optional.empty() : peers.runs(id);
        if (node.isEmpty()) {
            off(res, id);
            return;
        }
        byte[] body = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            problem(res, 413, "DRS-5005", "a forwarded request is at most 16 MB", null);
            return;
        }
        String path = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        PeerHttp.Answer a;
        try {
            a = peers.call(node.get(), req.getMethod(), path, body, p);
        } catch (IOException e) {
            problem(res, 502, "DRS-5052", "node " + node.get() + ", which runs " + registry.title(id) + ", is not reachable; try again shortly", id);
            return;
        }
        res.setStatus(a.status());
        a.headers().forEach((k, v) -> {
            String name = k.toLowerCase(Locale.ROOT);
            if (!DROP.contains(name) && !name.startsWith("x-drishti-net") && !name.startsWith(":")) {
                v.forEach(x -> res.addHeader(k, x));
            }
        });
        res.setHeader("X-Drishti-Engine-Node", node.get());
        res.getOutputStream().write(a.body() == null ? new byte[0] : a.body());
        res.getOutputStream().flush();
    }

    private void off(HttpServletResponse res, String id) throws IOException {
        problem(res, 404, "DRS-1020", "DRS-1020 " + EngineOffException.words(registry.title(id)), id);
    }

    static void problem(HttpServletResponse res, int status, String code, String detail, String engine) throws IOException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "about:blank");
        p.put("title", status == 404 ? "engine off" : "engine node");
        p.put("status", status);
        p.put("detail", detail);
        p.put("code", code);
        if (engine != null) {
            p.put("engine", engine);
        }
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(JSON.writeValueAsString(p));
    }
}
