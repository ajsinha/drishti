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
package com.ash.drishti.server.shard;

import com.ash.drishti.net.digest.Digest;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.member.MemberTable;
import com.ash.drishti.net.security.NetHeaders;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.net.DigestService;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The members a sharded measure's dates are spread over (VAR_SHARDING.md section 4), for any measure: this server and the Drishti*net
 * members that are alive, with what each says of the measure's shards in its capability digest (a service of its own per measure, VaR's
 * {@code var-shards}: whether it owns shards, whether it is
 * draining, its zone, and per date the shard map it holds and the shards it is ready to serve). Readiness travels with the digest: a change
 * raises the digest's hash in gossip and the peers pull it within a round, so "ready for shard X" is announced through gossip without a
 * protocol of its own. Calls to a member are the cluster's signed, one-hop calls; a call for a person carries their on-behalf-of token.
 * With Drishti*net off the only member is this server ({@code local}).
 */
public final class ShardPeers {

    /** The digest service name of a member's shard state for this measure ({@code var-shards}). */
    private final String service;
    public static final String LOCAL = "local";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * What a member says of its shards of the measure.
     *
     * @param on sharding is on there
     * @param owner it may own shards (not coordinator-only)
     * @param draining it is handing its shards over (it owns none from now on)
     * @param dates per business date the shard maps it holds shards of, each with the shards ready to serve and the data each was built
     *     from (the first 16 characters of the shard's hot file digest)
     * @param open the business dates open (put together) on the member now, apart from those it holds shards of (2.9.7: a held date
     *     nobody opens is released for age); null from a member older than 2.9.7, whose held dates count as open
     */
    public record State(boolean on, boolean owner, boolean draining, Map<LocalDate, List<Ready>> dates, java.util.Set<LocalDate> open) {
        public static final State NONE = new State(false, false, false, Map.of(), java.util.Set.of());

        public State(boolean on, boolean owner, boolean draining, Map<LocalDate, List<Ready>> dates) {
            this(on, owner, draining, dates, null);
        }

        /** The dates this member has open (its held dates when it does not say: a member older than 2.9.7). */
        public java.util.Set<LocalDate> openOrHeld() {
            return open != null ? open : dates.keySet();
        }

        public String json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("on", on);
            m.put("owner", owner);
            m.put("draining", draining);
            Map<String, Object> d = new TreeMap<>();
            dates.forEach((k, v) -> d.put(k.toString(), v.stream().map(r -> Map.of("map", r.map(), "ready", r.ready())).toList()));
            m.put("dates", d);
            if (open != null) {
                m.put("open", new java.util.TreeSet<>(open).stream().map(LocalDate::toString).toList());
            }
            try {
                return JSON.writeValueAsString(m);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return "";
            }
        }

        public static State parse(String json) {
            if (json == null || json.isBlank()) {
                return NONE;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(json);
                Map<LocalDate, List<Ready>> dates = new TreeMap<>();
                n.path("dates").fields().forEachRemaining(e -> {
                    List<Ready> maps = new ArrayList<>();
                    e.getValue().forEach(r -> {
                        Map<Integer, String> ready = new TreeMap<>();
                        r.path("ready").fields().forEachRemaining(x -> ready.put(Integer.parseInt(x.getKey()), x.getValue().asText()));
                        maps.add(new Ready(r.path("map").asText(), ready));
                    });
                    dates.put(LocalDate.parse(e.getKey()), maps);
                });
                java.util.Set<LocalDate> open = null;
                if (n.has("open")) {
                    open = new java.util.TreeSet<>();
                    for (com.fasterxml.jackson.databind.JsonNode x : n.path("open")) {
                        open.add(LocalDate.parse(x.asText()));
                    }
                }
                return new State(n.path("on").asBoolean(), n.path("owner").asBoolean(), n.path("draining").asBoolean(), dates, open);
            } catch (Exception e) {
                return NONE;
            }
        }

        /** The data a member holds shard {@code index} of the date's map {@code map} from (16 characters of its digest), or null. */
        public String held(LocalDate date, String map, int index) {
            for (Ready r : dates.getOrDefault(date, List.of())) {
                if (r.map().equals(map) && r.ready().containsKey(index)) {
                    return r.ready().get(index);
                }
            }
            return null;
        }

        /** True when the member is ready to serve shard {@code index} of the map built from data {@code digest} (any data when null). */
        public boolean ready(LocalDate date, String map, int index, String digest) {
            String h = held(date, map, index);
            return h != null && (digest == null || digest.startsWith(h));
        }
    }

    /** One shard map of a date and the shards of it ready on a member: index to the first 16 characters of the shard's data digest. */
    public record Ready(String map, Map<Integer, String> ready) {}

