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

import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.bi.runs.Run;
import com.ash.drishti.server.bi.runs.RunId;
import com.ash.drishti.server.bi.runs.RunService;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A run's own calls go to the node that holds it (docs/architecture/BI_RUNS.md section 13.2): {@code /api/v1/bi/runs/{id}/...} (result, cancel, export,
 * share, promote and the other actions) is forwarded whole to the run's node, for the caller (on-behalf-of: the same rights there), and its answer is passed
 * back unchanged. The node is the row's when this node can read the row (a shared identity database), else the one the id names ({@code r-<node>-...}).
 * {@code GET /runs/{id}} is answered here when the row is readable here. A node that does not answer gives {@code 502 DRS-5052}: the run's state is unknown
 * until it returns. A request another node forwarded is answered here only (one hop).
 */
public final class RunForwardFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/v1/bi/runs/";
    /** Paths under /runs/ that are not a run's id. */
    private static final Set<String> RESERVED = Set.of("events", "estimate", "settings", "schedules");
    private static final Set<String> DROP = Set.of("connection", "keep-alive", "transfer-encoding", "content-length", "date", "server", "upgrade", "vary");
    private static final int MAX_BODY = 16 * 1024 * 1024;

    private final RunCluster cluster;
    private final RunService runs;
    private final ForwardLog log;

    public RunForwardFilter(RunCluster cluster, RunService runs, ForwardLog log) {
        this.cluster = cluster;
        this.runs = runs;
        this.log = log;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.routed(request).startsWith(PREFIX) || !cluster.on() || NetCaller.fromPeer() || request.getAttribute(NetCallFilter.PEER) != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String rest = RequestPaths.routed(req).substring(PREFIX.length());
        String id = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        Principal p = req.getAttribute(Principal.ATTRIBUTE) instanceof Principal x ? x : null;
        if (p == null || id.isEmpty() || RESERVED.contains(id) || !RunId.valid(id)) {
            chain.doFilter(req, res);
            return;
        }
        Optional<Run> row = runs.get(id);
        if (row.isPresent() && "GET".equals(req.getMethod()) && !rest.contains("/")) {
            chain.doFilter(req, res);                                          // the row is readable here: its status needs no forwarding
            return;
        }
        String node = row.map(Run::node).orElseGet(() -> RunId.node(id));
        if (node == null || node.equals(cluster.self()) || (row.isEmpty() && !cluster.alive(node) && !known(node))) {
            chain.doFilter(req, res);
            return;
        }
        byte[] body = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            NetCallFilter.problem(res, 413, "DRS-5005", "a forwarded request is at most 16 MB");
            return;
        }
        String path = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        long t0 = System.nanoTime();
        PeerHttp.Answer a;
        try {
            a = cluster.call(node, req.getMethod(), path, body, p, cluster.timeout());
        } catch (IOException e) {
            log.record(node, "bi-run", "run", "failed", (System.nanoTime() - t0) / 1_000_000, path);
            NetCallFilter.problem(res, 502, "DRS-5052", "node " + node + " is not reachable: the state of run " + id
                    + " is unknown until it returns; re-run it here to have it now");
            return;
        }
        log.record(node, "bi-run", "run", ForwardLog.outcome(a.status()), (System.nanoTime() - t0) / 1_000_000, path);
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

    /** A node this node has heard of (a dead one is still known: its runs answer "unknown", not "no such run"). */
    private boolean known(String node) {
        List<String> all = cluster.directory().nodes();
        return all.contains(node);
    }
}
