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

import com.ash.drishti.net.config.NetSchema;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.member.MemberTable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What Admin → Net, the console's discovery and {@code drishti.py net} read: the member table, this node and its files, as
 * plain maps. Nothing here holds or shows a key: only whether one is set.
 */
public final class NetViews {

    private final NetService net;
    private volatile Function<String, Map<String, Object>> digestSummary = id -> Map.of();

    public NetViews(NetService net) {
        this.net = net;
    }

    /** What each member offers in brief (its packs, kinds, load), from its digest; set by the digest service. */
    public void digestSummary(Function<String, Map<String, Object>> f) {
        this.digestSummary = f == null ? id -> Map.of() : f;
    }

    /** The whole table for administrators and nodes: this node first, then every other member, with what this node knows of each. */
    public Map<String, Object> members() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", net.config().enabled());
        out.put("running", net.running());
        out.put("self", net.nodeId());
        out.put("node", node());
        List<Map<String, Object>> rows = new ArrayList<>();
        net.node().ifPresent(n -> {
            Member self = n.table().self();
            rows.add(row(self, null, true));
            n.table().others().stream().sorted(java.util.Comparator.comparing(k -> k.member().nodeId())).forEach(k -> rows.add(row(k.member(), k, false)));
        });
        out.put("members", rows);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (MemberState s : MemberState.values()) {
            counts.put(s.wire(), rows.stream().filter(r -> s.wire().equals(r.get("state"))).count());
        }
        out.put("counts", counts);
        out.put("seeds", net.config().peers().stream().map(p -> Map.of("name", p.name(), "url", p.url(), "tags", p.tags())).toList());
        out.put("peersFile", Map.of("path", net.peersFile().toString(), "savedAt", net.lastSaved() == 0 ? "" : Instant.ofEpochMilli(net.lastSaved()).toString()));
        out.put("problems", net.loaded().all().stream().map(NetViews::problem).toList());
        return out;
    }

    /** This node: its id, address, tags, peer-only packs, drain, and whether the key is set (never the key). */
    public Map<String, Object> node() {
        Map<String, Object> m = new LinkedHashMap<>();
        var c = net.config();
        m.put("enabled", c.enabled());
        m.put("nodeId", net.nodeId());
        m.put("advertiseUrl", c.node().advertiseUrl());
        m.put("tags", c.node().tags());
        m.put("peerOnlyPacks", c.node().peerOnlyPacks());
        m.put("keySet", net.key().set());
        m.put("previousKeySet", net.key().previousSet());
        m.put("version", net.version());
        m.put("drained", net.node().map(n -> n.table().self().drained()).orElse(false));
        m.put("leaving", net.node().map(n -> n.table().self().state() == MemberState.LEFT).orElse(false));
        m.put("dir", net.folder().dir().toString());
        m.put("watch", net.watchState());
        m.put("gossip", Map.of("interval", NetSchema.text(c.gossip().interval()), "fanout", c.gossip().fanout(),
                "suspectAfter", NetSchema.text(c.gossip().suspectAfter()), "deadAfter", NetSchema.text(c.gossip().deadAfter()),
                "rounds", net.node().map(n -> n.gossiper().rounds()).orElse(0L), "exchangesFailed", net.node().map(n -> n.gossiper().exchangesFailed()).orElse(0L)));
        return m;
    }

    private Map<String, Object> row(Member m, MemberTable.Known k, boolean self) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nodeId", m.nodeId());
        r.put("self", self);
        r.put("url", m.url());
        r.put("state", m.state().wire());
        r.put("incarnation", m.incarnation());
        r.put("heartbeat", m.heartbeat());
        r.put("version", m.version());
        r.put("protocol", m.protocol());
        r.put("tags", m.tags());
        r.put("drained", m.drained());
        r.put("since", m.since() == 0 ? "" : Instant.ofEpochMilli(m.since()).toString());
        r.put("lastHeard", k == null ? "" : Instant.ofEpochMilli(k.heardAt()).toString());
        r.put("latencyMs", k == null || k.latencyMs() < 0 ? null : Math.round(k.latencyMs() * 10) / 10.0);
        r.put("digestHash", m.digestHash());
        r.putAll(digestSummary.apply(m.nodeId()));
        return r;
    }

    /** For the console's discovery (any signed-in caller): the alive members' addresses, tags and packs, this node first. */
    public List<Map<String, Object>> discovery() {
        List<Map<String, Object>> out = new ArrayList<>();
        net.node().ifPresent(n -> {
            List<Member> list = new ArrayList<>();
            list.add(n.table().self());
            list.addAll(n.table().entries().subList(1, n.table().entries().size()));
            for (Member m : list) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("nodeId", m.nodeId());
                r.put("url", m.url());
                r.put("state", m.state().wire());
                r.put("tags", m.tags());
                Object packs = digestSummary.apply(m.nodeId()).get("packs");
                r.put("packs", packs == null ? List.of() : packs);
                out.add(r);
            }
        });
        return out;
    }

    static Map<String, Object> problem(NetSchema.Problem p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", p.file());
        m.put("level", p.level());
        m.put("field", p.field());
        m.put("line", p.line());
        m.put("message", p.message());
        return m;
    }
}
