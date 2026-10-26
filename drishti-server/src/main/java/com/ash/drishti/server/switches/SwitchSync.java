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
package com.ash.drishti.server.switches;

import com.ash.drishti.identity.SwitchLedger;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.member.MemberTable;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.net.NetService;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spreads the switches ledger across Drishti*net (docs/admin/COMMANDS.md, "Several servers"). The ledger's hash rides this node's member
 * entry in gossip; every {@link #LOOK_MS} this node compares it with each live peer's, and pulls the entries of a peer whose hash differs
 * ({@code GET /api/v1/net/switches}, signed) and merges them: the newer by (stamp, node) wins, written to this node's database before it
 * takes effect. Both sides of a difference pull, so a node that rejoins after missing changes, or one that changed something while it was
 * cut off, converges with the others within a gossip round or two (anti-entropy on join and rejoin). Without the cluster the ledger is
 * simply the node's own.
 */
public final class SwitchSync implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SwitchSync.class);
    /** How often the peers' hashes are compared (cheap). */
    static final long LOOK_MS = 250;
    /** A peer that still differs after a pull is asked again no sooner than this. */
    static final long RETRY_MS = 1_000;

    private final NetService net;
    private final SwitchLedger ledger;
    private final Map<String, Long> pulledAt = new ConcurrentHashMap<>();
    private volatile Thread loop;
    private volatile boolean closed;

    public SwitchSync(NetService net, SwitchLedger ledger) {
        this.net = net;
        this.ledger = ledger;
        ledger.onChange(moved -> net.switchesHash(ledger.hash()));
        net.switchesHash(ledger.hash());
        net.onChange(n -> ensureRunning());
        ensureRunning();
    }

    private void ensureRunning() {
        Thread t = loop;
        if (!closed && net.running() && (t == null || !t.isAlive())) {
            Thread next = Thread.ofVirtual().name("drishti-net-switches").unstarted(this::run);
            loop = next;
            next.start();
        }
    }

    private void run() {
        while (!closed && !Thread.currentThread().isInterrupted() && net.node().isPresent()) {
            try {
                round();
            } catch (RuntimeException e) {
                LOG.warn("net: switches round failed: {}", e.toString());
            }
            try {
                Thread.sleep(LOOK_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        loop = null;
    }

    /** One pass: pull from every live peer whose hash differs from this node's; the number of entries applied. */
    public int round() {
        NetService.Node n = net.node().orElse(null);
        if (n == null) {
            return 0;
        }
        net.switchesHash(ledger.hash());
        int applied = 0;
        long now = System.currentTimeMillis();
        for (MemberTable.Known k : n.table().others()) {
            Member m = k.member();
            if (m.state() != MemberState.ALIVE || m.protocol() != Member.PROTOCOL || m.switchesHash().isBlank()
                    || m.switchesHash().equals(ledger.hash()) || now - pulledAt.getOrDefault(m.nodeId(), 0L) < RETRY_MS) {
                continue;
            }
            pulledAt.put(m.nodeId(), now);
            List<SwitchLedger.Entry> theirs = pull(m);
            if (theirs != null) {
                List<SwitchLedger.Entry> got = ledger.merge(theirs);
                if (!got.isEmpty()) {
                    LOG.info("net: {} switch change(s) learned from {}: {}", got.size(), m.nodeId(),
                            got.stream().map(e -> e.scope() + " " + e.name() + (e.on() ? " on" : " off")).toList());
                }
                applied += got.size();
            }
        }
        pulledAt.keySet().removeIf(id -> n.table().known(id).isEmpty());
        return applied;
    }

    private List<SwitchLedger.Entry> pull(Member m) {
        try {
            PeerHttp.Answer a = net.http().control(m.url(), "GET", "/api/v1/net/switches", Map.of(), null, Duration.ofSeconds(3));
            if (a.status() != 200) {
                LOG.debug("net: switches of {} refused: HTTP {}", m.nodeId(), a.status());
                return null;
            }
            return fromWire(net.http().json().readTree(a.body()));
        } catch (IOException e) {
            LOG.debug("net: switches of {} not read: {}", m.nodeId(), e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The ledger as a peer reads it: {@code {node, hash, entries: [...]}}, times as epoch milliseconds. */
    public static Map<String, Object> toWire(String node, SwitchLedger ledger) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SwitchLedger.Entry e : ledger.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scope", e.scope());
            m.put("name", e.name());
            m.put("grantee", e.grantee());
            m.put("on", e.on());
            m.put("reason", e.reason());
            m.put("actor", e.actor());
            m.put("stamp", e.stamp());
            m.put("node", e.node());
            m.put("at", e.at() == null ? 0 : e.at().toEpochMilli());
            entries.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        out.put("hash", ledger.hash());
        out.put("entries", entries);
        return out;
    }

    static List<SwitchLedger.Entry> fromWire(JsonNode body) {
        List<SwitchLedger.Entry> out = new ArrayList<>();
        for (JsonNode e : body.path("entries")) {
            long at = e.path("at").asLong(0);
            out.add(new SwitchLedger.Entry(e.path("scope").asText(null), e.path("name").asText(null), e.path("grantee").asText("*"),
                    e.path("on").asBoolean(true), e.path("reason").asText(""), e.path("actor").asText(""), e.path("stamp").asLong(0),
                    e.path("node").asText(""), at == 0 ? null : Instant.ofEpochMilli(at)));
        }
        return out;
    }

    @Override
    public void close() {
        closed = true;
        Thread t = loop;
        if (t != null) {
            t.interrupt();
        }
    }
}
