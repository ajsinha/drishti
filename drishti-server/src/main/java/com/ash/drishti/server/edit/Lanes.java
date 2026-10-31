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
package com.ash.drishti.server.edit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lanes this node owns and their workers (WRITE_BACK.md › The design and its reasons, sections 7 and 11). A record's key always maps to the same lane
 * ({@code murmur3(key) mod P}); a lane is one durable ordered queue (its rows in the edit log) with <b>one worker</b>, a virtual thread
 * that applies its changes strictly in seq order and parks while the lane is empty, woken by an append. Different lanes apply in
 * parallel. Ownership is taken in the store ({@link EditLog#claim}): the node the ring suggests for a lane tries, the store decides;
 * the lease is renewed every third of its length, and a lane whose renewal fails is stopped at once (another node owns it now). A
 * worker that finds a lane's applied seq behind its rows replays from there, so a change acknowledged before a crash is applied once
 * after it. No lock is held across a database call.
 */
public final class Lanes implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Lanes.class);
    private static final int BATCH = 64;

    /** Applies one change (the handler); it ends the row's state itself, in the store. */
    @FunctionalInterface
    public interface Applier {
        void apply(EditLog.Row row, String node, long epoch);
    }

    /** Told when this node gains or loses a lane (the outbox resends a gained lane's unconfirmed commands). */
    public interface Listener {
        default void gained(int lane, long epoch) {
        }

        default void lost(int lane) {
        }
    }

    /** The change's lane is owned by another node (or nobody yet): forward it there. */
    public static final class NotOwner extends RuntimeException {
        private final int lane;
        private final String owner;

        public NotOwner(int lane, String owner) {
            super("lane " + lane + " is owned by " + (owner == null ? "no node yet" : owner));
            this.lane = lane;
            this.owner = owner;
        }

        public int lane() {
            return lane;
        }

        public String owner() {
            return owner;
        }
    }

    private final EditLog log;
    private final EditSettings settings;
    private final String node;
    private final Applier applier;
    private final Supplier<Set<Integer>> suggested;
    private final Map<Integer, Worker> owned = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ScheduledExecutorService leases;
    private volatile boolean closed;

    /**
     * @param suggested the lanes the ring names this node for (all of them on a single node or for the edit primary)
     */
    public Lanes(EditLog log, EditSettings settings, String node, Applier applier, Supplier<Set<Integer>> suggested) {
        this.log = log;
        this.settings = settings;
        this.node = node;
        this.applier = applier;
        this.suggested = suggested;
        this.leases = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("edit-leases").factory());
    }

    public String node() {
        return node;
    }

    public int count() {
        return settings.lanes();
    }

    public void listen(Listener l) {
        listeners.add(l);
    }

    /** Creates the lanes' rows, takes the ones this node is suggested for, and keeps the leases. */
    public void start() {
        log.ensureLanes(settings.lanes());
        tend();
        long every = Math.max(200, settings.laneLease().toMillis() / 3);
        leases.scheduleWithFixedDelay(this::tendQuietly, every, every, TimeUnit.MILLISECONDS);
    }

    private void tendQuietly() {
        try {
            tend();
        } catch (RuntimeException e) {
            LOG.warn("edit lanes: could not renew or claim lanes ({}); retrying", e.getMessage());
        }
    }

    /** Renews what is owned (a failed renewal stops that lane at once) and tries the suggested lanes not owned yet. */
    void tend() {
        if (closed) {
            return;
        }
        long lease = settings.laneLease().toMillis();
        for (Worker w : List.copyOf(owned.values())) {
            if (!log.renew(w.lane, node, w.epoch, lease)) {
                lose(w, "its lease was taken by another node");
            }
        }
        int gained = 0;
        for (int lane : suggested.get()) {
            if (lane < 0 || lane >= settings.lanes() || owned.containsKey(lane)) {
                continue;
            }
            Optional<Long> epoch = log.claim(lane, node, lease);
            if (epoch.isPresent()) {
                gain(lane, epoch.get());
                gained++;
            }
        }
        if (gained > 0) {
            LOG.info("edit lanes: {} took {} lane(s); it owns {} of {}", node, gained, owned.size(), settings.lanes());
        }
    }

    private void gain(int lane, long epoch) {
        Worker w = new Worker(lane, epoch);
        if (owned.putIfAbsent(lane, w) == null) {
            LOG.debug("edit lanes: {} owns lane {} at epoch {}", node, lane, epoch);
            w.start();
            listeners.forEach(l -> l.gained(lane, epoch));
        }
    }

    private void lose(Worker w, String why) {
        if (owned.remove(w.lane, w)) {
            LOG.warn("edit lanes: {} stops lane {} (epoch {}): {}", node, w.lane, w.epoch, why);
            w.stop();
            listeners.forEach(l -> l.lost(w.lane));
        }
    }

    /** The lane of a key: {@code murmur3(key) mod P}, the same on every node and after every restart. */
    public int laneOf(String key) {
        return laneOf(key, settings.lanes());
    }

    public static int laneOf(String key, int lanes) {
        return Math.floorMod(murmur3(key.getBytes(StandardCharsets.UTF_8)), lanes);
    }

    /** This node's epoch for a lane, when it owns it. */
    public Optional<Long> epoch(int lane) {
        Worker w = owned.get(lane);
        return w == null ? Optional.empty() : Optional.of(w.epoch);
    }

    /** Lane to epoch, for the lanes this node owns. */
    public Map<Integer, Long> owned() {
        Map<Integer, Long> out = new TreeMap<>();
        owned.forEach((k, v) -> out.put(k, v.epoch));
        return out;
    }

    /**
     * Appends a change to its lane on this node (acknowledged once committed) and wakes the lane's worker. {@link NotOwner} when
     * another node owns the lane; {@link EditLog.Fenced} when this node just lost it (the caller retries through the new owner).
     */
    public long append(Change c) {
        int lane = laneOf(c.key());
        Worker w = owned.get(lane);
        if (w == null) {
            throw new NotOwner(lane, log.lane(lane).filter(EditLog.Lane::leased).map(EditLog.Lane::owner).orElse(null));
        }
        try {
            long seq = log.append(c, lane, node, w.epoch, settings.maxLaneDepth());
            w.wake();
            return seq;
        } catch (EditLog.Fenced e) {
            lose(w, "an append was fenced");
            throw e;
        }
    }

    /** Wakes a lane's worker (an append forwarded by another node, or an approval). */
    public void wake(int lane) {
        Worker w = owned.get(lane);
        if (w != null) {
            w.wake();
        }
    }

    @Override
    public void close() {
        closed = true;
        leases.shutdownNow();
        for (Worker w : List.copyOf(owned.values())) {
            owned.remove(w.lane);
            w.stop();
            try {
                log.release(w.lane, node, w.epoch);                // leaving cleanly: the lanes move at once
            } catch (RuntimeException e) {
                LOG.debug("edit lanes: could not release lane {}: {}", w.lane, e.getMessage());
            }
        }
    }

    /** One lane's worker: applies in seq order, parks while the lane is empty. */
    private final class Worker {
        final int lane;
        final long epoch;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition more = lock.newCondition();
        private boolean woken = true;
        private volatile boolean running = true;
        private Thread thread;

        Worker(int lane, long epoch) {
            this.lane = lane;
            this.epoch = epoch;
        }

        void start() {
            thread = Thread.ofVirtual().name("edit-lane-" + lane).start(this::run);
        }

        void stop() {
            running = false;
            wake();
        }

        void wake() {
            lock.lock();
            try {
                woken = true;
                more.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void park() throws InterruptedException {
            lock.lock();
            try {
                if (!woken && running) {
                    more.await(1, TimeUnit.SECONDS);              // a periodic look too: an append that reached the store by another path
                }
                woken = false;
            } finally {
                lock.unlock();
            }
        }

        private void run() {
            long backoff = 100;
            while (running && !closed) {
                try {
                    park();
                    while (running) {
                        List<EditLog.Row> rows = log.unapplied(lane, BATCH);
                        if (rows.isEmpty()) {
                            break;
                        }
                        for (EditLog.Row r : rows) {
                            if (!running) {
                                break;
                            }
                            if (EditLog.QUEUED.equals(r.state())) {
                                log.applying(lane, r.seq());
                            }
                            applier.apply(r, node, epoch);        // ends the row (applied, refused, conflicted) and moves applied_seq
                        }
                    }
                    backoff = 100;
                } catch (EditLog.Fenced e) {
                    lose(this, e.getMessage());
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    LOG.warn("edit lane {}: {}; retrying in {} ms", lane, e.getMessage(), backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoff = Math.min(backoff * 2, Duration.ofSeconds(10).toMillis());
                    woken = true;
                }
            }
        }
    }

    /** MurmurHash3, x86 32-bit, seed 0: a stable hash, the same on every JVM. */
    static int murmur3(byte[] data) {
        int h = 0;
        int n = data.length / 4;
        for (int i = 0; i < n; i++) {
            int k = (data[i * 4] & 0xff) | (data[i * 4 + 1] & 0xff) << 8 | (data[i * 4 + 2] & 0xff) << 16 | (data[i * 4 + 3] & 0xff) << 24;
            k *= 0xcc9e2d51;
            k = Integer.rotateLeft(k, 15);
            k *= 0x1b873593;
            h ^= k;
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
        }
        int k = 0;
        int tail = n * 4;
        switch (data.length & 3) {
            case 3:
                k ^= (data[tail + 2] & 0xff) << 16;
            case 2:
                k ^= (data[tail + 1] & 0xff) << 8;
            case 1:
                k ^= data[tail] & 0xff;
                k *= 0xcc9e2d51;
                k = Integer.rotateLeft(k, 15);
                k *= 0x1b873593;
                h ^= k;
            default:
                break;
        }
        h ^= data.length;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
