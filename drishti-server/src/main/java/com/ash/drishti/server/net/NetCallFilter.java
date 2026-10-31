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
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The cluster credential on every call between nodes (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 9). A request that carries the signature header
 * is a node's: its signature (method, path and query, time, node, body) is checked with the cluster key, outside 60 s it is a
 * replay, and a bad one is refused with 401 {@code DRS-5051} and listed on Admin → Net → Security. A good one is marked as
 * coming from that node; when it carries an on-behalf-of token (a forwarded request), the token is checked (signed with the
 * key, for this node, 30 s) and the request runs as that user, provided the user's roles may reach this node
 * ({@code access.yaml}) and this node is not drained. The cluster's own endpoints (gossip, ping, digest, fetch, the scattered
 * search) are only ever a node's: without a signature they are refused, and while the cluster is off they do not exist.
 *
 * <p>Runs before the token filter, which then takes the principal set here instead of asking for a bearer token.
 */
public final class NetCallFilter extends OncePerRequestFilter {

    /** Request attribute: the id of the node that made a verified call. */
    public static final String PEER = "drishti.net.peer";
    /** Request attribute: the verified on-behalf-of claims of a forwarded call. */
    public static final String CLAIMS = "drishti.net.claims";
    /** The endpoints only a node may call. */
    static final Set<String> NODE_ONLY = Set.of("gossip", "ping", "digest", "fetch", "search", "suggest", "state", "subscribe", "reverse", "group", "history",
            "switches", "edits");
    private static final int MAX_BODY = 16 * 1024 * 1024;

    private final NetService net;

    public NetCallFilter(NetService net) {
        this.net = net;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.routed(request).startsWith("/api/v1/");
    }

    /** The first segment after {@code /api/v1/net/}, or null for any other path. */
    static String netEndpoint(String path) {
        if (!path.startsWith("/api/v1/net/")) {
            return null;
        }
        String rest = path.substring("/api/v1/net/".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = RequestPaths.routed(req);
        String endpoint = netEndpoint(path);
        boolean nodeOnly = endpoint != null && NODE_ONLY.contains(endpoint);
        String signature = req.getHeader(NetHeaders.SIGNATURE);
        if (signature == null) {
            if (nodeOnly) {
                if (!net.running()) {
                    problem(res, 404, "DRS-5050", Branding.netName() + " is off on this node");
                } else {
                    net.refused(req.getRemoteAddr(), "", "no cluster credential on " + path);
                    problem(res, 401, "DRS-5051", "this endpoint is for the cluster's nodes only: no cluster credential");
                }
                return;
            }
            chain.doFilter(req, res);
            return;
        }
        if (!net.running() && !("gossip".equals(endpoint) && net.node().isPresent())) {
            problem(res, 404, "DRS-5050", Branding.netName() + " is off on this node");
            return;
        }
        byte[] body = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            problem(res, 413, "DRS-5005", "a call between nodes is at most 16 MB");
            return;
        }
        String node = req.getHeader(NetHeaders.NODE);
        String target = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        ClusterKey.Verdict v = net.key().verify(req.getMethod(), target, req.getHeader(NetHeaders.TIME), node, ClusterKey.bodyHash(body), signature);
        if (!v.ok()) {
            net.refused(req.getRemoteAddr(), node, v.reason());
            problem(res, 401, "DRS-5051", "cluster credential refused: " + v.reason());
            return;
        }
        HttpServletRequest wrapped = new Cached(req, body);
        wrapped.setAttribute(PEER, node);
        String obo = req.getHeader(NetHeaders.ON_BEHALF_OF);
        if (obo != null) {
            OnBehalfOf.Claims c;
            try {
                c = OnBehalfOf.verify(net.key(), obo, net.nodeId());
            } catch (SecurityException e) {
                net.refused(req.getRemoteAddr(), node, e.getMessage());
                problem(res, 401, "DRS-5051", e.getMessage());
                return;
            }
            var self = net.node().map(n -> n.table().self()).orElse(null);
            if (self != null && !net.config().access().reaches(c.roles(), self.nodeId(), self.tags())) {
                problem(res, 403, "DRS-5054", "the roles " + c.roles() + " may not reach node " + self.nodeId() + " (access.yaml)");
                return;
            }
            if (self != null && self.drained() && req.getHeader(NetHeaders.HOP) != null) {
                problem(res, 503, "DRS-5054", "node " + self.nodeId() + " is drained: it takes no forwarded work");
                return;
            }
            wrapped.setAttribute(CLAIMS, c);
            wrapped.setAttribute(Principal.ATTRIBUTE, new Principal(c.sub(), c.roles(), c.app()));
        }
        chain.doFilter(wrapped, res);
    }

    public static void problem(HttpServletResponse res, int status, String code, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"title\":\"net\",\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\""
                + detail.replace("\\", "\\\\").replace("\"", "'") + "\"}");
    }

    /** The request with its body read once (to check its signature) and given again to whatever reads it next. */
    static final class Cached extends HttpServletRequestWrapper {
        private final byte[] body;

        Cached(HttpServletRequest r, byte[] body) {
            super(r);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener l) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
