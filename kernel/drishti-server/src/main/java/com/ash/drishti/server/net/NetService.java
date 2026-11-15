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

import com.ash.drishti.common.Branding;
import com.ash.drishti.net.config.ClusterSettings;
import com.ash.drishti.net.config.HostAddress;
import com.ash.drishti.net.config.NetConfig;
import com.ash.drishti.net.config.NetFolder;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberDirectory;
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.member.MembersFile;
import com.ash.drishti.net.member.Membership;
import com.ash.drishti.net.member.NetClock;
import com.ash.drishti.net.member.NodeIdentity;
import com.ash.drishti.net.member.ViewChange;
import com.ash.drishti.net.member.jgroups.JGroupsMembership;
import com.ash.drishti.net.member.jgroups.JGroupsStack;
import com.ash.drishti.net.security.ClusterKey;
import com.ash.drishti.net.security.ClusterTls;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.connectors.DirectoryWatcher;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
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
 * This server as a member of a Drishti*net cluster (CLUSTER_GUIDE.md section 1; CLUSTER_I5_DESIGN.md sections 3 and 4). It watches
 * {@code config/net/}; while {@code node.yaml} says {@code enabled: false} (or there is no folder) it does nothing at all: no library
 * loaded, no port, no folder, and the server is a single server exactly as before. Switched on, it joins the cluster through
 * {@link Membership} (JGroups, TCP only, on {@code cluster-port}), keeps the {@link MemberDirectory} (the agreed view with each member's
 * vitals), publishes this node's vitals on every change and every {@code vitals-every}, writes {@code <data dir>/net/members.json} on
 * every view change and at shutdown, and rejoins from it after a restart. Switched off (or stopped), it leaves cleanly, so peers drop it
 * at once.
 *
 * <p>Thread-safety: the running node is swapped whole under one lock (start, stop, a reload); readers take the volatile reference and
 * never lock. View changes and vitals are handled on one platform thread of their own, never on the membership's threads. The settings
 * are one immutable {@link NetConfig}.
 */
