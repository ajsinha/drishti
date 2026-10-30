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

import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.security.NetHeaders;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * One call to every other live member, once (2.9.7: a purge for the whole cluster, PO-6; a data-load announcement forwarded, PO-5).
 * Signed with the cluster key and carrying the hop header and an on-behalf-of token, as the shard and run calls do: the member runs it as
 * the same person, audits it in its own log, and, seeing the call came from a peer, never forwards it again (one hop). Members are called
 * in parallel on virtual threads; one that does not answer is reported, never retried here.
 */
public final class PeerCalls {

    /** What one member answered: its id, the HTTP status (0: not reached) and the body or the error. */
    public record Answer(String node, int status, String body) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node", node);
            m.put("status", status);
            m.put("answer", body.length() > 500 ? body.substring(0, 500) + "…" : body);
            return m;
        }
    }

    private final Supplier<NetService> net;

    public PeerCalls(Supplier<NetService> net) {
        this.net = net;
    }

    /** True while this server is a member of a running Drishti*net with others alive. */
    public boolean clustered() {
        NetService n = net.get();
        return n != null && n.running() && n.node().isPresent() && !n.nodeId().isEmpty();
    }

    /** The other members alive now. */
    public List<String> peers() {
        NetService n = net.get();
        if (!clustered()) {
            return List.of();
        }
        return n.node().map(x -> x.table().others().stream().filter(k -> k.member().state() == MemberState.ALIVE)
                .map(k -> k.member().nodeId()).toList()).orElse(List.of());
    }

    /** {@code method pathAndQuery} with {@code body} to every other live member for {@code p}; waits at most {@code timeout} for all. */
    public List<Answer> all(String method, String pathAndQuery, byte[] body, Principal p, Duration timeout) {
        NetService n = net.get();
        List<String> nodes = peers();
        List<CompletableFuture<Answer>> calls = new ArrayList<>();
        for (String node : nodes) {
            CompletableFuture<Answer> f = new CompletableFuture<>();
            Thread.ofVirtual().name("drishti-peer-call-" + node).start(() -> f.complete(one(n, node, method, pathAndQuery, body, p, timeout)));
            calls.add(f);
        }
        List<Answer> out = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            try {
                out.add(calls.get(i).get(timeout.toMillis() + 1000, java.util.concurrent.TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                out.add(new Answer(nodes.get(i), 0, "no answer: " + e));
            }
        }
        return out;
    }

    private static Answer one(NetService n, String node, String method, String pathAndQuery, byte[] body, Principal p, Duration timeout) {
        try {
            String url = n.node().flatMap(x -> x.table().known(node)).map(k -> k.member().url()).orElseThrow();
            String request = NetCaller.current().map(NetCaller.Caller::requestId).orElseGet(() -> UUID.randomUUID().toString().substring(0, 13));
            Map<String, String> h = new LinkedHashMap<>();
            h.put(NetHeaders.HOP, "1");
            h.put(NetHeaders.REQUEST, request);
            h.put(NetHeaders.ON_BEHALF_OF, OnBehalfOf.mint(n.key(), p.user(), p.roles(), p.embedApp(), n.nodeId(), request, node));
            h.put("Content-Type", "application/json");
            h.put("Accept", "application/json");
            PeerHttp.Answer a = n.http().send(url, method, pathAndQuery, h, body, timeout);
            return new Answer(node, a.status(), a.text());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(node, 0, "interrupted");
        } catch (Exception e) {
            return new Answer(node, 0, String.valueOf(e.getMessage()));
        }
    }
}
