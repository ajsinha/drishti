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

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.net.digest.CapabilityTable;
import com.ash.drishti.net.digest.Digest;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberTable;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.api.RequestActivity;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.SecurityProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This node's capability digest, the peers' digests and the capability table built from them (DRISHTI_NET.md section 6). While the
 * node runs, a virtual thread rebuilds this node's digest every gossip interval (packs, kinds, sources, data versions, settings: all
 * cheap reads), and gives its hash to the member entry when it changed, so gossip carries it; it pulls a peer's digest
 * ({@code GET /api/v1/net/digest}) when the hash in gossip differs from the one it holds, or when the one it holds is a minute old (its
 * load), and drops the digests of members that are gone. The capability table is swapped whole after each change.
 */
public final class DigestService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DigestService.class);
    private static final long REFRESH_MS = 60_000;

    private final NetService net;
    private final PackRegistry packs;
    private final PackAccess packAccess;
    private final Mnemonics mnemonics;
    private final SourceRegistry sources;
    private final Supplier<String> calendar;
    private final RequestActivity activity;
    private final SecurityProperties security;
    private final Map<String, Digest> peers = new ConcurrentHashMap<>();
    private final Map<String, Long> pulledAt = new ConcurrentHashMap<>();
    private final Map<String, String> packHashes = new ConcurrentHashMap<>();
    private volatile Supplier<List<String>> reports = List::of;
    /** The cluster services this node runs and their state now (VaR shards, 2.9.6), published in its digest. */
    private final Map<String, Supplier<String>> services = new ConcurrentHashMap<>();
    private volatile Digest self;
    private volatile CapabilityTable table = CapabilityTable.EMPTY;
    private volatile Thread loop;
    private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public DigestService(NetService net, PackRegistry packs, PackAccess packAccess, Mnemonics mnemonics, SourceRegistry sources, Supplier<String> calendar,
            RequestActivity activity, SecurityProperties security) {
        this.net = net;
        this.packs = packs;
        this.packAccess = packAccess;
        this.mnemonics = mnemonics;
        this.sources = sources;
        this.calendar = calendar;
        this.activity = activity;
        this.security = security;
        net.onChange(n -> ensureRunning());
        ensureRunning();
    }

    /** The BI report ids this node serves (wired by the BI layer). */
    public void reports(Supplier<List<String>> ids) {
        this.reports = ids == null ? List::of : ids;
    }

    /**
     * A cluster service's state, published in this node's digest under {@code name} and rebuilt every round (VaR shards: the shards this
     * node holds, VAR_SHARDING.md); a change raises the digest's hash, so peers pull it within a round. Null removes it.
     */
    public void service(String name, Supplier<String> state) {
        if (state == null) {
            services.remove(name);
        } else {
            services.put(name, state);
        }
    }

    /** Builds this node's digest now and announces a new hash at once (a service's state changed and peers should know soon). */
    public void announce() {
        NetService.Node n = net.node().orElse(null);
        if (n == null) {
            return;
        }
        Digest mine = build(n);
        boolean changed = self == null || !self.hash().equals(mine.hash());
        self = mine;
        if (changed) {
            net.digestHash(mine.hash());
        }
    }

    /** Told after the capability table changed (the peer sources, the router). */
    public void onChange(Runnable r) {
        listeners.add(r);
    }

    private void ensureRunning() {
        Thread t = loop;
        if (net.running() && (t == null || !t.isAlive())) {
            Thread next = Thread.ofVirtual().name("drishti-net-digest").unstarted(this::run);
            loop = next;
            next.start();
        }
    }

    /** How often the peers' digest hashes are compared (cheap): a changed digest is pulled within this of gossip bringing its hash. */
    private static final long LOOK_MS = 250;

    private void run() {
        long built = 0;
        while (!Thread.currentThread().isInterrupted() && net.node().isPresent()) {
            try {
                long now = System.currentTimeMillis();
                boolean rebuild = now - built >= net.config().gossip().interval().toMillis();
                tick(rebuild);
                if (rebuild) {
                    built = now;
                }
            } catch (RuntimeException e) {
                LOG.warn("net: digest round failed: {}", e.toString());
            }
            try {
                Thread.sleep(Math.min(LOOK_MS, Math.max(50, net.config().gossip().interval().toMillis())));
            } catch (InterruptedException e) {
                return;
            }
        }
        peers.clear();
        pulledAt.clear();
        table = CapabilityTable.EMPTY;
        loop = null;
    }

    /** One full pass: rebuild this node's digest, pull the peers' that changed, rebuild the table. */
    public void tick() {
        tick(true);
    }

    private void tick(boolean rebuild) {
        NetService.Node n = net.node().orElse(null);
        if (n == null) {
            return;
        }
        Digest mine = rebuild || self == null ? build(n) : self;
        boolean changed = self == null || !self.hash().equals(mine.hash());
        self = mine;
        if (changed) {
            net.digestHash(mine.hash());
        }
        Map<String, Double> latency = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (MemberTable.Known k : n.table().others()) {
            Member m = k.member();
            latency.put(m.nodeId(), k.latencyMs());
            if (m.state().gone()) {
                continue;                                  // its last digest is kept (not routed to) until the member is forgotten: a read says who holds the data
            }
            Digest held = peers.get(m.nodeId());
            boolean stale = held == null || !held.hash().equals(m.digestHash()) || now - pulledAt.getOrDefault(m.nodeId(), 0L) > REFRESH_MS;
            if (stale && !m.digestHash().isBlank() && m.protocol() == Member.PROTOCOL) {
                Digest got = pull(m);
                if (got != null) {
                    changed |= held == null || !held.hash().equals(got.hash());
                    peers.put(m.nodeId(), got);
                    pulledAt.put(m.nodeId(), now);
                }
            }
        }
        peers.keySet().removeIf(id -> n.table().known(id).isEmpty());
        CapabilityTable next = CapabilityTable.of(n.id(), mine, n.table().byId().values(), Map.copyOf(peers), latency);
        boolean statesMoved = !sameStates(table, next);
        table = next;
        if (settledFor != n && firstDigestsIn(n)) {
            settled(n, "it has its peers' digests (" + peers.size() + ")");
        }
        if (changed || statesMoved) {
            listeners.forEach(r -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOG.warn("net: capability listener failed: {}", e.toString());
                }
            });
        }
    }

    // -- joining (SOAK3-01) ---------------------------------------------------------------------------------------------

    /** The running node that has had its first membership and digest exchange (or gave up waiting for it). */
    private volatile NetService.Node settledFor;
    private final java.util.concurrent.locks.ReentrantLock joinLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition joinMoved = joinLock.newCondition();

    /**
     * True while this node is part of a cluster but does not yet know what its peers hold: node.yaml has it on and the server is still
     * starting (it has not joined), or it has joined with seeds or saved members and has not had their digests yet, for at most
     * routing.yaml's {@code join-grace} after it joined. A read that no source here holds must not then say "no source holds" (SOAK3-01:
     * a node just restarted answered 404 DRS-1001 for trades only its peers hold). False for a single server and for a node with no seeds
     * and no saved members.
     */
    public boolean joining() {
        if (!net.config().enabled()) {
            return false;
        }
        NetService.Node n = net.node().orElse(null);
        if (n == null) {
            return net.awaitingJoin();
        }
        if (n.leaving || settledFor == n) {
            return false;
        }
        if (!n.expectsPeers()) {
            settled(n, "it has no seeds and no saved members");
            return false;
        }
        if (System.currentTimeMillis() - n.started().toEpochMilli() >= net.config().routing().joinGrace().toMillis()) {
            settled(n, "join-grace (" + net.config().routing().joinGrace().toSeconds() + " s) passed without every peer's digest");
            return false;
        }
        return true;
    }

    /**
     * Waits while this node is {@link #joining()}, at most {@code max}; true when it is no longer joining. A read none of this node's sources
     * holds calls it before asking the peers, so its answer comes from them a moment later instead of "not found".
     */
    public boolean awaitJoined(Duration max) {
        long end = System.nanoTime() + (max == null ? 0 : max.toNanos());
        while (joining()) {
            long left = end - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            joinLock.lock();
            try {
                joinMoved.awaitNanos(Math.min(left, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !joining();
            } finally {
                joinLock.unlock();
            }
        }
        return true;
    }

    /**
     * The first exchange is done: some other member is known, and every other member that is alive and gossips a digest has had its
     * digest pulled (a peer that just started and has none yet is not waited for).
     */
    private boolean firstDigestsIn(NetService.Node n) {
        boolean any = false;
        for (MemberTable.Known k : n.table().others()) {
            Member m = k.member();
            if (m.state().gone()) {
                continue;
            }
            any = true;
            if (m.state() == com.ash.drishti.net.member.MemberState.ALIVE && m.protocol() == Member.PROTOCOL && !m.digestHash().isBlank()
                    && !peers.containsKey(m.nodeId())) {
                return false;
            }
        }
        return any;
    }

    private void settled(NetService.Node n, String why) {
        joinLock.lock();
        try {
            if (settledFor == n) {
                return;
            }
            settledFor = n;
            joinMoved.signalAll();
        } finally {
            joinLock.unlock();
        }
        if (n.expectsPeers()) {
            LOG.info("net: node {} has joined: {}, {} ms after joining", n.id(), why, System.currentTimeMillis() - n.started().toEpochMilli());
        }
    }

    private static boolean sameStates(CapabilityTable a, CapabilityTable b) {
        if (a.nodes().size() != b.nodes().size()) {
            return false;
        }
        for (CapabilityTable.Node n : b.nodes()) {
            var o = a.node(n.id()).orElse(null);
            if (o == null || o.routable() != n.routable() || !o.digest().hash().equals(n.digest().hash())) {
                return false;
            }
        }
        return true;
    }

    private Digest pull(Member m) {
        try {
            PeerHttp.Answer a = net.http().control(m.url(), "GET", "/api/v1/net/digest", Map.of(), null, Duration.ofSeconds(3));   // the control lane
            if (a.status() != 200) {
                LOG.debug("net: digest of {} refused: HTTP {}", m.nodeId(), a.status());
                return null;
            }
            return net.http().json().readValue(a.body(), Digest.class);
        } catch (IOException e) {
            LOG.debug("net: digest of {} not read: {}", m.nodeId(), e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** This node's digest now, built from the packs switched on, their kinds and the sources that serve them. */
    Digest build(NetService.Node n) {
        Member me = n.table().self();
        Set<String> peerOnly = Set.copyOf(net.config().node().peerOnlyPacks());
        List<String> enabled = packAccess.enabled();
        List<Pack> on = packs.packs().stream().filter(p -> enabled.contains(p.name())).sorted(Comparator.comparing(Pack::name)).toList();
        List<Digest.PackEntry> packEntries = new ArrayList<>();
        List<Digest.KindEntry> kinds = new ArrayList<>();
        for (Pack p : on) {
            packEntries.add(new Digest.PackEntry(p.name(), p.code(), p.version(), packHash(p), peerOnly.contains(p.name())));
            for (String kind : p.kinds().stream().sorted().toList()) {
                String code = mnemonics.codeFor(kind);
                String label = code == null ? kind : mnemonics.of(code).map(x -> x.label()).orElse(kind);
                kinds.add(new Digest.KindEntry(kind, code, label, p.name(), true, sourcesOf(kind)));
            }
        }
        List<Map<String, Object>> minutes = activity.lastMinutes(2);
        Map<String, Object> last = minutes.isEmpty() ? Map.of() : minutes.get(0);
        double rps = ((Number) last.getOrDefault("requests", 0L)).doubleValue() / 60.0;
        double ms = ((Number) last.getOrDefault("avgMs", 0.0)).doubleValue();
        Map<String, List<String>> access = net.config().access().roles();
        Map<String, String> svc = new java.util.TreeMap<>();
        services.forEach((name, state) -> {
            String v = safe(state);
            if (!v.isEmpty()) {
                svc.put(name, v);
            }
        });
        return new Digest(n.id(), me.url(), net.version(), Member.PROTOCOL, packEntries, kinds, new Digest.Load(Math.round(rps * 100) / 100.0, ms),
                calendar.get(), me.tags(), security.redact().stream().sorted().toList(), access, reports.get().stream().sorted().toList(),
                System.currentTimeMillis(), svc);
    }

    private List<Digest.SourceEntry> sourcesOf(String kind) {
        List<Digest.SourceEntry> out = new ArrayList<>();
        for (SourcePlugin p : sources.plugins()) {
            if (!p.manifest().serves(kind) || holdsNone(p, kind)) {
                continue;                                      // a source with nothing of the kind makes no node a holder of it
            }
            String name = p.manifest().name();
            String type = sources.applied(name).map(c -> c.plugin()).orElse(name);
            String table = safe(() -> p.tableIdentity(kind).orElse(""));
            String earliest = safe(() -> p.earliestDate(kind).map(Object::toString).orElse(""));
            String latest = safe(() -> p.latestDate(kind).map(Object::toString).orElse(""));
            String version = safe(() -> p.dataVersion(kind).orElse(""));
            boolean columnar = !safeSet(() -> p.columnar(kind)).isEmpty();
            out.add(new Digest.SourceEntry(name, type, table, earliest, latest, version, columnar, p.manifest().capabilities().dated(),
                    p.manifest().capabilities().live()));
        }
        out.sort(Comparator.comparing(Digest.SourceEntry::connector));
        return out;
    }

    private static boolean holdsNone(SourcePlugin p, String kind) {
        try {
            return p.holdsNone(kind);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String safe(Supplier<String> s) {
        try {
            String v = s.get();
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Set<String> safeSet(Supplier<Set<String>> s) {
        try {
            Set<String> v = s.get();
            return v == null ? Set.of() : v;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** A hash of a pack's files (names and contents), cached by name and version until the packs are reloaded. */
    private String packHash(Pack p) {
        String key = p.name() + "@" + p.version() + "#" + packs.version();
        return packHashes.computeIfAbsent(key, k -> {
            packHashes.keySet().removeIf(x -> x.startsWith(p.name() + "@") && !x.equals(k));
            try (Stream<Path> files = Files.walk(p.dir())) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                    md.update(p.dir().relativize(f).toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    md.update(Files.readAllBytes(f));
                }
                return HexFormat.of().formatHex(md.digest(), 0, 8);
            } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
                return "";
            }
        });
    }

    /** This node's digest (built now when none is yet). */
    public Digest self() {
        Digest d = self;
        if (d == null) {
            NetService.Node n = net.require();
            d = build(n);
            self = d;
        }
        return d;
    }

    public CapabilityTable table() {
        return table;
    }

    public Map<String, Digest> peers() {
        return Map.copyOf(peers);
    }

    @Override
    public void close() {
        Thread t = loop;
        if (t != null) {
            t.interrupt();
        }
    }
}
