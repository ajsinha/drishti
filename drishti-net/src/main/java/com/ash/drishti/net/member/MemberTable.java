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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * The member table of one node: its own entry (changed only by itself) and what it knows of every other member, with the
 * local bookkeeping the failure detector needs (when each member's heartbeat last rose here). Thread-safe: every change
 * takes one short lock (no I/O under it); readers get copies.
 */
public final class MemberTable {

    /** What this node knows of one other member, besides the gossiped entry. */
    public record Known(Member member, long beatAt, long heardAt, double latencyMs, long stateAt) {}

    /** What a merge changed: members seen for the first time (or back from gone), members now gone, and whether this node refuted news of itself. */
    public record Changes(List<String> joined, List<String> gone, boolean refuted, boolean changed) {
        public static final Changes NONE = new Changes(List.of(), List.of(), false, false);
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final NetClock clock;
    private Member self;
    private final Map<String, Known> others = new LinkedHashMap<>();

    public MemberTable(Member self, NetClock clock) {
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

    /** Changes this node's own entry (heartbeat, digest hash, drain, leave); only this node ever does. */
    public Member updateSelf(UnaryOperator<Member> change) {
        lock.lock();
        try {
            self = change.apply(self);
            return self;
        } finally {
            lock.unlock();
        }
    }

    /** Every entry, this node's first, as gossip sends it. */
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

    /** Every other member with its local bookkeeping. */
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

    /** Other members that are alive (suspect ones excluded), in a stable order. */
    public List<Member> alive() {
        return others().stream().map(Known::member).filter(m -> m.state() == MemberState.ALIVE)
                .sorted(Comparator.comparing(Member::nodeId)).toList();
    }

    /** Other members that are alive or suspect: those gossip still talks to (a suspect one may only be slow). */
    public List<Member> reachable() {
        return others().stream().map(Known::member).filter(m -> !m.state().gone()).sorted(Comparator.comparing(Member::nodeId)).toList();
    }

    /**
     * Merges a peer's table into this one by the merge rule ({@link Member#supersededBy}). News of this node itself that is
     * worse than alive (a suspicion, a death), or carries a higher incarnation, is refuted: this node raises its incarnation
     * above it and stays alive, and the next round spreads that.
     */
    public Changes merge(Collection<Member> incoming) {
        long now = clock.millis();
        List<String> joined = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        boolean refuted = false;
        boolean changed = false;
        // a loopback, wildcard, link-local or bridge advertisement (in any spelling, or a name that resolves here to one) is never stored,
        // so never sent on; looked at before the lock, since a name may need a lookup
        java.util.Set<Member> refused = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        String selfId = self.nodeId();
        for (Member m : incoming) {
            if (m != null && m.nodeId() != null && !m.nodeId().equals(selfId) && com.ash.drishti.net.config.HostAddress.loopbackUrl(m.url())) {
                refused.add(m);
            }
        }
        lock.lock();
        try {
            for (Member m : incoming) {
                if (m == null || m.nodeId() == null || m.nodeId().isBlank()) {
                    continue;
                }
                if (refused.contains(m)) {
                    continue;
                }
                if (m.nodeId().equals(self.nodeId())) {
                    if (self.state() == MemberState.LEFT) {
                        continue;                                  // leaving: nothing said about us is refuted any more
                    }
                    if (m.incarnation() > self.incarnation() || (m.incarnation() == self.incarnation() && m.state() != MemberState.ALIVE)) {
                        self = self.withIncarnation(Math.max(self.incarnation(), m.incarnation()) + 1).withState(MemberState.ALIVE, now);
                        refuted = true;
                        changed = true;
                    }
                    continue;
                }
                Known k = others.get(m.nodeId());
                if (k == null) {
                    others.put(m.nodeId(), new Known(m, now, now, -1, now));
                    if (!m.state().gone()) {
                        joined.add(m.nodeId());
                    }
                    changed = true;
                    continue;
                }
                Member old = k.member();
                if (!old.supersededBy(m)) {
                    continue;
                }
                long beatAt = m.heartbeat() > old.heartbeat() || m.incarnation() > old.incarnation() ? now : k.beatAt();
                long heardAt = beatAt == now ? now : k.heardAt();
                long stateAt = m.state() != old.state() ? now : k.stateAt();
                others.put(m.nodeId(), new Known(m, beatAt, heardAt, k.latencyMs(), stateAt));
                changed = true;
                if (old.state().gone() && !m.state().gone()) {
                    joined.add(m.nodeId());
                } else if (!old.state().gone() && m.state().gone()) {
                    gone.add(m.nodeId());
                }
            }
        } finally {
            lock.unlock();
        }
        return changed ? new Changes(List.copyOf(joined), List.copyOf(gone), refuted, true) : Changes.NONE;
    }

    /**
     * Sets another member's state as this node's own finding (the failure detector: suspect, dead). Its incarnation is kept, so
     * the member can refute by raising it.
     *
     * @return true when the state changed
     */
    public boolean mark(String nodeId, MemberState state) {
        long now = clock.millis();
        lock.lock();
        try {
            Known k = others.get(nodeId);
            if (k == null || k.member().state() == state || k.member().state() == MemberState.LEFT) {
                return false;
            }
            others.put(nodeId, new Known(k.member().withState(state, now), k.beatAt(), k.heardAt(), k.latencyMs(), now));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Records a direct answer from a member: when it was heard and how long it took (a smoothed average). */
    public void heard(String nodeId, double latencyMs) {
        long now = clock.millis();
        lock.lock();
        try {
            Known k = others.get(nodeId);
            if (k != null) {
                double smoothed = k.latencyMs() < 0 ? latencyMs : 0.7 * k.latencyMs() + 0.3 * latencyMs;
                others.put(nodeId, new Known(k.member(), k.beatAt(), now, smoothed, k.stateAt()));
            }
        } finally {
            lock.unlock();
        }
    }

    /** Takes a member out of the table (an administrator's remove of a dead or left node, or forgetting after forget-after). */
    public boolean remove(String nodeId) {
        lock.lock();
        try {
            return others.remove(nodeId) != null;
        } finally {
            lock.unlock();
        }
    }

    /** Members by id, for the capability table and the API. */
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
}
