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
package com.ash.drishti.server.shard;

import com.ash.drishti.compute.shard.ShardMap;
import com.ash.drishti.compute.shard.ShardOwners;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Who owns a sharded measure's shards now, for any measure (VAR_SHARDING.md section 4): the members that may own shards (alive, the measure's
 * sharding on there, not coordinator-only), as a view that moves only once the membership has been the same for {@code stable-seconds}
 * (until it first has, the members eligible now); a draining member leaves the view at once. A shard's owners are the top {@code replicas}
 * of the view by rendezvous hashing of the shard's ownership key ({@link ShardOwners}), a pinned member first. Every member that sees the
 * same members computes the same owners: no consensus protocol. Looked at again at most every quarter of a second.
 */
public final class ShardMembership {

    private final ShardPeers peers;
    private final Supplier<ShardPeers.State> mine;
    private final IntSupplier stableSeconds;
    private final String zoneTag;
    private final Map<String, String> pins;
    private volatile List<ShardOwners.Candidate> current = List.of();
    private volatile long changedAt = System.currentTimeMillis();
    private volatile List<ShardOwners.Candidate> stable;
    private volatile List<ShardOwners.Candidate> lastView = List.of();
    private volatile long lastViewAt;

    /**
     * @param mine this member's own state now (never read from its digest)
     * @param zoneTag the member tag prefix naming a member's zone (blank: none)
     * @param pins a shard (its id or range) pinned to a member, the same on every member
     */
    public ShardMembership(ShardPeers peers, Supplier<ShardPeers.State> mine, IntSupplier stableSeconds, String zoneTag, Map<String, String> pins) {
        this.peers = peers;
        this.mine = mine;
        this.stableSeconds = stableSeconds;
        this.zoneTag = zoneTag == null ? "" : zoneTag;
        this.pins = pins == null ? Map.of() : Map.copyOf(pins);
    }

    public ShardPeers peers() {
        return peers;
    }

    /** This member, then every other member alive, each with what it says of the measure's shards. */
    public List<ShardPeers.Peer> members() {
        return peers.peers(mine.get(), zoneTag);
    }

    /** The members that may own shards now (see the class). */
    public List<ShardOwners.Candidate> view() {
        long at = System.currentTimeMillis();
        if (at - lastViewAt < 250) {
            return lastView;
        }
        List<ShardOwners.Candidate> v = look(at);
        lastView = v;
        lastViewAt = at;
        return v;
    }

    /** True once the membership has been stable for {@code stable-seconds}: shards move (are built and released) only from then on. */
    public boolean settled() {
        view();
        return stable != null;
    }

    /** Forgets the view looked at last (a drain; a test that moves members). */
    public void refresh() {
        lastViewAt = 0;
    }

    private List<ShardOwners.Candidate> look(long t) {
        List<ShardPeers.Peer> ps = members();
        List<ShardOwners.Candidate> now = ps.stream().filter(p -> p.routable() && p.state().on() && p.state().owner())
                .map(p -> new ShardOwners.Candidate(p.node(), p.zone())).sorted(Comparator.comparing(ShardOwners.Candidate::node)).toList();
        if (!now.equals(current)) {
            current = now;
            changedAt = t;
        }
        if (t - changedAt >= stableSeconds.getAsInt() * 1000L) {
            stable = now;
        }
        Set<String> drainingNow = new TreeSet<>();
        ps.stream().filter(p -> p.state().draining()).forEach(p -> drainingNow.add(p.node()));
        return (stable != null ? stable : now).stream().filter(c -> !drainingNow.contains(c.node())).toList();
    }

    /** A shard's owners now, first owner first. */
    public List<String> owners(ShardMap.Shard s, int replicas) {
        String pin = pins.getOrDefault(s.id(), pins.get(s.range()));
        return ShardOwners.owners(s.ownershipKey(), view(), replicas, pin);
    }
}
