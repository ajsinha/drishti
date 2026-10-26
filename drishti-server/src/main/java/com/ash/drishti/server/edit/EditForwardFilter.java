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
package com.ash.drishti.server.edit;

import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.net.NetCallFilter;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.net.RunCluster;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In a cluster with an edit primary and no shared database (WRITE_BACK.md section 11), every node that is not the primary forwards the
 * write-back API ({@code /api/v1/changes/**}, a record's history, Admin → Edits) whole to the primary, as the caller (signed, on behalf
 * of them), and passes its answer back unchanged. While the primary is not reachable the answer is {@code 503 DRS-9306}: <i>Edits are
 * paused: the edit primary (…) is not reachable; your changes are kept as drafts. Reading is not affected.</i> A request another node
 * forwarded is answered here only (one hop).
 */
public final class EditForwardFilter extends OncePerRequestFilter {

    private static final Set<String> DROP = Set.of("connection", "keep-alive", "transfer-encoding", "content-length", "date", "server", "upgrade", "vary");
    private static final int MAX_BODY = 16 * 1024 * 1024;

    private final Supplier<EditRuntime> runtime;
    private final RunCluster cluster;

    public EditForwardFilter(Supplier<EditRuntime> runtime, RunCluster cluster) {
        this.runtime = runtime;
        this.cluster = cluster;
    }

    static boolean forwarded(String path) {
        return path.equals("/api/v1/changes") || path.startsWith("/api/v1/changes/") || path.startsWith("/api/v1/admin/edits")
                || path.startsWith("/api/v1/entities/") && path.endsWith("/history");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        EditRuntime r = runtime.get();
        return r == null || r.cluster().plan().mode() != EditCluster.Mode.FOLLOWER || !forwarded(RequestPaths.routed(request))
                || NetCaller.fromPeer() || request.getAttribute(NetCallFilter.PEER) != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Principal p = req.getAttribute(Principal.ATTRIBUTE) instanceof Principal x ? x : null;
        if (p == null) {
            chain.doFilter(req, res);
            return;
        }
        String primary = runtime.get().cluster().plan().primary();
        byte[] body = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            NetCallFilter.problem(res, 413, "DRS-5005", "a forwarded request is at most 16 MB");
            return;
        }
        String path = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        PeerHttp.Answer a;
        try {
            if (!cluster.on() || !cluster.alive(primary)) {
                throw new IOException("not alive");
            }
            a = cluster.call(primary, req.getMethod(), path, body, p, cluster.timeout());
        } catch (IOException e) {
            NetCallFilter.problem(res, 503, "DRS-9306", "Edits are paused: the edit primary (" + primary + ") is not reachable; your changes are kept as "
                    + "drafts. Reading is not affected.");
            return;
        }
        res.setStatus(a.status());
        a.headers().forEach((k, v) -> {
            String name = k.toLowerCase(Locale.ROOT);
            if (!DROP.contains(name) && !name.startsWith("x-drishti-net") && !name.startsWith(":")) {
                v.forEach(x -> res.addHeader(k, x));
            }
        });
        res.getOutputStream().write(a.body() == null ? new byte[0] : a.body());
        res.getOutputStream().flush();
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