    /**
     * A member as the shards see it.
     *
     * @param routable alive, speaking this protocol, not drained by Drishti*net's own drain
     * @param zone its zone (the value of its tag {@code <zone-tag>:<zone>}), or empty
     */
    public record Peer(String node, String url, String zone, boolean self, boolean routable, State state) {
        /** May own shards now: routable, sharding on, not coordinator-only, not draining. */
        public boolean eligible() {
            return routable && state.on() && state.owner() && !state.draining();
        }
    }

    private final Supplier<NetService> net;
    private final Supplier<DigestService> digests;

    /**
     * @param service the digest service name the measure's shard state is published under ({@code var-shards})
     */
    public ShardPeers(String service, Supplier<NetService> net, Supplier<DigestService> digests) {
        this.service = service;
        this.net = net;
        this.digests = digests;
    }

    /** True while this server is a member of a running Drishti*net. */
    public boolean clustered() {
        NetService n = net.get();
        return n != null && n.running() && n.node().isPresent() && !n.nodeId().isEmpty();
    }

    /** This member's id ({@code local} with Drishti*net off). */
    public String self() {
        return clustered() ? net.get().nodeId() : LOCAL;
    }

    /** Publishes this member's state in its digest (rebuilt each round); a no-op with Drishti*net off. */
    public void publish(Supplier<String> state) {
        DigestService d = digests.get();
        if (d != null) {
            d.service(service, state);
        }
    }

    /** Rebuilds this member's digest now, so a shard just ready is announced in the next gossip round, not after the digest's own. */
    public void announce() {
        DigestService d = digests.get();
        if (d != null && clustered()) {
            d.announce();
        }
    }

    /** This member, then every other member that is alive, with its state; {@code mine} is this member's own (never read from a digest). */
    public List<Peer> peers(State mine, String zoneTag) {
        List<Peer> out = new ArrayList<>();
        if (!clustered()) {
            out.add(new Peer(LOCAL, "", "", true, true, mine));
            return out;
        }
        NetService n = net.get();
        MemberTable table = n.node().orElseThrow().table();
        Member me = table.self();
        out.add(new Peer(me.nodeId(), me.url(), zone(me.tags(), zoneTag), true, !me.drained(), mine));
        Map<String, Digest> theirs = digests.get() == null ? Map.of() : digests.get().peers();
        for (MemberTable.Known k : table.others()) {
            Member m = k.member();
            if (m.state() != MemberState.ALIVE) {
                continue;
            }
            Digest d = theirs.get(m.nodeId());
            State s = d == null ? State.NONE : State.parse(d.services().get(service));
            out.add(new Peer(m.nodeId(), m.url(), zone(m.tags(), zoneTag), false, m.routable(), s));
        }
        return out;
    }

    /** The zone of a member: the value of its tag {@code <tag>:<zone>}; empty without a zone tag or such a tag. */
    public static String zone(List<String> tags, String zoneTag) {
        if (zoneTag == null || zoneTag.isBlank() || tags == null) {
            return "";
        }
        String prefix = zoneTag.strip() + ":";
        for (String t : tags) {
            if (t != null && t.startsWith(prefix)) {
                return t.substring(prefix.length());
            }
        }
        return "";
    }

    private Map<String, String> headers(String node, Principal p, String accept) {
        String request = com.ash.drishti.server.net.NetCaller.current().map(com.ash.drishti.server.net.NetCaller.Caller::requestId)
                .orElseGet(() -> UUID.randomUUID().toString().substring(0, 13));
        Map<String, String> h = new LinkedHashMap<>();
        h.put(NetHeaders.HOP, "1");
        h.put(NetHeaders.REQUEST, request);
        if (p != null) {                                               // a person's request: the owner applies their rights too
            NetService n = net.get();
            h.put(NetHeaders.ON_BEHALF_OF, OnBehalfOf.mint(n.key(), p.user(), p.roles(), p.embedApp(), n.nodeId(), request, node));
        }
        h.put("Content-Type", "application/json");
        h.put("Accept", accept);
        return h;
    }

    private String url(String node) throws IOException {
        NetService n = net.get();
        return n.node().flatMap(x -> x.table().known(node)).map(k -> k.member().url())
                .orElseThrow(() -> new IOException("node " + node + " is not a known member"));
    }

    /** One signed call to a member, its answer read whole; IOException when the member cannot be reached. */
    public PeerHttp.Answer call(String node, String method, String pathAndQuery, byte[] body, Principal p, Duration timeout) throws IOException {
        try {
            return net.get().http().send(url(node), method, pathAndQuery, headers(node, p, "application/json"), body, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling " + node, e);
        }
    }

    /** A signed call whose answer is streamed (a shard's skeleton); the caller closes it. */
    public HttpResponse<InputStream> open(String node, String pathAndQuery, Duration timeout) throws IOException {
        try {
            return net.get().http().open(url(node), "GET", pathAndQuery, headers(node, null, "application/octet-stream"), null, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling " + node, e);
        }
    }
}
