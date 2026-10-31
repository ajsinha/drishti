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

import com.ash.drishti.common.Branding;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.security.ClusterKey;
import com.ash.drishti.net.security.NetHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The priority path of the membership (DRISHTI_NET.md section 5, "gossip off the busy path", 2.6.7). The first filter of every request:
 * {@code POST /api/v1/net/gossip}, {@code GET /api/v1/net/ping} and {@code GET /api/v1/net/digest} are answered here, whole, from memory:
 * the cluster key is checked and the answer written, and the request never reaches another filter, the token check, request activity, the
 * size and path guards, view forwarding, the dispatcher or any admission gate (read slots, BI admission, max-concurrent). On the gossip
 * port ({@link GossipConnector}) nothing else is served. The only I/O is an indirect probe's own probe, on the control lane.
 */
public final class GossipPath extends OncePerRequestFilter {

    static final String GOSSIP = "/api/v1/net/gossip";
    static final String PING = "/api/v1/net/ping";
    static final String DIGEST = "/api/v1/net/digest";
    /** The switches ledger (Admin → Commands), pulled by a peer whose gossip showed a different hash: on the control lane, so here. */
    static final String SWITCHES = "/api/v1/net/switches";
    /** A member table is small; this is the cap of any call between nodes. */
    private static final int MAX_BODY = 16 * 1024 * 1024;

    private final NetService net;
    private final Supplier<DigestService> digests;
    private final GossipConnector port;
    private final ObjectMapper json = new ObjectMapper();
    private volatile Supplier<Object> switches = () -> null;

    public GossipPath(NetService net, Supplier<DigestService> digests, GossipConnector port) {
        this.net = net;
        this.digests = digests;
        this.port = port;
    }

    /** What {@code GET /api/v1/net/switches} answers (this node's switches ledger), or null while there is none. */
    public void switches(Supplier<Object> s) {
        this.switches = s == null ? () -> null : s;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        String method = req.getMethod();
        boolean ours = GOSSIP.equals(path) && "POST".equals(method) || (PING.equals(path) || DIGEST.equals(path) || SWITCHES.equals(path)) && "GET".equals(method);
        if (!ours) {
            if (port != null && port.isGossipPort(req.getLocalPort())) {
                NetCallFilter.problem(res, 404, "DRS-5056", "this port serves " + Branding.netName() + " gossip, probes and digests only; the server's port serves "
                        + "everything else");
                return;
            }
            chain.doFilter(req, res);
            return;
        }
        serve(req, res, path);
    }

    private void serve(HttpServletRequest req, HttpServletResponse res, String path) throws IOException {
        String signature = req.getHeader(NetHeaders.SIGNATURE);
        if (signature == null) {
            if (!net.running()) {
                NetCallFilter.problem(res, 404, "DRS-5050", Branding.netName() + " is off on this node");
            } else {
                net.refused(req.getRemoteAddr(), "", "no cluster credential on " + path);
                NetCallFilter.problem(res, 401, "DRS-5051", "this endpoint is for the cluster's nodes only: no cluster credential");
            }
            return;
        }
        boolean gossip = GOSSIP.equals(path);
        if (!net.running() && !(gossip && net.node().isPresent())) {
            NetCallFilter.problem(res, 404, "DRS-5050", Branding.netName() + " is off on this node");
            return;
        }
        byte[] body = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            NetCallFilter.problem(res, 413, "DRS-5005", "a call between nodes is at most 16 MB");
            return;
        }
        String node = req.getHeader(NetHeaders.NODE);
        String target = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        ClusterKey.Verdict v = net.key().verify(req.getMethod(), target, req.getHeader(NetHeaders.TIME), node, ClusterKey.bodyHash(body), signature);
        if (!v.ok()) {
            net.refused(req.getRemoteAddr(), node, v.reason());
            NetCallFilter.problem(res, 401, "DRS-5051", "cluster credential refused: " + v.reason());
            return;
        }
        NetService.Node n = net.node().orElse(null);
        if (n == null) {
            NetCallFilter.problem(res, 404, "DRS-5050", Branding.netName() + " is off on this node: config/net/node.yaml does not say enabled: true (see docs/admin/OPERATIONS.md)");
            return;
        }
        Object answer;
        if (gossip) {
            List<Member> theirs;
            try {
                JsonNode tree = json.readTree(body);
                theirs = List.of(json.treeToValue(tree.path("members"), Member[].class));
            } catch (IOException | RuntimeException e) {
                NetCallFilter.problem(res, 400, "DRS-5056", "not a member table: " + e.getMessage());
                return;
            }
            n.gossiper().accept(theirs);                       // merged, and joins or departures told as for an exchange of this node
            answer = Map.of("members", n.table().entries());
        } else if (PING.equals(path)) {
            String t = req.getParameter("target");
            if (t == null || t.isBlank()) {
                answer = Map.of("nodeId", n.id(), "state", n.table().self().state().wire());
            } else {
                // only a member's address is probed: a probe is never a way to make this node call anywhere else
                String url = t.replaceAll("/+$", "");
                if (n.table().entries().stream().noneMatch(m -> m.url().equals(url))) {
                    NetCallFilter.problem(res, 400, "DRS-5056", "not a member's address: " + url);
                    return;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeId", n.id());
                m.put("target", url);
                m.put("answered", net.http().ping(url));
                answer = m;
            }
        } else if (SWITCHES.equals(path)) {
            answer = switches.get();
            if (answer == null) {
                NetCallFilter.problem(res, 404, "DRS-5050", "this node keeps no switches");
                return;
            }
        } else {
            DigestService d = digests.get();
            if (d == null) {
                NetCallFilter.problem(res, 404, "DRS-5050", Branding.netName() + " is off on this node");
                return;
            }
            answer = d.self();
        }
        byte[] out = net.http().json().writeValueAsBytes(answer);
        res.setStatus(200);
        res.setContentType("application/json");
        res.setContentLength(out.length);
        res.getOutputStream().write(out);
    }
}
