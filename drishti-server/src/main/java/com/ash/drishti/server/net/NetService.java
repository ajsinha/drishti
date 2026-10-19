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

import com.ash.drishti.net.config.NetConfig;
import com.ash.drishti.net.config.NetFolder;
import com.ash.drishti.net.config.SeedPeer;
import com.ash.drishti.net.member.Gossiper;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.member.MemberTable;
import com.ash.drishti.net.member.NetClock;
import com.ash.drishti.net.member.NodeIdentity;
import com.ash.drishti.net.member.PeersFile;
import com.ash.drishti.net.security.ClusterKey;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.connectors.DirectoryWatcher;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This server as a member of a Drishti*net cluster (DRISHTI_NET.md sections 5 and 14). It watches {@code config/net/}; while
 * {@code node.yaml} says {@code enabled: false} (or there is no folder) it does nothing at all and the server is a single
 * server exactly as before. Switched on, it keeps the member table, runs a gossip round every {@code interval} on a virtual
 * thread, saves {@code <data dir>/net/peers.json} every {@code persist-every}, on every join or leave and at shutdown, and
 * rejoins from that file after a restart. Switched off (or stopped), it gossips {@code left} first, so peers drop it at once.
 *
 * <p>Thread-safety: the running node is swapped whole under one lock (start, stop, a reload); readers take the volatile
 * reference and never lock. The settings are one immutable {@link NetConfig}.
 */
