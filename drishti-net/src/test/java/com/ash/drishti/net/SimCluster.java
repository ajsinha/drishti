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
package com.ash.drishti.net;

import com.ash.drishti.net.config.GossipSettings;
import com.ash.drishti.net.member.GossipTransport;
import com.ash.drishti.net.member.Gossiper;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.member.MemberTable;
import com.ash.drishti.net.member.NetClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-process cluster for the membership tests: nodes whose gossip goes through a map instead of HTTP, one fake clock moved a
 * second per round, and a switch per node (up, down) and per pair (a network split).
 */
final class SimCluster {

    /** One node of the cluster. */
    static final class Node {
        final String id;
        final String url;
        final MemberTable table;
        final Gossiper gossiper;
        volatile boolean up = true;

        Node(String id, String url, MemberTable table, Gossiper gossiper) {
            this.id = id;
            this.url = url;
            this.table = table;
            this.gossiper = gossiper;
        }

        MemberState stateOf(String other) {
            return table.known(other).map(k -> k.member().state()).orElse(null);
        }
    }

    final AtomicLong now = new AtomicLong(1_760_000_000_000L);
    final NetClock clock = now::get;
    final Map<String, Node> byUrl = new LinkedHashMap<>();
    final Set<String> cut = new java.util.HashSet<>();
    private final Random random = new Random(42);
    GossipSettings settings = GossipSettings.DEFAULTS;

    static String url(String id) {
        return "http://" + id + ".test:18783";
    }

    Node add(String id, long incarnation, List<String> joinList) {
        Member self = new Member(id, url(id), incarnation, 0, MemberState.ALIVE, "", "test", Member.PROTOCOL, List.of(), now.get(), false);
        MemberTable t = new MemberTable(self, clock);
        Gossiper g = new Gossiper(t, () -> settings, new Bound(id), clock, random, Runnable::run);
        g.joinList(() -> joinList);
        Node n = new Node(id, url(id), t, g);
        byUrl.put(n.url, n);
        return n;
    }

    Node node(String id) {
        return byUrl.get(url(id));
    }

    /** One second: every node that is up runs a round. */
    void tick() {
        now.addAndGet(1000);
        for (Node n : new ArrayList<>(byUrl.values())) {
            if (n.up) {
                n.gossiper.round();
            }
        }
    }

    void split(Set<String> side) {
        for (Node a : byUrl.values()) {
            for (Node b : byUrl.values()) {
                if (side.contains(a.id) != side.contains(b.id)) {
                    cut.add(a.id + ">" + b.id);
                }
            }
        }
    }

    void heal() {
        cut.clear();
    }

    private Node reach(String from, String url) throws IOException {
        Node n = byUrl.get(url);
        if (n == null || !n.up || cut.contains(from + ">" + n.id)) {
            throw new IOException("connection refused: " + url);
        }
        return n;
    }

    /** The transport as seen from one node (so a split can cut it). */
    final class Bound implements GossipTransport {
        private final String from;

        Bound(String from) {
            this.from = from;
        }

        @Override
        public List<Member> exchange(String url, List<Member> mine) throws Exception {
            Node n = reach(from, url);
            n.gossiper.accept(mine);
            return n.table.entries();
        }

        @Override
        public boolean ping(String url) {
            try {
                reach(from, url);
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public boolean pingVia(String via, String target) {
            try {
                Node v = reach(from, via);
                return v.gossiper != null && new Bound(v.id).ping(target);
            } catch (IOException e) {
                return false;
            }
        }
    }
}
