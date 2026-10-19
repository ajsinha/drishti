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

import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.transport.PeerHttp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * User state across nodes (DRISHTI_NET.md section 11, phase N7): every change to a user's settings (saved searches among them), layouts,
 * pivots and workspaces is sent to every routable peer ({@code POST /api/v1/net/state}, signed, in batches, a quarter of a second at most
 * after it was made), and a change a peer sends is applied here, the newest write winning. A peer that does not answer keeps its changes
 * waiting (at most 10,000) and gets them when it does; a member that joins gets every such document this node holds, applied there only
 * where it has none. Alerts, monitors and scheduled reports are not copied: they act, and would act once per node. With routing.yaml's
 * {@code user-state: off} (one shared identity database already shares them) nothing is sent.
 */
public final class StateSync implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StateSync.class);
    /** The namespaces copied: what follows a user, not what acts. */
    public static final Set<String> NAMESPACES = Set.of("settings", "layouts", "pivots", "workspaces");
    private static final int MAX_PENDING = 10_000;
    private static final int BATCH = 200;

    /** One change: a document written ({@code value}) or deleted (null). {@code catchUp}: applied only where the key is absent. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(String user, String namespace, String key, JsonNode value, long at, String origin, boolean catchUp) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Batch(List<Change> changes) {}

    private final NetService net;
    private final Supplier<PreferenceStore> store;
    private final ForwardLog log;
    private final Map<String, Long> last = new ConcurrentHashMap<>();
    private final Map<String, Deque<Change>> pending = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final Thread loop;

    /** @param store the store itself (not the replicating one): what a peer's change is applied to */
    public StateSync(NetService net, Supplier<PreferenceStore> store, ForwardLog log) {
        this.net = net;
        this.store = store;
        this.log = log;
        net.onJoin(this::caughtUp);
        this.loop = Thread.ofVirtual().name("drishti-net-state").start(this::run);
    }

    /** True when changes are copied: the cluster runs and routing.yaml says {@code user-state: replicate}. */
    public boolean active() {
        return net.running() && net.config().routing().replicateUserState();
    }

    private static String key(String user, String ns, String key) {
        return user + "\u001f" + ns + "\u001f" + key;
    }

    /** A change made here (the replicating store calls it): queued for every routable peer. */
    void changed(String user, String namespace, String key, JsonNode value) {
        if (!active() || !NAMESPACES.contains(namespace)) {
            return;
        }
        long at = System.currentTimeMillis();
        last.put(key(user, namespace, key), at);
        Change c = new Change(user, namespace, key, value, at, net.nodeId(), false);
        net.node().ifPresent(n -> n.table().others().stream().map(k -> k.member()).filter(Member::routable).forEach(m -> queue(m.nodeId(), c)));
    }

    private void queue(String peer, Change c) {
        lock.lock();
        try {
            Deque<Change> q = pending.computeIfAbsent(peer, x -> new ArrayDeque<>());
            q.addLast(c);
            while (q.size() > MAX_PENDING) {
                q.pollFirst();                             // the oldest goes: the member is far behind, its catch-up on rejoin covers it
            }
        } finally {
            lock.unlock();
        }
    }

    /** A member joined (or came back): every copied document this node holds, applied there only where it has none. */
    private void caughtUp(String peer) {
        if (!active()) {
            return;
        }
        PreferenceStore s = store.get();
        int n = 0;
        for (String user : s.users()) {
            for (String ns : NAMESPACES) {
                for (String k : s.keys(user, ns)) {
                    var v = s.get(user, ns, k);
                    if (v.isPresent()) {
                        queue(peer, new Change(user, ns, k, v.get(), 0, net.nodeId(), true));
                        n++;
                    }
                }
            }
        }
        if (n > 0) {
            LOG.info("net: {} user documents queued for {}, which joined", n, peer);
        }
    }

    /** Changes a peer sent: applied, the newest write winning (a catch-up only where there is nothing). */
    public int receive(List<Change> changes) {
        PreferenceStore s = store.get();
        int done = 0;
        for (Change c : changes) {
            if (c == null || c.user() == null || !NAMESPACES.contains(c.namespace()) || c.key() == null) {
                continue;
            }
            String k = key(c.user(), c.namespace(), c.key());
            boolean deleted = c.value() == null || c.value().isNull();      // JSON null arrives as a null node
            if (c.catchUp()) {
                if (!deleted && s.get(c.user(), c.namespace(), c.key()).isEmpty()) {
                    s.put(c.user(), c.namespace(), c.key(), c.value());
                    done++;
                }
                continue;
            }
            Long mine = last.get(k);
            if (mine != null && mine > c.at()) {
                continue;                                  // a newer write here wins
            }
            last.put(k, c.at());
            if (deleted) {
                s.delete(c.user(), c.namespace(), c.key());
            } else {
                s.put(c.user(), c.namespace(), c.key(), c.value());
            }
            done++;
        }
        applied.addAndGet(done);
        return done;
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(250);
                flush();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LOG.warn("net: copying user state failed: {}", e.toString());
            }
        }
    }

    /** Sends what is waiting to each peer that can be reached now; what a peer does not take stays for the next pass. */
    void flush() throws InterruptedException {
        if (pending.isEmpty()) {
            return;
        }
        var node = net.node().orElse(null);
        if (node == null) {
            pending.clear();
            return;
        }
        for (String peer : new ArrayList<>(pending.keySet())) {
            var known = node.table().known(peer).orElse(null);
            if (known == null || known.member().state().gone()) {
                pending.remove(peer);                      // gone: its catch-up when it comes back covers what it missed
                continue;
            }
            if (!known.member().routable()) {
                continue;
            }
            List<Change> batch = new ArrayList<>();
            lock.lock();
            try {
                Deque<Change> q = pending.get(peer);
                while (q != null && !q.isEmpty() && batch.size() < BATCH) {
                    batch.add(q.pollFirst());
                }
            } finally {
                lock.unlock();
            }
            if (batch.isEmpty()) {
                continue;
            }
            long t0 = System.nanoTime();
            boolean ok;
            try {
                PeerHttp.Answer a = net.http().send(known.member().url(), "POST", "/api/v1/net/state", Map.of("Content-Type", "application/json"),
                        net.http().json().writeValueAsBytes(new Batch(batch)), Duration.ofSeconds(5));
                ok = a.status() == 200;
                log.record(peer, "user-state", "state", ForwardLog.outcome(a.status()), (System.nanoTime() - t0) / 1_000_000, batch.size() + " changes");
            } catch (java.io.IOException e) {
                ok = false;
                log.record(peer, "user-state", "state", "failed", (System.nanoTime() - t0) / 1_000_000, e.toString());
            }
            if (ok) {
                sent.addAndGet(batch.size());
            } else {
                lock.lock();
                try {
                    Deque<Change> q = pending.computeIfAbsent(peer, x -> new ArrayDeque<>());
                    for (int i = batch.size() - 1; i >= 0; i--) {
                        q.addFirst(batch.get(i));          // kept, in order, for the next pass
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /** What Admin → Net shows: changes sent and applied since start, and what waits for whom. */
    public Map<String, Object> summary() {
        Map<String, Integer> waiting = new java.util.TreeMap<>();
        lock.lock();
        try {
            pending.forEach((p, q) -> waiting.put(p, q.size()));
        } finally {
            lock.unlock();
        }
        return Map.of("active", active(), "namespaces", new java.util.TreeSet<>(NAMESPACES), "sent", sent.get(), "applied", applied.get(), "waiting", waiting);
    }

    @Override
    public void close() {
        loop.interrupt();
    }
}