public final class NetService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NetService.class);

    /** A refused call from another node or a join attempt, for Admin → Net → Security. */
    public record Refusal(Instant at, String address, String node, String reason) {}

    /** The running node: its table, gossip and the thread that drives it. */
    public static final class Node {
        final String id;
        final MemberTable table;
        final Gossiper gossiper;
        final Thread loop;
        final Instant started;
        volatile boolean leaving;

        Node(String id, MemberTable table, Gossiper gossiper, Thread loop) {
            this.id = id;
            this.table = table;
            this.gossiper = gossiper;
            this.loop = loop;
            this.started = Instant.now();
        }

        public String id() {
            return id;
        }

        public MemberTable table() {
            return table;
        }

        public Gossiper gossiper() {
            return gossiper;
        }
    }

    private final NetFolder folder;
    private final Path state;
    private final NetProperties props;
    private final String version;
    private final NetClock clock;
    private final PeersFile peersFile;
    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService exchanges = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("drishti-net-io-", 0).factory());
    private final Deque<Refusal> refusals = new ArrayDeque<>();
    private final List<Consumer<NetService>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong lastSaved = new AtomicLong();
    private volatile NetFolder.Loaded loaded = new NetFolder.Loaded(NetConfig.OFF, Map.of(), Map.of());
    private volatile ClusterKey key;
    private volatile Node node;
    private volatile String digestHash = "";
    private final PeerHttp http;
    private DirectoryWatcher watcher;

    public NetService(NetFolder folder, Path state, NetProperties props, String version, NetClock clock) {
        this.folder = folder;
        this.state = state.toAbsolutePath().normalize();
        this.props = props;
        this.version = version == null ? "" : version;
        this.clock = clock;
        this.peersFile = new PeersFile(this.state.resolve("peers.json"));
        this.key = new ClusterKey("", "", clock);
        this.http = new PeerHttp(() -> key, this::nodeId, () -> config().gossip().interval().multipliedBy(2).plusMillis(500), this::tlsFor);
    }

    // -- life cycle ---------------------------------------------------------------------------------------------------

    /** Reads the folder once and starts watching it. */
    public void start() {
        reload();
        watcher = new DirectoryWatcher(folder.dir(), props.watch(), props.poll().toMillis(), this::reloadIfChanged, "drishti-net-watch");
        watcher.start();
    }

    private void reloadIfChanged() {
        Map<String, String> now = new LinkedHashMap<>();
        folder.files().stream().filter(NetFolder.FileInfo::exists).forEach(f -> now.put(f.id(), f.etag()));
        if (!now.equals(loaded.etags())) {
            reload();
        }
    }

    /** Applies the folder as it is now: settings swapped, the node started, stopped or adjusted. Never throws. */
    public void reload() {
        lock.lock();
        try {
            NetFolder.Loaded l = folder.load(loaded.config());
            l.all().forEach(p -> (p.error() ? LOG.atWarn() : LOG.atInfo()).log("net: {} {}", p.error() ? "refused" : "note:", p));
            NetConfig before = loaded.config();
            loaded = l;
            NetConfig c = l.config();
            key = key.withKeys(c.node().secret(), c.node().previousSecret());
            if (!c.node().tls().equals(before.node().tls()) || !c.peers().equals(before.peers())) {
                http.reset();
            }
            Node n = node;
            if (c.enabled() && n == null) {
                startNode(c);
            } else if (!c.enabled() && n != null) {
                stopNode("node.yaml switched it off");
            } else if (n != null && !n.leaving) {
                String wanted = c.node().nodeId().isEmpty() ? n.id : c.node().nodeId();
                if (!wanted.equals(n.id)) {
                    stopNode("node-id changed");
                    startNode(c);
                } else {
                    n.table.updateSelf(m -> m.withAdvert(c.node().advertiseUrl(), c.node().tags()));
                }
            }
        } catch (RuntimeException e) {
            LOG.error("net: applying config/net/ failed; the previous settings stay", e);
        } finally {
            lock.unlock();
        }
        listeners.forEach(x -> x.accept(this));
    }

    private void startNode(NetConfig c) {
        String id = NodeIdentity.resolve(c.node().nodeId(), state.resolve("node-id"));
        Optional<PeersFile.Saved> saved = peersFile.read().filter(s -> id.equals(s.nodeId()));
        long incarnation = saved.map(s -> s.self() == null ? 1 : s.self().incarnation() + 1).orElse(1L);
        Member self = new Member(id, c.node().advertiseUrl(), incarnation, 0, MemberState.ALIVE, digestHash, version, Member.PROTOCOL,
                c.node().tags(), clock.millis(), false);
        MemberTable table = new MemberTable(self, clock);
        Gossiper g = new Gossiper(table, () -> config().gossip(), http, clock, new java.security.SecureRandom(), exchanges);
        List<String> rejoin = saved.map(PeersFile.Saved::rejoinUrls).orElse(List.of());
        g.joinList(() -> {
            List<String> urls = new ArrayList<>(config().seedUrls());
            rejoin.stream().filter(u -> !urls.contains(u)).forEach(urls::add);
            return urls;
        });
        g.listener(ch -> {
            if (!ch.joined().isEmpty() || !ch.gone().isEmpty()) {
                ch.joined().forEach(j -> LOG.info("net: member {} joined", j));
                ch.gone().forEach(j -> LOG.info("net: member {} is gone", j));
                save();
            }
            listeners.forEach(x -> x.accept(this));
        });
        Thread loop = Thread.ofVirtual().name("drishti-net-gossip").unstarted(() -> loop(table, g));
        node = new Node(id, table, g, loop);
        loop.start();
        LOG.info("net: node {} joined Drishti*net at {} (incarnation {}, seeds {}, {} saved members)", id, self.url(), incarnation,
                c.seedUrls(), rejoin.size());
    }

    private void loop(MemberTable table, Gossiper g) {
        while (!Thread.currentThread().isInterrupted()) {
            Node n = node;
            if (n == null || n.table != table) {
                return;
            }
            if (!n.leaving) {
                try {
                    g.round();
                } catch (RuntimeException e) {
                    LOG.warn("net: gossip round failed: {}", e.toString());
                }
                if (clock.millis() - lastSaved.get() >= config().gossip().persistEvery().toMillis()) {
                    save();
                }
            }
            try {
                Thread.sleep(config().gossip().interval().toMillis());
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void stopNode(String why) {
        Node n = node;
        if (n == null) {
            return;
        }
        n.leaving = true;
        try {
            n.gossiper.leave(3);
        } catch (RuntimeException e) {
            LOG.warn("net: telling peers this node leaves failed: {}", e.toString());
        }
        save();
        n.loop.interrupt();
        node = null;
        LOG.info("net: node {} left Drishti*net ({})", n.id, why);
    }

    /** Saves peers.json now (atomically); a failure is logged. */
    public void save() {
        Node n = node;
        if (n != null && peersFile.save(n.table, clock.millis())) {
            lastSaved.set(clock.millis());
        }
    }

    @Override
    public void close() {
        if (watcher != null) {
            watcher.close();
        }
        lock.lock();
        try {
            stopNode("the server is stopping");
        } finally {
            lock.unlock();
        }
        exchanges.shutdownNow();
    }

    // -- what the rest of the server reads ---------------------------------------------------------------------------

    /** Told after every reload and every membership change (the digest service, the peer sources). */
    public void onChange(Consumer<NetService> l) {
        listeners.add(l);
    }

    public NetConfig config() {
        return loaded.config();
    }

    public NetFolder.Loaded loaded() {
        return loaded;
    }

    public NetFolder folder() {
        return folder;
    }

    public String watchState() {
        return watcher == null ? "OFF" : watcher.state();
    }

    /** True while this server is a running member (enabled and not left). */
    public boolean running() {
        Node n = node;
        return n != null && !n.leaving && n.table.self().state() != MemberState.LEFT;
    }

    public Optional<Node> node() {
        return Optional.ofNullable(node);
    }

    /** This node's id, or empty while the cluster is off. */
    public String nodeId() {
        Node n = node;
        return n == null ? "" : n.id;
    }

    public ClusterKey key() {
        return key;
    }

    public PeerHttp http() {
        return http;
    }

    public NetClock clock() {
        return clock;
    }

    public String version() {
        return version;
    }

    public Path peersFile() {
        return peersFile.path();
    }

    public long lastSaved() {
        return lastSaved.get();
    }

    /** The digest hash this node gossips (set by the digest service when its digest changes). */
    public void digestHash(String hash) {
        this.digestHash = hash == null ? "" : hash;
        Node n = node;
        if (n != null) {
            n.table.updateSelf(m -> m.withDigestHash(this.digestHash));
            if (!n.leaving) {
                announce();                          // the peers hear of the change now, not at the next round
            }
        }
    }

    public String digestHash() {
        return digestHash;
    }

    /** The {@code tls.*} settings for calls to {@code url}: node.yaml's, with the matching peer file's overrides. */
    private Map<String, String> tlsFor(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        config().node().tls().forEach((k, v) -> out.put("tls." + k, v));
        for (SeedPeer p : config().peers()) {
            if (p.url().equals(url)) {
                p.tls().forEach((k, v) -> out.put("tls." + k, v));
            }
        }
        return out;
    }

    // -- refusals -----------------------------------------------------------------------------------------------------

    public void refused(String address, String node, String reason) {
        synchronized (refusals) {                     // no I/O: a bounded list
            refusals.addFirst(new Refusal(Instant.now(), address, node, reason));
            while (refusals.size() > 50) {
                refusals.removeLast();
            }
        }
        LOG.warn("net: refused a call from {} (node {}): {}", address, node == null || node.isBlank() ? "?" : node, reason);
    }

    public List<Refusal> refusals() {
        synchronized (refusals) {                     // no I/O: a copy
            return List.copyOf(refusals);
        }
    }

    // -- management (Admin → Net → Members) ---------------------------------------------------------------------------

    /** Raises this node's heartbeat and pushes its entry now (after a drain, an advert change), without waiting for the next round. */
    private void announce() {
        Node n = node;
        if (n != null) {
            n.table.updateSelf(m -> m.withHeartbeat(m.heartbeat() + 1));
            exchanges.execute(n.gossiper::round);
        }
    }

    public Node require() {
        Node n = node;
        if (n == null) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.NET_OFF,
                    "Drishti*net is off on this node: config/net/node.yaml does not say enabled: true (see docs/admin/NET.md)");
        }
        return n;
    }

    /** Takes no forwarded work (maintenance) and tells the peers; its own users are served as before. */
    public void drain(boolean on) {
        Node n = require();
        n.table.updateSelf(m -> m.withDrained(on));
        announce();
    }

    /** Leaves the cluster (gossips {@code left}, then serves peers nothing) without switching it off in node.yaml. */
    public void leave() {
        Node n = require();
        n.leaving = true;
        n.gossiper.leave(3);
        save();
    }

    /** Back after {@link #leave()}: alive again with a higher incarnation. */
    public void rejoin() {
        Node n = require();
        n.gossiper.rejoin();
        n.leaving = false;
        announce();
    }

    /** Removes a dead or left member from the table; an alive or suspect one is refused (gossip would bring it back). */
    public void remove(String id) {
        Node n = require();
        MemberTable.Known k = n.table.known(id).orElseThrow(() -> new com.ash.drishti.common.DrishtiException(
                com.ash.drishti.common.ErrorCode.NET_BAD_REQUEST, "no member " + id + " in the table"));
        if (!k.member().state().gone()) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.NET_BAD_REQUEST, "member " + id + " is "
                    + k.member().state().wire() + ": only a dead or left member can be removed (an alive one would come back with the next round)");
        }
        n.table.remove(id);
        save();
    }

    /** Tries a seed at once; true when it answered and its whole table was merged. */
    public boolean contact(String url) {
        return require().gossiper.contact(url);
    }
}
