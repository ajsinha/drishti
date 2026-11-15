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

import com.ash.drishti.net.config.HostAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * What this node knows of the cluster's members (CLUSTER_I5_DESIGN.md sections 3.1 and 8): the agreed view from {@link Membership}
 * combined with each member's vitals from the membership channel, plus the members that left the view, remembered for
 * {@code forget-after}. It decides nothing: who is alive is the view's answer; what a member offers is its vitals and its digest.
 *
 * <p>The state of a member: in the view, {@code alive} (or {@code left} once its vitals say it is leaving); out of the view, {@code left}
 * when it said so, else {@code dead}. A member the view names whose vitals have not arrived yet is alive with no address, so nothing is
 * routed to it until they do. Thread-safe: every change takes one short lock (no I/O under it); readers get copies.
 */
public final class MemberDirectory {

    /** What this node knows of one other member: its entry, when its vitals last came, the latency of the last call to it, when its state began. */
    public record Known(Member member, long heardAt, double latencyMs, long stateAt) {}

    /** What a change did: members that joined (or came back), members now gone. */
    public record Changes(List<String> joined, List<String> gone, boolean changed) {
        public static final Changes NONE = new Changes(List.of(), List.of(), false);
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final NetClock clock;
    private Member self;
    private final Map<String, Known> others = new LinkedHashMap<>();
    private Set<String> inView = Set.of();
    private long viewId;

    public MemberDirectory(Member self, NetClock clock) {
        this.self = self;
        this.clock = clock;
    }

    public Member self() {
        lock.lock();
        try {
            return self;
        } finally {
            lock.unlock();
        }
    }

    /** Changes this node's own entry (digest hash, drain, advert, leaving); the caller publishes the new vitals. */
    public Member updateSelf(UnaryOperator<Member> change) {
        lock.lock();
        try {
            self = change.apply(self);
            return self;
        } finally {
            lock.unlock();
        }
    }

    /** The id of the view last applied (0 before the first). */
    public long viewId() {
        lock.lock();
        try {
            return viewId;
        } finally {
            lock.unlock();
        }
    }

    /** Applies an agreed view: its members alive, the others that were alive now dead (or left, when their vitals said so). */
    public Changes view(View v) {
        long now = clock.millis();
        List<String> joined = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        lock.lock();
        try {
            Set<String> ids = new HashSet<>();
            for (NodeIdentity n : v.members()) {
                if (n.id().equals(self.nodeId())) {
                    if (self.clusterAddress().isEmpty() && !n.address().isEmpty()) {
                        self = self.withClusterAddress(n.address());
                    }
                    continue;
                }
                ids.add(n.id());
                Known k = others.get(n.id());
                if (k == null) {
                    others.put(n.id(), new Known(Member.fromView(n, now), now, -1, now));
                    joined.add(n.id());
                    continue;
                }
                Member m = k.member();
                if (m.clusterAddress().isEmpty() && !n.address().isEmpty()) {
                    m = m.withClusterAddress(n.address());
                }
                if (m.state() != MemberState.ALIVE && m.state() != MemberState.LEFT || m.state() == MemberState.LEFT && !inView.contains(n.id())) {
                    m = m.withState(MemberState.ALIVE, now);
                    others.put(n.id(), new Known(m, k.heardAt(), k.latencyMs(), now));
                    joined.add(n.id());
                } else {
                    others.put(n.id(), new Known(m, k.heardAt(), k.latencyMs(), k.stateAt()));
                }
            }
            for (Map.Entry<String, Known> e : others.entrySet()) {
                Member m = e.getValue().member();
                if (!ids.contains(e.getKey()) && !m.state().gone()) {
                    MemberState s = inView.contains(e.getKey()) && m.state() == MemberState.LEFT ? MemberState.LEFT : MemberState.DEAD;
                    e.setValue(new Known(m.withState(s, now), e.getValue().heardAt(), e.getValue().latencyMs(), now));
                    gone.add(e.getKey());
                } else if (!ids.contains(e.getKey()) && m.state() == MemberState.LEFT && inView.contains(e.getKey())) {
                    gone.add(e.getKey());                       // it said it was leaving, and now it has left
                }
            }
            inView = Set.copyOf(ids);
            viewId = v.id();
        } finally {
            lock.unlock();
        }
        boolean changed = !joined.isEmpty() || !gone.isEmpty();
        return changed ? new Changes(List.copyOf(joined), List.copyOf(gone), true) : Changes.NONE;
    }

