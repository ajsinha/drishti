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
import com.ash.drishti.net.security.ClusterKey;
import com.ash.drishti.net.security.NetHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The priority path of the calls between nodes that are not request traffic (CLUSTER_I5_DESIGN.md section 4.1): {@code GET /api/v1/net/ping},
 * {@code GET /api/v1/net/digest} and {@code GET /api/v1/net/switches} are answered by the very first filter, whole, from memory: the
 * cluster key is checked and the answer written, and the request never reaches the token check, request activity, the guards, view
 * forwarding, the dispatcher or any admission gate, so a busy server still answers its peers. Membership itself is not here: it is the
 * membership channel's ({@code cluster-port}).
 */
public final class ControlPath extends OncePerRequestFilter {

    static final String PING = "/api/v1/net/ping";
    static final String DIGEST = "/api/v1/net/digest";
    /** The switches ledger (Admin → Functions), pulled by a peer whose vitals showed a different hash: on the control lane, so here. */
    static final String SWITCHES = "/api/v1/net/switches";

    private final NetService net;
    private final Supplier<DigestService> digests;
    private volatile Supplier<Object> switches = () -> null;

    public ControlPath(NetService net, Supplier<DigestService> digests) {
        this.net = net;
        this.digests = digests;
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
        boolean ours = (PING.equals(path) || DIGEST.equals(path) || SWITCHES.equals(path)) && "GET".equals(req.getMethod());
        if (!ours) {
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
        if (!net.running()) {
            NetCallFilter.problem(res, 404, "DRS-5050", Branding.netName() + " is off on this node");
            return;
        }
        String node = req.getHeader(NetHeaders.NODE);
        String target = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        ClusterKey.Verdict v = net.key().verify(req.getMethod(), target, req.getHeader(NetHeaders.TIME), node, ClusterKey.bodyHash(new byte[0]), signature);
        if (!v.ok()) {
            net.refused(req.getRemoteAddr(), node, v.reason());
            NetCallFilter.problem(res, 401, "DRS-5051", "cluster credential refused: " + v.reason());
            return;
        }
        NetService.Node n = net.node().orElse(null);
        if (n == null) {
            NetCallFilter.problem(res, 404, "DRS-5050", Branding.netName() + " is off on this node: config/net/node.yaml does not say enabled: true (see docs/guides/OPERATIONS_GUIDE.md)");
            return;
        }
        Object answer;
        if (PING.equals(path)) {
            answer = Map.of("nodeId", n.id(), "state", n.members().self().state().wire(), "view", n.members().viewId());
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
