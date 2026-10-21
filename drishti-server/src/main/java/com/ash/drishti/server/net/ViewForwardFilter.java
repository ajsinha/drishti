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

import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.net.security.NetHeaders;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * View forwarding (DRISHTI_NET.md section 7, question 2): a request for a kind whose pack is not on this node, is older here than on
 * a peer, or is served to peers only here, goes whole to a node that renders the kind (path, query, body, the business date and
 * "known at", signed, with the hop header and an on-behalf-of token for the caller), and that node's answer is passed back unchanged,
 * streamed (a live view's events too). The console cannot tell: nothing of the peer reaches the response but its answer. A read that
 * fails on one node is tried once on the next; when none answers, the answer is a clear {@code 502 DRS-5052} or {@code 504 DRS-5053}
 * naming the node, never a wrong page. Requests another node forwarded, and every request while the cluster is off, pass untouched.
 */
public final class ViewForwardFilter extends OncePerRequestFilter {

    /** Request headers passed on to the peer; anything else (cookies, the bearer token, proxies' headers) stays here. */
    private static final List<String> PASS = List.of("Accept", "Content-Type", "Accept-Language", "X-Drishti-As-Of", "X-Drishti-Known-At", "X-Drishti-Share",
            "If-None-Match", "Last-Event-ID");
    /** Response headers never copied back: hop-by-hop ones and the servlet's own. */
    private static final Set<String> DROP = Set.of("connection", "keep-alive", "transfer-encoding", "content-length", "date", "server", "upgrade",
            "proxy-authenticate", "trailer", "te", "vary");
    private static final Map<String, String> ACTIONS = Map.of("views", "view", "entities", "raw", "history", "history", "search", "search", "forms", "form");
    private static final int MAX_BODY = 16 * 1024 * 1024;
    /** Paths under /api/v1/bi/reports/ that are not a report's id. */
    private static final Set<String> BI_RESERVED = Set.of("format-schemas", "catalogue", "dataset", "validate", "format", "query");

    private final NetRouting routing;
    private final Entitlements entitlements;
    private final ForwardLog log;
    private final ObjectProvider<AccessLog> access;
    private final ObjectProvider<com.ash.drishti.engine.command.Mnemonics> mnemonics;
    private final SearchScatter scatter;
    private volatile ObjectProvider<com.ash.drishti.server.bi.report.BiReportService> reports;

    public ViewForwardFilter(NetRouting routing, Entitlements entitlements, ForwardLog log, ObjectProvider<AccessLog> access,
            ObjectProvider<com.ash.drishti.engine.command.Mnemonics> mnemonics, SearchScatter scatter) {
        this.routing = routing;
        this.entitlements = entitlements;
        this.log = log;
        this.access = access;
        this.mnemonics = mnemonics;
        this.scatter = scatter;
    }

    /** The BI reports, to know the kinds a report reads (set by the configuration; without it a report is answered where asked). */
    public ViewForwardFilter withReports(ObjectProvider<com.ash.drishti.server.bi.report.BiReportService> r) {
        this.reports = r;
        return this;
    }

    /** What a request names: the area (views, entities, history, search, forms), the kind (or the pack, for forms) and the entity. */
    record Target(String area, String kind, String id, boolean pack) {}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.routed(request).startsWith("/api/v1/") || (!routing.forwarding() && !routing.joining()) || NetCaller.fromPeer()
                || request.getAttribute(NetCallFilter.PEER) != null;
    }

    Optional<Target> target(HttpServletRequest req) {
        String path = RequestPaths.routed(req);
        String[] seg = path.substring("/api/v1/".length()).split("/");
        String area = seg[0];
        if ("search".equals(area)) {
            if (seg.length > 2 || (seg.length == 2 && !Set.of("csv", "compare").contains(seg[1]))) {
                return Optional.empty();                 // a search's pivot and columns run where its rows are: not forwarded
            }
            String q = req.getParameter("q");
            if (q == null || q.isBlank()) {
                return Optional.empty();
            }
            String head = q.trim().split("\\s+", 2)[0];
            var m = mnemonics.getObject().of(head.toUpperCase(Locale.ROOT));
            if (m.isPresent()) {
                return Optional.of(new Target(area, m.get().kind(), null, false));
            }
            Principal p = principal(req);
            return routing.kindOfMnemonic(head, p).or(() -> Optional.of(head.toLowerCase(Locale.ROOT))).map(k -> new Target(area, k, null, false));
        }
        if ("bi".equals(area) && seg.length >= 3 && "reports".equals(seg[1]) && !BI_RESERVED.contains(seg[2])) {
            return Optional.of(new Target("bi", null, decode(seg[2]), false));       // a BI report: routed to the node that holds its data
        }
        if ("forms".equals(area) && seg.length >= 2) {
            return Optional.of(new Target(area, decode(seg[1]), null, true));
        }
        if (Set.of("views", "entities", "history").contains(area) && seg.length >= 3) {
            String id = "~".equals(seg[2]) && req.getParameter("id") != null ? req.getParameter("id") : decode(seg[2]);
            return Optional.of(new Target(area, decode(seg[1]), id, false));
        }
        return Optional.empty();
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static Principal principal(HttpServletRequest req) {
        return req.getAttribute(Principal.ATTRIBUTE) instanceof Principal p ? p : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Optional<Target> t = target(req);
        Principal p = principal(req);
        if (t.isEmpty() || p == null) {
            chain.doFilter(req, res);
            return;
        }
        if ("bi".equals(t.get().area())) {
            var service = reports == null ? null : reports.getIfAvailable();
            java.util.Set<String> kinds = service == null ? java.util.Set.of() : service.kinds(p, t.get().id());
            NetRouting.Plan bi = routing.report(t.get().id(), kinds, p);
            if (bi.route() == NetRouting.Route.VIEW) {
                forward(req, res, new Target("bi", kinds.isEmpty() ? "bi" : String.join(",", kinds), t.get().id(), false), bi, p);
            } else {
                chain.doFilter(req, res);
            }
            return;
        }
        if (routing.joining() && !t.get().pack() && routing.localPack(t.get().kind()).isEmpty()) {
            // SOAK3-01: still joining, so which node renders a kind not here is not known yet: wait briefly for the peers' digests, and
            // when they are still not in, say so (DRS-5052, which the console tries once on another server), never "no such kind"
            if (!routing.awaitJoined()) {
                NetCallFilter.problem(res, 502, "DRS-5052", PeerSources.JOINING + t.get().kind() + "; try again in a moment");
                return;
            }
        }
        if (!routing.forwarding()) {
            chain.doFilter(req, res);                    // the node joined alone, or the cluster is off now
            return;
        }
        NetRouting.Plan plan = t.get().pack() ? packPlan(t.get().kind(), p, req) : routing.view(t.get().kind(), p, dated(req), t.get().id());
        if (plan.route() == NetRouting.Route.LOCAL) {
            chain.doFilter(req, res);
            return;
        }
        if (plan.route() == NetRouting.Route.NONE) {
            boolean local = !t.get().pack() && routing.localPack(t.get().kind()).isPresent();
            if (!local && !t.get().pack() && !routing.downRenderers(t.get().kind()).isEmpty()) {
                // the nodes that serve it are down: say so, rather than "no such kind"
                NetCallFilter.problem(res, 502, "DRS-5052", plan.reason() + "; try again in a moment");
            } else if (hiddenHere(t.get())) {
                // a kind of a pack served to peers only here, and no other node: answered as if the pack were not here
                NetCallFilter.problem(res, 404, "DRS-1002", "no source serves kind '" + t.get().kind() + "'");
            } else {
                chain.doFilter(req, res);
            }
            return;
        }
        if (!t.get().pack() && routing.localPack(t.get().kind()).isPresent() && !entitlements.mayOpen(p, t.get().kind())) {
            // the kind is known here and the caller may not open it: refused here, before any call (the controller says why). A kind
            // whose pack is not here is decided by the node that renders it, which knows the pack's roles
            chain.doFilter(req, res);
            return;
        }
        if ("search".equals(t.get().area()) && "/api/v1/search".equals(RequestPaths.routed(req)) && scatter != null) {
            scatterSearch(req, res, t.get(), plan, p);
            return;
        }
        forward(req, res, t.get(), plan, p);
    }

    /**
     * A search of a kind whose pack is not here: asked of every node that holds data of it (not one forwarded node), each on its own
     * data, and merged here as one answer (section 8).
     */
    private void scatterSearch(HttpServletRequest req, HttpServletResponse res, Target t, NetRouting.Plan plan, Principal p) throws IOException {
        String q = req.getParameter("q");
        com.ash.drishti.engine.search.SearchQuery query;
        try {
            query = com.ash.drishti.engine.search.SearchQuery.pick(q);
        } catch (RuntimeException e) {
            forward(req, res, t, plan, p);                 // a query this node cannot read: the node that renders the kind says why
            return;
        }
        List<String> peers = routing.tableFor(p).scatterTargets(t.kind());
        if (peers.isEmpty()) {
            peers = plan.targets();
        }
        var shares = new java.util.ArrayList<>(scatter.ask(peers, SearchScatter.sharePath(q), asOf(req), p, scatter.budget(), "search", t.kind()));
        routing.downHolders(t.kind()).forEach(d -> shares.add(new SearchScatter.Share(d, null, "not asked: it cannot be reached now", 0)));
        if (shares.stream().allMatch(s -> s.answer() == null)) {
            NetCallFilter.problem(res, 502, "DRS-5052", "no node that holds " + t.kind() + " answered the search: "
                    + String.join("; ", shares.stream().map(s -> s.nodeId() + " " + s.failure()).toList()));
            return;
        }
        Map<String, Object> merged = scatter.merge(null, shares, query.descending(), query.limit(), entitlements.isAdmin(p));
        res.setStatus(200);
        res.setContentType("application/json");
        routing.net().http().json().writeValue(res.getOutputStream(), merged);
        recordAccess(req, t, p, String.join(",", peers), NetCaller.current().map(NetCaller.Caller::requestId).orElse("-"));
    }

    private static com.ash.drishti.api.AsOf asOf(HttpServletRequest req) {
        String d = req.getHeader("X-Drishti-As-Of");
        if (d == null) {
            d = req.getParameter("asOf");
        }
        String k = req.getHeader("X-Drishti-Known-At");
        java.time.LocalDate date = null;
        try {
            date = d == null || d.isBlank() || "live".equalsIgnoreCase(d) ? null : java.time.LocalDate.parse(d.trim());
        } catch (RuntimeException e) {
            date = null;
        }
        Instant known = null;
        try {
            known = k == null || k.isBlank() ? null : Instant.parse(k.trim());
        } catch (RuntimeException e) {
            known = null;
        }
        return date == null && known == null ? com.ash.drishti.api.AsOf.LATEST : new com.ash.drishti.api.AsOf(date, known, false);
    }

    /** A pack's form: forwarded when the pack is not on here (or is peer-only here) and a reachable node has it. */
    private NetRouting.Plan packPlan(String pack, Principal p, HttpServletRequest req) {
        boolean here = routing.net().config().node().peerOnlyPacks().contains(pack) ? false
                : routing.digests().table().node(routing.net().nodeId()).map(n -> n.digest().pack(pack).isPresent()).orElse(false);
        if (here) {
            return new NetRouting.Plan(NetRouting.Route.LOCAL, pack, "the pack is here", List.of(), List.of());
        }
        var t = routing.tableFor(p);
        List<String> have = t.nodes().stream().filter(n -> !n.self() && n.digest().pack(pack).isPresent()).map(n -> n.id()).toList();
        return have.isEmpty() ? new NetRouting.Plan(NetRouting.Route.NONE, pack, "no node has the pack", List.of(), List.of())
                : new NetRouting.Plan(NetRouting.Route.VIEW, pack, "the pack is on " + have, have, List.of());
    }

    private boolean hiddenHere(Target t) {
        return t.pack() ? routing.peerOnly(t.kind()) : routing.hiddenHere(t.kind());
    }

    private static boolean dated(HttpServletRequest req) {
        String d = req.getHeader("X-Drishti-As-Of");
        if (d == null) {
            d = req.getParameter("asOf");
        }
        return (d != null && !d.isBlank() && !"live".equalsIgnoreCase(d)) || req.getHeader("X-Drishti-Known-At") != null;
    }

    private void forward(HttpServletRequest req, HttpServletResponse res, Target t, NetRouting.Plan plan, Principal p) throws IOException {
        byte[] body = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            NetCallFilter.problem(res, 413, "DRS-5005", "a forwarded request is at most 16 MB");
            return;
        }
        String pathAndQuery = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        boolean stream = pathAndQuery.contains("/stream") || String.valueOf(req.getHeader("Accept")).contains("text/event-stream");
        boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
        String requestId = NetCaller.current().map(NetCaller.Caller::requestId).orElse("-");
        Duration timeout = routing.net().config().routing().forwardTimeout();
        String lastError = "";
        boolean timedOut = false;
        // a read goes to the best renderer and, if it does not hold the entity (404: which node holds it cannot always be told from the
        // digests) or fails, to the next, at most three; a write to the best only, never retried
        List<String> targets = read ? plan.targets().subList(0, Math.min(3, plan.targets().size())) : plan.targets().subList(0, Math.min(1, plan.targets().size()));
        int failures = 0;
        byte[] notHeld = null;
        for (String node : targets) {
            if (failures >= 2) {
                break;                                     // tried once on the next after a failure, never more
            }
            String url = routing.net().node().flatMap(n -> n.table().known(node)).map(k -> k.member().url()).orElse(null);
            if (url == null) {
                continue;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (String h : PASS) {
                if (req.getHeader(h) != null) {
                    headers.put(h, req.getHeader(h));
                }
            }
            headers.put(NetHeaders.HOP, "1");
            headers.put(NetHeaders.REQUEST, requestId);
            headers.put(NetHeaders.ON_BEHALF_OF, com.ash.drishti.net.security.OnBehalfOf.mint(routing.net().key(), p.user(), p.roles(), p.embedApp(),
                    routing.net().nodeId(), requestId, node));
            long t0 = System.nanoTime();
            HttpResponse<InputStream> answer;
            try {
                answer = routing.net().http().open(url, req.getMethod(), pathAndQuery, headers, body, stream ? Duration.ofSeconds(30) : timeout);
            } catch (HttpTimeoutException e) {
                timedOut = true;
                failures++;
                lastError = node + " did not answer within " + timeout.toMillis() + " ms";
                log.record(node, t.kind(), "view", "timeout", ms(t0), pathAndQuery);
                continue;
            } catch (IOException e) {
                failures++;
                lastError = node + " could not be reached (" + e.getClass().getSimpleName() + ")";
                log.record(node, t.kind(), "view", "failed", ms(t0), pathAndQuery);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            int status = answer.statusCode();
            boolean last = node.equals(targets.get(targets.size() - 1));
            if ((status == 502 || status == 503) && read && !last) {
                answer.body().close();                     // the node is drained or failing: the next one is asked
                failures++;
                lastError = node + " answered " + status;
                log.record(node, t.kind(), "view", "failed", ms(t0), pathAndQuery);
                continue;
            }
            if (status == 404 && read && !last) {
                try (InputStream in = answer.body()) {
                    notHeld = in.readNBytes(64 * 1024);    // that node does not hold it: the next one may
                }
                log.record(node, t.kind(), "view", "not-held", ms(t0), pathAndQuery);
                continue;
            }
            relay(answer, res, stream);
            log.record(node, t.kind(), "view", ForwardLog.outcome(status), ms(t0), pathAndQuery);
            if (status / 100 == 2) {
                recordAccess(req, t, p, node, requestId);
            }
            return;
        }
        if (notHeld != null && failures == 0) {
            res.setStatus(404);                            // no node holds it: the answer of the last that said so
            res.setContentType("application/problem+json");
            res.getOutputStream().write(notHeld);
            return;
        }
        NetCallFilter.problem(res, timedOut ? 504 : 502, timedOut ? "DRS-5053" : "DRS-5052", "the node that serves " + t.kind() + " did not answer: "
                + (lastError.isEmpty() ? "no node is reachable now" : lastError) + "; try again in a moment");
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** The peer's answer as it is: status, its headers (none of the hop-by-hop ones), its body streamed. */
    private static void relay(HttpResponse<InputStream> answer, HttpServletResponse res, boolean stream) throws IOException {
        res.setStatus(answer.statusCode());
        answer.headers().map().forEach((k, v) -> {
            String name = k.toLowerCase(Locale.ROOT);
            if (!DROP.contains(name) && !name.startsWith("x-drishti-net") && !name.startsWith(":")) {
                v.forEach(x -> res.addHeader(k, x));
            }
        });
        try (InputStream in = answer.body()) {
            OutputStream out = res.getOutputStream();
            byte[] buf = new byte[stream ? 1024 : 16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (stream) {
                    out.flush();                           // a live view's event reaches the browser as it comes
                }
            }
            out.flush();
        } catch (IOException e) {
            // the viewer went away (a closed live view) or the peer did: the stream ends either way
        }
    }

    /** The forwarding node's access-log row: the node the request went to and the request id (the receiving node records its own). */
    private void recordAccess(HttpServletRequest req, Target t, Principal p, String node, String requestId) {
        AccessLog sink = access.getIfAvailable();
        String action = ACTIONS.get(t.area());
        if (sink == null || action == null || "form".equals(action) || p.roles().contains("service")) {
            return;
        }
        String detail = ("search".equals(action) ? req.getParameter("q") + " " : "") + "net:to " + node + " request " + requestId;
        String date = req.getHeader("X-Drishti-As-Of");
        try {
            sink.record(new AccessLog.Event(Instant.now(), p.user(), action, t.kind(), t.id(), detail, date == null || date.isBlank() ? null : date));
        } catch (RuntimeException e) {
            logger.warn("could not record a forwarded read in the access log: " + e.getMessage());
        }
    }
}