    /**
     * Applies a member's vitals. An advertisement that is no address another machine can call (a loopback, wildcard or malformed one, in
     * any spelling) is never stored, so never used; it is looked at before the lock, since a name may need a lookup.
     */
    public Changes vitals(Member m) {
        if (m == null || m.nodeId() == null || m.nodeId().isBlank() || m.nodeId().equals(self().nodeId())) {
            return Changes.NONE;
        }
        if (!m.url().isEmpty() && !HostAddress.callableUrl(m.url())) {
            return Changes.NONE;
        }
        long now = clock.millis();
        List<String> joined = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        lock.lock();
        try {
            Known k = others.get(m.nodeId());
            boolean here = inView.contains(m.nodeId());
            MemberState s = !here ? (k == null ? MemberState.DEAD : k.member().state().gone() ? k.member().state() : MemberState.DEAD)
                    : m.state() == MemberState.LEFT ? MemberState.LEFT : MemberState.ALIVE;
            String address = m.clusterAddress().isEmpty() && k != null ? k.member().clusterAddress() : m.clusterAddress();
            MemberState before = k == null ? null : k.member().state();
            long stateAt = before == s ? k.stateAt() : now;
            Member next = new Member(m.nodeId(), m.url(), s, m.digestHash(), m.version(), m.protocol(), m.tags(), before == s ? k.member().since() : now,
                    m.drained(), address, m.switchesHash(), m.role());
            others.put(m.nodeId(), new Known(next, now, k == null ? -1 : k.latencyMs(), stateAt));
            if (here && s == MemberState.ALIVE && (before == null || before.gone())) {
                joined.add(m.nodeId());
            } else if (s == MemberState.LEFT && before == MemberState.ALIVE) {
                gone.add(m.nodeId());
            }
        } finally {
            lock.unlock();
        }
        boolean changed = !joined.isEmpty() || !gone.isEmpty();
        return changed ? new Changes(List.copyOf(joined), List.copyOf(gone), true) : new Changes(List.of(), List.of(), true);
    }

    /** Forgets the members out of the view for longer than {@code after}; answers their ids. */
    public List<String> forget(Duration after) {
        long now = clock.millis();
        List<String> out = new ArrayList<>();
        lock.lock();
        try {
            others.entrySet().removeIf(e -> {
                boolean old = e.getValue().member().state().gone() && now - e.getValue().stateAt() >= after.toMillis();
                if (old) {
                    out.add(e.getKey());
                }
                return old;
            });
        } finally {
            lock.unlock();
        }
        return out;
    }

    /** Every entry, this node's first. */
    public List<Member> entries() {
        lock.lock();
        try {
            List<Member> out = new ArrayList<>(others.size() + 1);
            out.add(self);
            others.values().forEach(k -> out.add(k.member()));
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** Every other member with what this node knows of it. */
    public List<Known> others() {
        lock.lock();
        try {
            return List.copyOf(others.values());
        } finally {
            lock.unlock();
        }
    }

    public Optional<Known> known(String nodeId) {
        lock.lock();
        try {
            return Optional.ofNullable(others.get(nodeId));
        } finally {
            lock.unlock();
        }
    }

    /** Other members that are alive, in a stable order. */
    public List<Member> alive() {
        return others().stream().map(Known::member).filter(m -> m.state() == MemberState.ALIVE).sorted(Comparator.comparing(Member::nodeId)).toList();
    }

    /** Other members not gone (alive or suspect), in a stable order. */
    public List<Member> reachable() {
        return others().stream().map(Known::member).filter(m -> !m.state().gone()).sorted(Comparator.comparing(Member::nodeId)).toList();
    }

    /** Records the latency of a call that answered (a digest pull): a smoothed average. */
    public void heard(String nodeId, double latencyMs) {
        lock.lock();
        try {
            Known k = others.get(nodeId);
            if (k != null) {
                double smoothed = k.latencyMs() < 0 ? latencyMs : 0.7 * k.latencyMs() + 0.3 * latencyMs;
                others.put(nodeId, new Known(k.member(), k.heardAt(), smoothed, k.stateAt()));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets another member's state as this node's own finding (a suspicion the failure detector raised and is verifying; tests), until the
     * next view or vitals say otherwise. True when the state changed.
     */
    public boolean mark(String nodeId, MemberState state) {
        long now = clock.millis();
        lock.lock();
        try {
            Known k = others.get(nodeId);
            if (k == null || k.member().state() == state) {
                return false;
            }
            others.put(nodeId, new Known(k.member().withState(state, now), k.heardAt(), k.latencyMs(), now));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Takes a gone member out (an administrator's remove); false when it is unknown. */
    public boolean remove(String nodeId) {
        lock.lock();
        try {
            return others.remove(nodeId) != null;
        } finally {
            lock.unlock();
        }
    }

    /** Members by id, this node's first, for the capability table and the API. */
    public Map<String, Member> byId() {
        lock.lock();
        try {
            Map<String, Member> out = new LinkedHashMap<>();
            out.put(self.nodeId(), self);
            others.forEach((id, k) -> out.put(id, k.member()));
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** Remembers members read from {@code members.json} at start: gone until the view names them. */
    public void remember(List<Member> saved) {
        long now = clock.millis();
        lock.lock();
        try {
            for (Member m : saved) {
                if (m != null && !m.nodeId().isBlank() && !m.nodeId().equals(self.nodeId()) && !others.containsKey(m.nodeId())) {
                    MemberState s = m.state() == MemberState.LEFT ? MemberState.LEFT : MemberState.DEAD;
                    others.put(m.nodeId(), new Known(m.withState(s, m.since()), 0, -1, now));
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
