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
import com.ash.drishti.server.api.IdTokens;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RequestPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One server per screen (RACHANA_REFERENCE.md › Sutra Next: fragments, events, children, sources, layout and forms, section 7, CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 11.1): a Sutra Next screen's selections are tokens held in the
 * memory of the node that issued them, and each token names that node ({@code tok_<node>~...}). The console sends a screen's clicks and
 * children to that node; a request that reaches another node anyway (the console has not learnt the node's address, or a proxy chose)
 * is forwarded whole to the issuing node for the caller (on-behalf-of, the same rights there), and its answer passed back unchanged, as
 * a run's calls are. When the issuing node is gone the answer is {@code 502 DRS-5052} saying the selections were reset because that
 * server went away: the page clears them and the reader picks again. A request another node forwarded is answered here only (one hop).
 */
public final class SelectionForwardFilter extends OncePerRequestFilter {

    /** The requests that carry selections: a click ({@code POST .../events}) and a panel's children ({@code GET .../children?sel=}). */
    static final Pattern PATH = Pattern.compile("/api/v1/views/[^/]+/.+/(events|panels/[^/]+/children)");
    private static final Set<String> DROP = Set.of("connection", "keep-alive", "transfer-encoding", "content-length", "date", "server", "upgrade", "vary");
    private static final int MAX_BODY = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunCluster cluster;
    private final ForwardLog log;

    public SelectionForwardFilter(RunCluster cluster, ForwardLog log) {
        this.cluster = cluster;
        this.log = log;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.matcher(RequestPaths.routed(request)).matches() || !cluster.on() || NetCaller.fromPeer()
                || request.getAttribute(NetCallFilter.PEER) != null;
    }

    /** The words the page shows when the node that held a screen's selections is gone. */
    public static String reset(String node) {
        return "your selections were reset because server " + node + " went away: pick again";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Principal p = req.getAttribute(Principal.ATTRIBUTE) instanceof Principal x ? x : null;
        boolean post = "POST".equalsIgnoreCase(req.getMethod());
        byte[] body = post ? req.getInputStream().readNBytes(MAX_BODY + 1) : new byte[0];
        HttpServletRequest again = post ? new NetCallFilter.Cached(req, body) : req;
        if (body.length > MAX_BODY) {
            NetCallFilter.problem(res, 413, "DRS-5005", "a click is at most 64 KB");
            return;
        }
        String node = issuer(req, body);
        if (p == null || node == null || node.equals(cluster.self())) {
            chain.doFilter(again, res);
            return;
        }
        if (!cluster.alive(node)) {
            log.record(node, "selection", "view", "failed", 0, req.getRequestURI());
            NetCallFilter.problem(res, 502, "DRS-5052", reset(node));
            return;
        }
        String path = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        long t0 = System.nanoTime();
        PeerHttp.Answer a;
        try {
            a = cluster.call(node, req.getMethod().toUpperCase(Locale.ROOT), path, body, p, cluster.timeout());
        } catch (IOException e) {
            log.record(node, "selection", "view", "failed", (System.nanoTime() - t0) / 1_000_000, path);
            NetCallFilter.problem(res, 502, "DRS-5052", reset(node));
            return;
        }
        log.record(node, "selection", "view", ForwardLog.outcome(a.status()), (System.nanoTime() - t0) / 1_000_000, path);
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

    /** The node that issued the request's selections: the first token naming one ({@code sel} parameters, or a click's {@code selections}). */
    static String issuer(HttpServletRequest req, byte[] body) {
        List<String> tokens = new ArrayList<>();
        String[] sel = req.getParameterValues("sel");
        if (sel != null) {
            tokens.addAll(List.of(sel));
        }
        if (body.length > 0) {
            try {
                JsonNode n = JSON.readTree(body).path("selections");
                n.forEach(t -> tokens.add(t.asText()));
            } catch (IOException e) {
                return null;                                          // not JSON: the controller says what is wrong
            }
        }
        return tokens.stream().map(IdTokens::node).filter(x -> x != null).findFirst().orElse(null);
    }
}
