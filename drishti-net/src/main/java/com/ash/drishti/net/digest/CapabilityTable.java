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
package com.ash.drishti.net.digest;

import com.ash.drishti.net.member.Member;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Kind to the nodes that render it and the nodes that hold its data (DRISHTI_NET.md section 6), built from every node's digest and
 * the member table: an immutable snapshot, rebuilt whenever a digest or a member's state changes. Routing reads it filtered to the
 * nodes a caller's roles may reach ({@link #reachable}); Admin → Net shows it whole, with its warnings.
 */
public final class CapabilityTable {

    /** A node with its member entry and its digest. {@code routable}: alive, our protocol, not drained (this node always is). */
    public record Node(Member member, Digest digest, boolean self, boolean routable, double latencyMs) {
        public String id() {
            return member.nodeId();
        }
    }

    /** A node that renders a kind: its pack and the pack's version there. */
    public record Renderer(String nodeId, boolean self, String pack, String packVersion, String packHash, boolean peerOnly) {}

    /** A source of a kind on a node. */
    public record Holder(String nodeId, boolean self, Digest.SourceEntry source) {}

    /** Something an administrator should look at. */
    public record Warning(String what, String text) {}

    public static final CapabilityTable EMPTY = new CapabilityTable("", Map.of());

    private final String self;
    private final Map<String, Node> nodes;

    private CapabilityTable(String self, Map<String, Node> nodes) {
        this.self = self;
        this.nodes = nodes;
    }

    /**
     * @param self this node's id
     * @param selfDigest this node's digest
     * @param members every member as the table knows it (this node's own entry included)
     * @param digests the peers' digests by node id
     * @param latency the latency this node measured to each peer, by node id
     */
    public static CapabilityTable of(String self, Digest selfDigest, Collection<Member> members, Map<String, Digest> digests, Map<String, Double> latency) {
        Map<String, Node> out = new LinkedHashMap<>();
        for (Member m : members) {
            boolean me = m.nodeId().equals(self);
            Digest d = me ? selfDigest : digests.get(m.nodeId());
            if (d == null) {
                continue;
            }
            out.put(m.nodeId(), new Node(m, d, me, me || m.routable(), me ? 0 : latency.getOrDefault(m.nodeId(), -1.0)));
        }
        return new CapabilityTable(self, java.util.Collections.unmodifiableMap(out));
    }

    public String self() {
        return self;
    }

    public Collection<Node> nodes() {
        return nodes.values();
    }

    public Optional<Node> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    /** The same table with only the nodes {@code reach} lets through (this node always stays): what a caller's roles may reach. */
    public CapabilityTable reachable(Predicate<Node> reach) {
        Map<String, Node> out = new LinkedHashMap<>();
        nodes.forEach((id, n) -> {
            if (n.self() || reach.test(n)) {
                out.put(id, n);
            }
        });
        return new CapabilityTable(self, java.util.Collections.unmodifiableMap(out));
    }

    /** Only the nodes a request may be sent to (alive, our protocol, not drained), and this one. */
    public CapabilityTable routable() {
        return reachable(Node::routable);
    }

    /** Every node that renders the kind, this one first, then by node id. */
    public List<Renderer> renderers(String kind) {
        List<Renderer> out = new ArrayList<>();
        for (Node n : ordered()) {
            n.digest().kind(kind).filter(Digest.KindEntry::renders).ifPresent(k -> {
                var p = n.digest().pack(k.pack()).orElse(null);
                out.add(new Renderer(n.id(), n.self(), k.pack(), p == null ? "" : p.version(), p == null ? "" : p.hash(), p != null && p.peerOnly()));
            });
        }
        return out;
    }

    /** Every source of the kind on every node, this one first. */
    public List<Holder> holders(String kind) {
        List<Holder> out = new ArrayList<>();
        for (Node n : ordered()) {
            n.digest().kind(kind).ifPresent(k -> k.sources().forEach(s -> out.add(new Holder(n.id(), n.self(), s))));
        }
        return out;
    }

    /** The newest version of a pack among the nodes of this table, or empty when none has it. */
    public Optional<String> newest(String pack) {
        return nodes.values().stream().map(n -> n.digest().pack(pack)).flatMap(Optional::stream).map(Digest.PackEntry::version).max(Versions.ORDER);
    }

    /** The kind a mnemonic names, as this node says, else the first peer that knows it. */
    public Optional<String> kindOfMnemonic(String code) {
        for (Node n : ordered()) {
            Optional<String> k = n.digest().kindOfMnemonic(code);
            if (k.isPresent()) {
                return k;
            }
        }
        return Optional.empty();
    }

    /** The pack that owns the kind on any node, this one first. */
    public Optional<String> packOf(String kind) {
        for (Node n : ordered()) {
            Optional<Digest.KindEntry> k = n.digest().kind(kind);
            if (k.isPresent()) {
                return Optional.of(k.get().pack());
            }
        }
        return Optional.empty();
    }