public final class NetService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NetService.class);
    /** The membership channel's topic of a member's vitals. */
    static final String VITALS = "vitals";
    /** How long a leaving node waits after its "left" vitals before it leaves the view. */
    private static final long LEAVE_GRACE_MS = 150;

    /** A refused call from another node or a join attempt, for Admin → Net → Security. */
    public record Refusal(Instant at, String address, String node, String reason) {}

    /** Makes the membership of a node from its settings (the product: JGroups; a test may give another). */
    public interface MembershipFactory {
        Membership create(String nodeId, NetConfig config, List<String> seeds, ClusterKey key, String bindHost);
    }

    /** The running node: its directory, its membership and when it joined. */
    public static final class Node {
        final String id;
        final MemberDirectory members;
        final Membership membership;
        final Instant started;
        /** True when it joined with seeds other than itself or members saved in members.json: it is joining until it has their digests. */
        final boolean expectsPeers;
        volatile boolean leaving;

        Node(String id, MemberDirectory members, Membership membership, boolean expectsPeers) {
            this.id = id;
            this.members = members;
            this.membership = membership;
            this.started = Instant.now();
            this.expectsPeers = expectsPeers;
        }

        /** When it joined. */
        public Instant started() {
            return started;
        }

        /** True when it has seeds other than itself or saved members to join: a cluster member, not a node alone. */
        public boolean expectsPeers() {
            return expectsPeers;
        }

        public String id() {
            return id;
        }

        /** The members as this node knows them: the view and each member's vitals. */
        public MemberDirectory members() {
            return members;
        }

        public Membership membership() {
            return membership;
        }
    }

    private final NetFolder folder;
    private final Path state;
    private final NetProperties props;
    private final String version;
    private final NetClock clock;
    private final MembersFile membersFile;
    private final ReentrantLock lock = new ReentrantLock();
    /** View changes and vitals: one platform thread of their own, off the membership's threads and off the busy path. */
    private final ExecutorService events = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("drishti-net-membership").factory());
    private final Deque<Refusal> refusals = new ArrayDeque<>();
    private final List<Consumer<NetService>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong lastSaved = new AtomicLong();
    private final AtomicLong viewChanges = new AtomicLong();
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private volatile NetFolder.Loaded loaded = new NetFolder.Loaded(NetConfig.OFF, Map.of(), Map.of());
    private volatile ClusterKey key;
    private volatile Node node;
    private volatile Thread vitalsLoop;
    private volatile String digestHash = "";
    private volatile String switchesHash = "";
    private volatile MembershipFactory factory = this::jgroups;
    private final PeerHttp http;
    private DirectoryWatcher watcher;

    public NetService(NetFolder folder, Path state, NetProperties props, String version, NetClock clock) {
        this.folder = folder;
        this.state = state.toAbsolutePath().normalize();
        this.props = props;
        this.version = version == null ? "" : version;
        this.clock = clock;
        this.membersFile = new MembersFile(this.state.resolve("members.json"));
        this.key = new ClusterKey("", "", clock);
        this.http = new PeerHttp(() -> key, this::nodeId, () -> config().cluster().membership().heartbeat().multipliedBy(2).plusMillis(500), this::tlsFor)
                .http2(() -> config().routing().http2());
    }

    /** How the membership is made (tests); called before {@link #start()}. */
    public void membershipFactory(MembershipFactory f) {
        this.factory = f == null ? this::jgroups : f;
    }

    /** The product's membership: JGroups on the advertised host and the cluster port, TLS from [net.tls] when configured. */
    private Membership jgroups(String nodeId, NetConfig c, List<String> seeds, ClusterKey k, String bindHost) {
        ClusterSettings cs = c.cluster();
        javax.net.ssl.SSLContext tls = null;
        boolean need = true;
        if (cs.tls().configured()) {
            ClusterTls t = ClusterTls.read(cs.tls());                  // a TLS problem stops the join: never fall back to no TLS
            tls = t.context();
            need = t.clientAuthNeeded();
        }
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (String s : seeds) {
            int colon = s.lastIndexOf(':');
            addresses.add(new InetSocketAddress(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1))));
        }
        JGroupsStack.Spec spec = new JGroupsStack.Spec(cs.cluster().name(), bindHost, c.node().clusterPort(), addresses, cs, tls, need,
                c.node().secret(), c.node().previousSecret());
        return new JGroupsMembership(nodeId, c.node().role(), spec);
    }

    // -- life cycle ---------------------------------------------------------------------------------------------------

    /** The address advertised when node.yaml sets none: this machine's primary IPv4 address with the server's port ("" when there is none). */
    private volatile String defaultAdvert = "";

    /**
     * Sets the address this node advertises when node.yaml names none: {@code scheme://<this machine's primary IPv4 address>:port}
     * ({@link HostAddress#primary()}); none when the machine has no address but loopback or the port is not fixed. Called before
     * {@link #start()}.
     */
    public void advertiseDefault(String scheme, int port) {
        defaultAdvert = port > 0 ? HostAddress.primary().map(ip -> scheme + "://" + ip + ":" + port).orElse("") : "";
    }

    /** The address this node advertises: node.yaml's advertise-url, else this machine's real address with the server's port. */
    public String advertiseUrl() {
        String set = config().node().advertiseUrl();
        return set.isEmpty() ? defaultAdvert : set;
    }

    /** False while the server is starting and joining waits for it ({@link #joinWhenReady()}); true once it may join. */
    private volatile boolean mayJoin = true;

    /**
     * Holds the joining back until {@link #ready()}: the node joins only once its web server answers and its connectors have started, so no
     * peer routes a request to a port that is not open yet (SOAK-03). Called before {@link #start()}; the server calls {@link #ready()} on
     * {@code ApplicationReadyEvent}. A NetService made without it (a test, a tool) joins at once.
     */
    public void joinWhenReady() {
        mayJoin = false;
    }

    /** The server is ready (its web server answers, its connectors have started): the node joins now if node.yaml says so. */
    public void ready() {
        if (!mayJoin) {
            starting = true;                         // awaitingJoin() stays true until the node is made (SOAK3-01)
            try {
                mayJoin = true;
                reload();
            } finally {
                starting = false;
            }
        }
    }

    /** True once the node may join (the server is ready, or nothing holds it back). */
    public boolean mayJoin() {
        return mayJoin;
    }

    /** True while node.yaml says the node is a member but it has not joined yet: a read then cannot know what its peers hold (SOAK3-01). */
    public boolean awaitingJoin() {
        return node == null && (!mayJoin || starting) && config().enabled();
    }

    /** True while {@link #ready()} is making the node: no gap between "not joined yet" and "joined". */
    private volatile boolean starting;

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

    /** Applies the folder as it is now: settings swapped, the node started, stopped, restarted or adjusted. Never throws. */
    public void reload() {
        lock.lock();
        try {
            NetFolder.Loaded l = folder.load(loaded.config());
            l.all().forEach(p -> (p.error() ? LOG.atWarn() : LOG.atInfo()).log("net: {} {}", p.error() ? "refused" : "note:", p));
            NetConfig before = loaded.config();
            loaded = l;
            NetConfig c = l.config();
            key = key.withKeys(c.node().secret(), c.node().previousSecret());
            if (!c.node().tls().equals(before.node().tls())) {
                http.reset();
            }
            Node n = node;
            if (c.enabled() && n == null && !mayJoin) {
                LOG.info("net: {} is on; this node joins once the server is ready (its web server answers and its connectors have started)", Branding.netName());
            } else if (c.enabled() && n == null) {
                startNode(c);
            } else if (!c.enabled() && n != null) {
                stopNode("node.yaml switched it off");
            } else if (n != null && !n.leaving) {
                String wanted = c.node().nodeId().isEmpty() ? n.id : c.node().nodeId();
                if (!wanted.equals(n.id) || restartNeeded(before, c)) {
                    stopNode(!wanted.equals(n.id) ? "node-id changed" : "the cluster port, a removed seed, the role, key, cluster name, timings or TLS changed");
                    startNode(c);
                } else {
                    c.seeds().stream().filter(x -> !before.seeds().contains(x)).forEach(x -> n.membership.addSeed(x));   // dialled from now on
                    n.members.updateSelf(m -> m.withAdvert(advertiseUrl(), c.node().tags()));
                    announce();
                }
            }
        } catch (RuntimeException e) {
            LOG.error("net: applying config/net/ failed; the previous settings stay", e);
        } finally {
            lock.unlock();
        }
        listeners.forEach(x -> x.accept(this));
    }

    /** What the running membership was built from changed: it leaves and joins again with the new settings (a new seed is only added). */
    private static boolean restartNeeded(NetConfig a, NetConfig b) {
        return a.node().clusterPort() != b.node().clusterPort() || !b.node().seeds().containsAll(a.node().seeds()) || !a.node().role().equals(b.node().role())
                || !a.node().secret().equals(b.node().secret()) || !a.node().previousSecret().equals(b.node().previousSecret())
                || !a.node().advertisedHost().equals(b.node().advertisedHost()) || !a.cluster().cluster().equals(b.cluster().cluster())
                || !a.cluster().membership().equals(b.cluster().membership()) || !a.cluster().tls().equals(b.cluster().tls());
    }

    private void startNode(NetConfig c) {
        String id = NodeIdentity.resolve(c.node().nodeId(), state.resolve("node-id"));
        Optional<MembersFile.Saved> saved = membersFile.read().filter(s -> id.equals(s.nodeId()));
        String advert = advertiseUrl();
        if (advert.isEmpty() || !HostAddress.callableUrl(advert)) {
            LOG.error("net: node {} does not join {}: it has no address to advertise ({}); set advertise-url in node.yaml to an address "
                    + "other machines can call (this machine has no address but loopback, or the server's port is not fixed)", id, Branding.netName(),
                    advert.isEmpty() ? "none" : HostAddress.urlProblem(advert).orElse(advert + " is loopback or wildcard"));
            return;
        }
        String host = java.net.URI.create(advert).getHost().replaceAll("^\\[|]$", "");
        String self = host + ":" + c.node().clusterPort();
        List<String> seeds = new ArrayList<>(c.seeds());
        saved.map(MembersFile.Saved::rejoinSeeds).orElse(List.of()).stream().filter(s -> !seeds.contains(s)).forEach(seeds::add);
        seeds.remove(self);
        Member me = new Member(id, advert, MemberState.ALIVE, digestHash, version, Member.PROTOCOL, c.node().tags(), clock.millis(), false, self,
                switchesHash, c.node().role());
        MemberDirectory members = new MemberDirectory(me, clock);
        saved.ifPresent(s -> members.remember(s.members()));
        Membership membership;
        try {
            membership = factory.create(id, c, List.copyOf(seeds), key, host);
        } catch (RuntimeException e) {
            LOG.error("net: node {} does not join {}: {}", id, Branding.netName(), e.getMessage());
            return;
        }
        Node n = new Node(id, members, membership, !seeds.isEmpty());
        membership.onChange(change -> events.execute(() -> onView(n, change)));
        membership.onMessage(VITALS, (from, bytes) -> events.execute(() -> onVitals(n, bytes)));
        node = n;
        try {
            membership.join();
        } catch (RuntimeException e) {
            node = null;
            membership.close();
            LOG.error("net: node {} could not join {}: {}", id, Branding.netName(), e.getMessage());
            return;
        }
        startVitals(n, c);
        announce();
        LOG.info("net: node {} joined {} at {} (cluster port {}, seeds {}, {} saved members)", id, Branding.netName(), advert, c.node().clusterPort(),
                c.seeds(), saved.map(s -> s.members().size()).orElse(0));
    }

    /** Publishes this node's vitals every {@code vitals-every} and forgets members gone for longer than {@code forget-after}. */
    private void startVitals(Node n, NetConfig c) {
        Thread t = Thread.ofPlatform().daemon().name("drishti-net-vitals").unstarted(() -> {
            while (!Thread.currentThread().isInterrupted() && node == n) {
                try {
                    Thread.sleep(config().cluster().membership().vitalsEvery().toMillis());
                } catch (InterruptedException e) {
                    return;
                }
                if (!n.leaving) {
                    publishVitals(n);
                }
                List<String> forgotten = n.members.forget(config().cluster().membership().forgetAfter());
                if (!forgotten.isEmpty()) {
                    LOG.info("net: forgot {} (out of the view for {})", forgotten, com.ash.drishti.net.config.NetSchema.text(c.cluster().membership().forgetAfter()));
                    save();
                }
            }
        });
        vitalsLoop = t;
        t.start();
    }

    private void onView(Node n, ViewChange change) {
        if (node != n) {
            return;
        }
        viewChanges.incrementAndGet();
        MemberDirectory.Changes ch = n.members.view(change.after());
        ch.joined().forEach(j -> LOG.info("net: member {} joined (view {})", j, change.after().id()));
        ch.gone().forEach(g -> LOG.info("net: member {} left the view (view {})", g, change.after().id()));
        ch.joined().forEach(j -> joinListeners.forEach(x -> {
            try {
                x.accept(j);
            } catch (RuntimeException e) {
                LOG.warn("net: join listener failed: {}", e.toString());
            }
        }));
        save();
        if (!n.leaving && !change.joined().isEmpty()) {
            publishVitals(n);                            // a newcomer hears this node's vitals at once, not after vitals-every
        }
        listeners.forEach(x -> x.accept(this));
    }

    private void onVitals(Node n, byte[] bytes) {
        if (node != n) {
            return;
        }
        Member m;
        try {
            m = json.readValue(bytes, Member.class);
        } catch (java.io.IOException e) {
            LOG.debug("net: vitals that cannot be read were dropped: {}", e.toString());
            return;
        }
        MemberDirectory.Changes ch = n.members.vitals(m);
        ch.joined().forEach(j -> joinListeners.forEach(x -> {
            try {
                x.accept(j);
            } catch (RuntimeException e) {
                LOG.warn("net: join listener failed: {}", e.toString());
            }
        }));
        if (ch.changed()) {
            listeners.forEach(x -> x.accept(this));
        }
    }

    private void publishVitals(Node n) {
        try {
            n.membership.publish(VITALS, json.writeValueAsBytes(n.members.self()));
        } catch (java.io.IOException | RuntimeException e) {
            LOG.debug("net: publishing this node's vitals failed: {}", e.toString());
        }
    }

    private void stopNode(String why) {
        Node n = node;
        if (n == null) {
            return;
        }
        n.leaving = true;
        n.members.updateSelf(m -> m.withState(MemberState.LEFT, clock.millis()));
        publishVitals(n);                                 // peers show "left", not "dead"
        save();
        try {
            Thread.sleep(LEAVE_GRACE_MS);                 // the "left" vitals reach the peers before the view drops this node
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            n.membership.close();                         // a clean leave: the peers drop it at once
        } catch (RuntimeException e) {
            LOG.warn("net: leaving the cluster failed: {}", e.toString());
        }
        node = null;
        Thread t = vitalsLoop;
        if (t != null) {
            t.interrupt();
        }
        LOG.info("net: node {} left {} ({})", n.id, Branding.netName(), why);
    }

    /** Saves members.json now (atomically); a failure is logged. */
    public void save() {
        Node n = node;
        if (n != null && membersFile.save(n.members, clock.millis())) {
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
        events.shutdownNow();
        http.close();
    }

    /**
     * Stops at once without telling anyone, as a node that died does (tests: the failure detector must find it). The caller closes the ports.
     */
    void halt() {
        Node n = node;
        if (n != null) {
            node = null;
            Thread t = vitalsLoop;
            if (t != null) {
                t.interrupt();
            }
            if (n.membership instanceof JGroupsMembership j) {
                j.crash();
            } else {
                n.membership.close();
            }
        }
    }

    // -- what the rest of the server reads ---------------------------------------------------------------------------

    /** Told after every reload, every view change and every change of a member's vitals (the digest service, the peer sources). */
    public void onChange(Consumer<NetService> l) {
        listeners.add(l);
    }

    private final List<Consumer<String>> joinListeners = new CopyOnWriteArrayList<>();

    /** Told the id of each member that joins or comes back (the user state's catch-up). */
    public void onJoin(Consumer<String> l) {
        joinListeners.add(l);
    }

    public NetConfig config() {
        return loaded.config();
    }

    /**
     * The id this node has, or will have once it joins, while config/net/ switches the cluster on; empty while it is off. The BI runs name
     * their runs after it ({@code r-<node>-...}) from the start, before the node joins.
     */
    public String plannedNodeId() {
        NetConfig c = config();
        if (!c.enabled()) {
            return "";
        }
        try {
            Node n = node;
            return n != null ? n.id : NodeIdentity.resolve(c.node().nodeId(), state.resolve("node-id"));
        } catch (RuntimeException e) {
            return "";
        }
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
        return n != null && !n.leaving && n.members.self().state() != MemberState.LEFT;
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

    /** {@code <data dir>/net/members.json}. */
    public Path membersFile() {
        return membersFile.path();
    }

    public long lastSaved() {
        return lastSaved.get();
    }

    /** View changes since the server started ({@code drishti_cluster_view_changes_total}). */
    public long viewChanges() {
        return viewChanges.get();
    }

    /** The digest hash this node publishes in its vitals (set by the digest service when its digest changes); peers told at once. */
    public void digestHash(String hash) {
        this.digestHash = hash == null ? "" : hash;
        Node n = node;
        if (n != null) {
            n.members.updateSelf(m -> m.withDigestHash(this.digestHash));
            if (!n.leaving) {
                announce();
            }
        }
    }

    public String digestHash() {
        return digestHash;
    }

    /** The switches ledger's hash this node publishes in its vitals (set by the switches sync when the ledger changes); peers told at once. */
    public void switchesHash(String hash) {
        String h = hash == null ? "" : hash;
        if (h.equals(this.switchesHash)) {
            return;
        }
        this.switchesHash = h;
        Node n = node;
        if (n != null) {
            n.members.updateSelf(m -> m.withSwitchesHash(h));
            if (!n.leaving) {
                announce();
            }
        }
    }

    public String switchesHash() {
        return switchesHash;
    }

    /** The {@code tls.*} settings for calls to another node's server port: node.yaml's. */
    private Map<String, String> tlsFor(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        config().node().tls().forEach((k, v) -> out.put("tls." + k, v));
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

    /** Publishes this node's vitals now (after a drain, an advert or digest change), off the caller's thread. */
    private void announce() {
        Node n = node;
        if (n != null) {
            events.execute(() -> publishVitals(n));
        }
    }

    public Node require() {
        Node n = node;
        if (n == null) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.NET_OFF,
                    Branding.netName() + " is off on this node: config/net/node.yaml does not say enabled: true (see docs/guides/OPERATIONS_GUIDE.md)");
        }
        return n;
    }

    /** Takes no forwarded work (maintenance) and tells the peers; its own users are served as before. */
    public void drain(boolean on) {
        Node n = require();
        n.members.updateSelf(m -> m.withDrained(on));
        announce();
    }

    /** Leaves the cluster (a clean leave: peers drop it at once; it serves peers nothing) without switching it off in node.yaml. */
    public void leave() {
        Node n = require();
        n.leaving = true;
        n.members.updateSelf(m -> m.withState(MemberState.LEFT, clock.millis()));
        publishVitals(n);
        save();
        n.membership.leave();
    }

    /** Back after {@link #leave()}: joins again. */
    public void rejoin() {
        Node n = require();
        n.members.updateSelf(m -> m.withState(MemberState.ALIVE, clock.millis()));
        n.membership.join();
        n.leaving = false;
        announce();
    }

    /** Removes a dead or left member from the directory; an alive or suspect one is refused (the view would bring it back). */
    public void remove(String id) {
        Node n = require();
        MemberDirectory.Known k = n.members.known(id).orElseThrow(() -> new com.ash.drishti.common.DrishtiException(
                com.ash.drishti.common.ErrorCode.NET_BAD_REQUEST, "no member " + id + " is known"));
        if (!k.member().state().gone()) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.NET_BAD_REQUEST, "member " + id + " is "
                    + k.member().state().wire() + ": only a dead or left member can be removed (it is in the view)");
        }
        n.members.remove(id);
        save();
    }

    /** Adds a seed ({@code host:cluster-port}) to the running membership's discovery; true when it was taken. */
    public boolean contact(String hostPort) {
        return require().membership.addSeed(hostPort);
    }
}
