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
package com.ash.drishti.compute.var;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who owns a shard (VAR_SHARDING.md section 4): rendezvous (highest random weight) hashing of the shard's key over the members that may
 * own shards, the top {@code replicas} by weight. A pure function of its inputs: every member that sees the same members computes the same
 * owners without asking anyone (no consensus protocol), and a member that joins or leaves moves only the shards it wins or held (about
 * 1/N of them). Two refinements: owners spread over zones when the members carry a zone (a second replica in another zone when there is
 * one), and a shard pinned to a member (for tests) is owned by it first.
 */
public final class ShardOwners {

    /** A member that may own shards, with its zone (empty: none). */
    public record Candidate(String node, String zone) {
        public Candidate {
            zone = zone == null ? "" : zone;
        }
    }

    private ShardOwners() {}

    /**
     * The owners of a shard, the first owner first.
     *
     * @param key the shard's ownership key ({@link ShardMap.Shard#ownershipKey()})
     * @param members the members that may own shards now
     * @param replicas how many owners a shard has (fewer when there are fewer members)
     * @param pinned a member this shard is pinned to (null or blank: none); used only when it is among {@code members}
     */
    public static List<String> owners(String key, Collection<Candidate> members, int replicas, String pinned) {
        List<Candidate> ranked = new ArrayList<>(members);
        ranked.sort(Comparator.comparingLong((Candidate c) -> weight(c.node(), key)).reversed().thenComparing(Candidate::node));
        List<String> out = new ArrayList<>();
        Set<String> zones = new LinkedHashSet<>();
        if (pinned != null && !pinned.isBlank()) {
            for (Candidate c : ranked) {
                if (c.node().equals(pinned)) {
                    out.add(c.node());
                    zones.add(c.zone());
                }
            }
        }
        int want = Math.max(1, replicas);
        while (out.size() < Math.min(want, ranked.size())) {
            Candidate pick = null;
            for (Candidate c : ranked) {                                   // the heaviest member in a zone not used yet ...
                if (!out.contains(c.node()) && !c.zone().isEmpty() && !zones.contains(c.zone())) {
                    pick = c;
                    break;
                }
            }
            if (pick == null) {
                for (Candidate c : ranked) {                               // ... else the heaviest member left
                    if (!out.contains(c.node())) {
                        pick = c;
                        break;
                    }
                }
            }
            out.add(pick.node());
            zones.add(pick.zone());
        }
        return out;
    }

    /** A member's weight for a key: the first 60 bits of SHA-256 of {@code node|key}. */
    public static long weight(String node, String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((node + "|" + key).getBytes(StandardCharsets.UTF_8));
            return Long.parseUnsignedLong(HexFormat.of().formatHex(h, 0, 8).substring(0, 15), 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