    /**
     * The peers a search of the kind asks, besides this node (DRISHTI_NET.md sections 8 and 10): a peer whose every source of the kind
     * reads a table this node already reads (a replica) is not asked; of several peers that read the same tables, one is (the lowest
     * latency, then the lowest id). A source with no table identity is unique to its node, a shard: its node is always asked.
     */
    public List<String> scatterTargets(String kind) {
        Set<String> covered = new LinkedHashSet<>();
        Node me = nodes.get(self);
        if (me != null) {
            me.digest().kind(kind).ifPresent(k -> k.sources().forEach(s -> covered.add(identity(self, s))));
        }
        Map<String, Set<String>> byNode = new LinkedHashMap<>();
        for (Node n : ordered()) {
            if (n.self()) {
                continue;
            }
            n.digest().kind(kind).ifPresent(k -> {
                Set<String> ids = new TreeSet<>();
                k.sources().forEach(s -> ids.add(identity(n.id(), s)));
                ids.removeAll(covered);
                if (!ids.isEmpty()) {
                    byNode.put(n.id(), ids);
                }
            });
        }
        // the fewest nodes that cover every remaining table: the fastest first, each taking what it reads
        List<String> chosen = new ArrayList<>();
        List<String> order = new ArrayList<>(byNode.keySet());
        order.sort(Comparator.comparingDouble((String id) -> latencyOrMax(nodes.get(id))).thenComparing(id -> id));
        Set<String> left = new TreeSet<>();
        byNode.values().forEach(left::addAll);
        for (String id : order) {
            Set<String> mine = new TreeSet<>(byNode.get(id));
            mine.retainAll(left);
            if (!mine.isEmpty()) {
                chosen.add(id);
                left.removeAll(mine);
            }
        }
        return chosen;
    }

    private static double latencyOrMax(Node n) {
        return n == null || n.latencyMs() < 0 ? Double.MAX_VALUE : n.latencyMs();
    }

    /** A source's table identity, or one unique to its node when it has none. */
    static String identity(String node, Digest.SourceEntry s) {
        return s.table() == null || s.table().isBlank() ? "node:" + node + "/" + s.connector() : s.table();
    }

    private List<Node> ordered() {
        List<Node> out = new ArrayList<>(nodes.values());
        out.sort(Comparator.comparing((Node n) -> !n.self()).thenComparing(Node::id));
        return out;
    }

    /** The digest service a member that runs an edition publishes its name under (none: the full product). */
    public static final String EDITION_SERVICE = "edition";

    /**
     * What Admin → Net should show: one version of a pack with different contents, different calendars, different mask lists, different
     * editions (drishti.edition: every member of a cluster should run the same one).
     */
    public List<Warning> warnings() {
        List<Warning> out = new ArrayList<>();
        Map<String, Map<String, Set<String>>> hashes = new TreeMap<>();     // pack -> version -> hashes
        Map<String, Set<String>> calendars = new TreeMap<>();
        Map<String, Set<String>> redacts = new TreeMap<>();
        for (Node n : ordered()) {
            n.digest().packs().forEach(p -> hashes.computeIfAbsent(p.name(), x -> new TreeMap<>()).computeIfAbsent(p.version(), x -> new TreeSet<>()).add(p.hash()));
            calendars.computeIfAbsent(n.digest().calendar(), x -> new TreeSet<>()).add(n.id());
            redacts.computeIfAbsent(String.join(",", new TreeSet<>(n.digest().redact())), x -> new TreeSet<>()).add(n.id());
        }
        hashes.forEach((pack, versions) -> versions.forEach((version, h) -> {
            if (h.size() > 1) {
                out.add(new Warning("pack-content", pack + " " + version + " has different contents on different nodes: equal versions should be equal packs"));
            }
        }));
        if (calendars.size() > 1) {
            out.add(new Warning("calendar", "the nodes use different business calendars " + calendars
                    + ": a peer with another calendar is used for live reads only"));
        }
        Map<String, Set<String>> editions = new TreeMap<>();
        for (Node n : ordered()) {
            Map<String, String> svc = n.digest().services() == null ? Map.of() : n.digest().services();
            editions.computeIfAbsent(svc.getOrDefault(EDITION_SERVICE, "(none)"), x -> new TreeSet<>()).add(n.id());
        }
        if (editions.size() > 1) {
            out.add(new Warning("edition", "the nodes run different editions " + editions
                    + ": every member should set the same drishti.edition (each shows and refuses what its own edition says)"));
        }
        if (redacts.size() > 1) {
            out.add(new Warning("redact", "the nodes mask different fields " + redacts.values()
                    + ": each masks what it masks, and the node that shows a page applies its own masks again, so the stricter wins"));
        }
        nodes.values().stream().filter(n -> n.member().protocol() != Member.PROTOCOL)
                .forEach(n -> out.add(new Warning("protocol", n.id() + " speaks protocol " + n.member().protocol() + ": it is listed but not routed to")));
        return out;
    }
}
