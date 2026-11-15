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
package com.ash.drishti.net.member;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * An in-memory {@link Membership} for tests (CLUSTER_I5_DESIGN.md sections 3 and 12.1): members of one {@link Network} see each other at
 * once, in join order, with scripted joins, losses ({@link #crash()}) and partitions ({@link Network#partition}, {@link Network#heal()}).
 * Messages are delivered synchronously, in order, to every member of the sender's partition. Replaces the gossip-era {@code SimCluster}.
 */
public final class SimMembership implements Membership {

    /** The members' shared network: who joined, in what order, and who can see whom. */
    public static final class Network {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, SimMembership> joined = new LinkedHashMap<>();
        private final Map<String, Integer> side = new ConcurrentHashMap<>();
        private long viewIds;

        /** A new member of this network (not joined). */
        public SimMembership member(String id) {
            return new SimMembership(this, new NodeIdentity(id, "sim:" + id, NodeIdentity.SERVER));
        }

        /** Splits the network: each group sees only its own members; a member named in no group is alone. */
        @SafeVarargs
        public final void partition(List<String>... groups) {
            lock.lock();
            try {
                side.clear();
                for (int i = 0; i < groups.length; i++) {
                    for (String id : groups[i]) {
                        side.put(id, i + 1);
                    }
                }
            } finally {
                lock.unlock();
            }
            recompute(false);
        }

        /** Every member sees every other again: one view, a merge. */
        public void heal() {
            side.clear();
            recompute(true);
        }

        private int sideOf(String id) {
            return side.getOrDefault(id, side.isEmpty() ? 0 : -id.hashCode() - 1);
        }

        void join(SimMembership m) {
            lock.lock();
            try {
                joined.put(m.self.id(), m);
            } finally {
                lock.unlock();
            }
            recompute(false);
        }

        void leave(SimMembership m) {
            lock.lock();
            try {
                joined.remove(m.self.id());
            } finally {
                lock.unlock();
            }
            m.install(View.NONE, false);
            recompute(false);
        }

        /** Installs, in each member, the view of its side when it differs from the one it has. */
        private void recompute(boolean merged) {
            List<Map.Entry<SimMembership, View>> installs = new ArrayList<>();
            lock.lock();
            try {
                Map<Integer, List<SimMembership>> sides = new LinkedHashMap<>();
                for (SimMembership m : joined.values()) {
                    sides.computeIfAbsent(sideOf(m.self.id()), k -> new ArrayList<>()).add(m);
                }
                for (List<SimMembership> group : sides.values()) {
                    List<NodeIdentity> members = group.stream().map(x -> x.self).toList();
                    if (group.stream().anyMatch(m -> !members.equals(m.view.members()))) {
                        View v = new View(++viewIds, members.get(0).id(), members);       // one view, one id, for the whole side
                        group.forEach(m -> installs.add(Map.entry(m, v)));
                    }
                }
            } finally {
                lock.unlock();
            }
            installs.forEach(e -> e.getKey().install(e.getValue(), merged));
        }

        void send(SimMembership from, String topic, byte[] body) {
            List<SimMembership> to;
            lock.lock();
            try {
                int s = sideOf(from.self.id());
                to = joined.values().stream().filter(x -> sideOf(x.self.id()) == s).toList();
            } finally {
                lock.unlock();
            }
            to.forEach(m -> m.deliver(from.self.id(), topic, body));
        }
    }

    private final Network network;
    private final NodeIdentity self;
    private final List<Consumer<ViewChange>> viewListeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<BiConsumer<String, byte[]>>> messageListeners = new ConcurrentHashMap<>();
    private volatile View view = View.NONE;
    private volatile boolean joinedNow;

    private SimMembership(Network network, NodeIdentity self) {
        this.network = network;
        this.self = self;
    }

    @Override
    public NodeIdentity self() {
        return self;
    }

    @Override
    public View view() {
        return view;
    }

    @Override
    public void join() {
        if (!joinedNow) {
            joinedNow = true;
            network.join(this);
        }
    }

    @Override
    public void leave() {
        if (joinedNow) {
            joinedNow = false;
            network.leave(this);
        }
    }

    /** As a killed process: gone from the others' views at once (a closed socket), this node's own view cleared. */
    public void crash() {
        leave();
    }

    @Override
    public void onChange(Consumer<ViewChange> listener) {
        viewListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void publish(String topic, byte[] small) {
        int size = (small == null ? 0 : small.length) + topic.length() + 2;
        if (size > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("a membership message is at most " + MAX_MESSAGE_BYTES + " bytes; " + topic + " has " + size);
        }
        if (!joinedNow) {
            throw new IllegalStateException(self.id() + " has not joined");
        }
        network.send(this, topic, small == null ? new byte[0] : small.clone());
    }

    @Override
    public void onMessage(String topic, BiConsumer<String, byte[]> listener) {
        messageListeners.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(Objects.requireNonNull(listener));
    }

    @Override
    public void close() {
        leave();
    }

    private void install(View next, boolean merged) {
        View before = view;
        view = next;
        ViewChange c = ViewChange.between(before, next, merged);
        viewListeners.forEach(l -> l.accept(c));
    }

    private void deliver(String from, String topic, byte[] body) {
        messageListeners.getOrDefault(topic, List.of()).forEach(l -> l.accept(from, body.clone()));
    }
}
