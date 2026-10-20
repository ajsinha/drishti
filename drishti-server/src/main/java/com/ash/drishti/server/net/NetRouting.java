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

import com.ash.drishti.net.config.AccessSettings;
import com.ash.drishti.net.digest.CapabilityTable;
import com.ash.drishti.net.digest.Digest;
import com.ash.drishti.net.digest.Versions;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where a request goes (DRISHTI_NET.md section 7), the three questions in order. 1: can this node answer it (its pack is on here, is
 * the newest version of that pack among the nodes the caller may reach, and is not peer-only for this node's own users)? Then
 * locally, and when the data is not here the router asks a peer for the document (data forwarding). 2: is the pack missing here, older
 * here, or peer-only here? Then the whole request goes to a node that renders the kind (view forwarding). Only nodes the caller's roles
 * may reach are ever considered, and never for a request another node forwarded (one hop). Stateless: every answer is read from the
 * capability table in force.
 */
public final class NetRouting {

    /** The route: answered here, the whole request forwarded, or not served anywhere the caller may reach. */
    public enum Route { LOCAL, VIEW, NONE }

    /**
     * Where a request goes and why.
     *
     * @param route the route
     * @param reason one sentence, as Admin → Net → Routing shows it
     * @param targets for {@code VIEW}, the nodes to forward to, best first (the next is tried once when the first fails)
     * @param unreachable nodes that would serve it but the caller's roles may not reach (shown to administrators)
     */
    public record Plan(Route route, String kind, String reason, List<String> targets, List<String> unreachable) {}

    private final NetService net;
    private final DigestService digests;
    private final PackRegistry packs;
    private final PackAccess packAccess;

    public NetRouting(NetService net, DigestService digests, PackRegistry packs, PackAccess packAccess) {
        this.net = net;
        this.digests = digests;
        this.packs = packs;
        this.packAccess = packAccess;
    }

    /** True when requests of this node's own clients may leave it: the cluster runs, forwarding is on (max-hops 1). */
    public boolean forwarding() {
        return net.running() && net.config().routing().forwarding();
    }

    /**
     * The capability table cut to the nodes a request may go to for this caller: routable now (the member table's newest word, which may
     * be newer than the capability table's), and reached by the caller's roles.
     */
    public CapabilityTable tableFor(Principal p) {
        AccessSettings a = net.config().access();
        List<String> roles = p == null ? List.of() : p.roles();
        var live = net.node().map(n -> n.table()).orElse(null);
        return digests.table().routable().reachable(n -> a.reaches(roles, n.id(), n.member().tags())
                && (live == null || live.known(n.id()).map(k -> k.member().routable()).orElse(false)));
    }

    /** The nodes that would serve the kind but the caller's roles do not reach (for the explanation). */
    private List<String> unreachable(String kind, Principal p) {
        CapabilityTable reach = tableFor(p);
        List<String> out = new ArrayList<>();
        for (CapabilityTable.Renderer r : digests.table().routable().renderers(kind)) {
            if (reach.node(r.nodeId()).isEmpty()) {
                out.add(r.nodeId());
            }
        }
        return out;
    }

    /** The pack that owns the kind here, when it is on here and this caller may use it here (a peer-only pack only for a peer). */
    public Optional<Pack> localPack(String kind) {
        String owner = packAccess.ownerOf(kind);
        if (owner == null || !packAccess.isEnabled(owner)) {
            return Optional.empty();
        }
        if (net.config().node().peerOnlyPacks().contains(owner) && !NetCaller.fromPeer()) {
            return Optional.empty();
        }
        return packs.packs().stream().filter(x -> x.name().equals(owner)).findFirst();
    }

    /** True when a pack this node loads is served to peers only (hidden from its own users). */
    public boolean peerOnly(String pack) {
        return net.config().node().peerOnlyPacks().contains(pack);
    }

    /** True when the kind's pack is here but served to peers only, and the caller is this node's own client. */
    public boolean hiddenHere(String kind) {
        String owner = packAccess.ownerOf(kind);
        return owner != null && peerOnly(owner) && !NetCaller.fromPeer();
    }

    /**
     * The route of a request that names one kind (a view, its raw document, its history, a search of it).
     *
     * @param dated true when the request asks for a business date other than live: a peer with another calendar is not used
     * @param entityId the entity, for a stable choice among equal peers (or null)
     */
    public Plan view(String kind, Principal p, boolean dated, String entityId) {
        if (!forwarding() || NetCaller.fromPeer()) {
            return new Plan(Route.LOCAL, kind, NetCaller.fromPeer() ? "forwarded here by another node: answered here only (one hop)"
                    : "the cluster is off or forwarding is switched off", List.of(), List.of());
        }
        CapabilityTable t = calendarCut(tableFor(p), dated);
        Optional<Pack> local = localPack(kind);
        String pack = local.map(Pack::name).orElseGet(() -> t.packOf(kind).orElse(null));
        List<CapabilityTable.Renderer> others = t.renderers(kind).stream().filter(r -> !r.self()).toList();
        if (local.isPresent()) {
            String newest = t.newest(local.get().name()).orElse(local.get().version());
            if (Versions.compare(local.get().version(), newest) >= 0) {
                return new Plan(Route.LOCAL, kind, "the pack " + pack + " " + local.get().version() + " is here and is the newest the caller may reach",
                        List.of(), unreachable(kind, p));
            }
            List<CapabilityTable.Renderer> newer = others.stream().filter(r -> r.packVersion().equals(newest)).toList();
            if (!newer.isEmpty()) {
                return new Plan(Route.VIEW, kind, "the pack " + pack + " is " + local.get().version() + " here and " + newest + " on "
                        + newer.get(0).nodeId() + ": the newest is served", order(newer, t, entityId, kind), unreachable(kind, p));
            }
            return new Plan(Route.LOCAL, kind, "the pack " + pack + " is here", List.of(), unreachable(kind, p));
        }
        if (others.isEmpty()) {
            List<String> blocked = unreachable(kind, p);
            List<String> down = downRenderers(kind);
            return new Plan(Route.NONE, kind, !down.isEmpty() ? kind + " is rendered by nodes that cannot be reached now: " + String.join(", ", down)
                    : blocked.isEmpty() ? "no node the caller may reach renders " + kind
                    : kind + " is rendered only by nodes the caller's roles may not reach " + blocked, List.of(), blocked);
        }
        String newest = others.stream().map(CapabilityTable.Renderer::packVersion).max(Versions.ORDER).orElse("");
        List<CapabilityTable.Renderer> best = others.stream().filter(r -> r.packVersion().equals(newest)).toList();
        String why = packAccess.ownerOf(kind) != null && peerOnly(packAccess.ownerOf(kind))
                ? "the pack " + pack + " is served to peers only here" : "the pack " + pack + " is not on this node";
        return new Plan(Route.VIEW, kind, why + ": forwarded to a node that renders " + kind + " (" + pack + " " + newest + ")",
                order(best, t, entityId, kind), unreachable(kind, p));
    }

    /**
     * The route of a BI report's request (DRISHTI_NET.md section 8: a BI query runs on the node that holds the model's data). Answered here
     * when this node has the report and its sources say they hold every kind its datasets read; else forwarded to the peers that list the report
     * and hold those kinds, the surest first; answered here (with its own answer) when no peer does.
     *
     * @param kinds the kinds the report's datasets read here, or empty when the report is not here
     */
    public Plan report(String id, java.util.Set<String> kinds, Principal p) {
        if (!forwarding() || NetCaller.fromPeer()) {
            return new Plan(Route.LOCAL, null, "answered here", List.of(), List.of());
        }
        CapabilityTable t = tableFor(p);
        boolean allHere = !kinds.isEmpty() && kinds.stream().allMatch(k -> evidence(t, t.self(), k) >= 10);
        if (allHere) {
            return new Plan(Route.LOCAL, null, "the report and the data of " + kinds + " are here", List.of(), List.of());
        }
        List<String> peers = new ArrayList<>();
        for (CapabilityTable.Node n : t.nodes()) {
            if (!n.self() && n.digest().reports().contains(id)) {
                peers.add(n.id());
            }
        }
        Comparator<String> surest = Comparator.comparingInt((String n) -> -kinds.stream().mapToInt(k -> evidence(t, n, k)).sum()).thenComparing(choice(t, id));
        peers.sort(surest);
        if (!kinds.isEmpty()) {
            peers.removeIf(n -> kinds.stream().anyMatch(k -> evidence(t, n, k) < 10));
        }
        if (peers.isEmpty()) {
            return new Plan(Route.LOCAL, null, kinds.isEmpty() ? "no node the caller may reach has the report" : "no other node holds " + kinds,
                    List.of(), List.of());
        }
        return new Plan(Route.VIEW, null, (kinds.isEmpty() ? "the report is not here" : "the data of " + kinds + " is not here") + ": forwarded to "
                + peers.get(0), peers, List.of());
    }

    /** Peers with another business calendar answer only live reads. */
    private CapabilityTable calendarCut(CapabilityTable t, boolean dated) {
        if (!dated) {
            return t;
        }
        String mine = t.node(t.self()).map(n -> n.digest().calendar()).orElse("");
        return t.reachable(n -> mine.isEmpty() || mine.equals(n.digest().calendar()));
    }

    /**
     * Renderers, best first: those that also hold the kind's data (a forwarded request is answered from that node only, one hop, so a
     * node without the data could only say it does not have it), then the lowest latency (in steps of 10 ms times the latency weight),
     * then a stable hash of the entity id.
     */
    private List<String> order(List<CapabilityTable.Renderer> rs, CapabilityTable t, String entityId, String kind) {
        List<String> ids = new ArrayList<>(rs.stream().map(CapabilityTable.Renderer::nodeId).toList());
        Comparator<String> holdsFirst = Comparator.comparingInt((String id) -> -evidence(t, id, kind));
        ids.sort(holdsFirst.thenComparing(choice(t, entityId)));
        return ids;
    }

    /**
     * How surely the node holds the kind's data, from its digest: its sources that say they hold some (a newest business date, a data
     * version, or a live stream) count ten, any other source of the kind one (a store that serves every kind may hold nothing of it).
     */
    static int evidence(CapabilityTable t, String id, String kind) {
        return t.node(id).flatMap(n -> n.digest().kind(kind)).map(k -> k.sources().stream()
                .mapToInt(s -> !s.latestDate().isBlank() || !s.dataVersion().isBlank() || s.live() ? 10 : 1).sum()).orElse(0);
    }

    private Comparator<String> choice(CapabilityTable t, String entityId) {
        double w = net.config().routing().weights().getOrDefault("latency", 1.0);
        String key = entityId == null ? "" : entityId;
        return Comparator.comparingLong((String id) -> {
            double ms = t.node(id).map(CapabilityTable.Node::latencyMs).orElse(-1.0);
            return w <= 0 ? 0 : ms < 0 ? Long.MAX_VALUE : (long) (ms / (10 * w));
        }).thenComparingInt(id -> Math.floorMod((id + "\u001f" + key).hashCode(), 1 << 20)).thenComparing(id -> id);
    }

    /**
     * The peers that hold the kind's data, for a read none of this node's sources answered (data forwarding), best first: alive and
     * reachable, holding the date asked (by their newest business date), then the lowest latency, then the entity's stable hash. A peer
     * whose sources all read tables this node reads too (a replica) is left out: it would answer as this node did.
     */
    public List<String> dataPeers(String kind, Principal p, LocalDate date, boolean live, String entityId) {
        if (!forwarding() || NetCaller.fromPeer()) {
            return List.of();
        }
        CapabilityTable t = calendarCut(tableFor(p), !live);
        java.util.Set<String> mine = new java.util.HashSet<>();
        t.node(t.self()).flatMap(n -> n.digest().kind(kind)).ifPresent(k -> k.sources().forEach(s -> {
            if (s.table() != null && !s.table().isBlank()) {
                mine.add(s.table());
            }
        }));
        List<String> out = new ArrayList<>();
        List<String> later = new ArrayList<>();
        for (CapabilityTable.Node n : t.nodes()) {
            if (n.self()) {
                continue;
            }
            Optional<Digest.KindEntry> k = n.digest().kind(kind);
            if (k.isEmpty() || k.get().sources().isEmpty()) {
                continue;
            }
            boolean replica = k.get().sources().stream().allMatch(s -> s.table() != null && !s.table().isBlank() && mine.contains(s.table()));
            if (replica) {
                continue;
            }
            boolean holdsDate = date == null || k.get().sources().stream().anyMatch(s -> s.latestDate() == null || s.latestDate().isBlank()
                    || !s.dated() || !LocalDate.parse(s.latestDate()).isBefore(date));
            (holdsDate ? out : later).add(n.id());
        }
        Comparator<String> best = Comparator.comparingInt((String id) -> -evidence(t, id, kind)).thenComparing(choice(t, entityId));
        out.sort(best);
        later.sort(best);
        out.addAll(later);
        return out;
    }

    /** The member as the member table says now (newer than the capability table's copy). */
    private com.ash.drishti.net.member.Member live(CapabilityTable.Node n) {
        return net.node().flatMap(x -> x.table().known(n.id())).map(k -> k.member()).orElse(n.member());
    }

    private boolean liveRoutable(CapabilityTable.Node n) {
        return n.routable() && live(n).routable();
    }

    /**
     * The state a refusal names a node by. A node that gossip says is alive again while this node's table has not taken it back yet (its
     * digest has not arrived) is {@code rejoining}, never {@code alive}: a refusal naming an alive node as unreachable reads as a fault
     * (SOAK2-05).
     */
    private String state(CapabilityTable.Node n) {
        return label(live(n), n.routable());
    }

    /** The state of {@code live} (gossip's view of the member) as a refusal names it, given whether this node's table routes to it. */
    public static String label(com.ash.drishti.net.member.Member live, boolean inTable) {
        if (live.drained()) {
            return "drained";
        }
        return live.routable() && !inTable ? "rejoining" : live.state().wire();
    }

    /** Nodes that render the kind but are not routable now (suspect, dead, left, drained), with their state. */
    public List<String> downRenderers(String kind) {
        List<String> out = new ArrayList<>();
        for (CapabilityTable.Node n : digests.table().nodes()) {
            if (!n.self() && !liveRoutable(n) && n.digest().kind(kind).isPresent()) {
                out.add(n.id() + " (" + state(n) + ")");
            }
        }
        return out;
    }

    /** Nodes that hold the kind but are not routable now (suspect, dead, drained): a read that finds nothing says so. */
    public List<String> downHolders(String kind) {
        List<String> out = new ArrayList<>();
        for (CapabilityTable.Node n : digests.table().nodes()) {
            if (!n.self() && !liveRoutable(n) && n.digest().kind(kind).map(k -> !k.sources().isEmpty()).orElse(false)) {
                out.add(n.id() + " (" + state(n) + ")");
            }
        }
        return out;
    }

    /**
     * The nodes that hold the kind, are not routable now and may hold {@code date}: one of their sources of the kind keeps no dates, cannot
     * say its range, or held the date by the range its digest last gave (oldest to newest business date) before the node went down (SOAK-01).
     */
    public List<String> downHolders(String kind, LocalDate date) {
        List<String> out = new ArrayList<>();
        for (CapabilityTable.Node n : digests.table().nodes()) {
            if (!n.self() && !liveRoutable(n) && n.digest().kind(kind).map(k -> k.sources().stream().anyMatch(s -> s.mayHold(date))).orElse(false)) {
                out.add(n.id() + " (" + state(n) + ")");
            }
        }
        return out;
    }

    /** The kind a mnemonic names on any node the caller may reach (for a search of a kind whose pack is not here). */
    public Optional<String> kindOfMnemonic(String code, Principal p) {
        return tableFor(p).kindOfMnemonic(code);
    }

    public NetService net() {
        return net;
    }

    public DigestService digests() {
        return digests;
    }
}
