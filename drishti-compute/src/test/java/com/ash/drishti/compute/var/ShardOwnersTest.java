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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Shard ownership by rendezvous hashing (VAR_SHARDING.md section 4): the same members in any order give the same owners on every member;
 * a fourth member takes about a quarter of the shards (and of the replica slots); a member leaving moves only its own shards, each keeping
 * its other replica; owners spread over zones when there are zones; a pinned shard goes to its member first.
 */
class ShardOwnersTest {

    static final int SHARDS = 2_000;

    static List<ShardOwners.Candidate> members(String... ids) {
        List<ShardOwners.Candidate> out = new ArrayList<>();
        for (String id : ids) {
            out.add(new ShardOwners.Candidate(id, ""));
        }
        return out;
    }

    static String key(int i) {
        return "LE-" + (i / 40) + "/DESK-" + (i % 40) + "..LE-" + (i / 40) + "/DESK-" + (i % 40);
    }

    @Test
    void everyMemberComputesTheSameOwnersWhateverOrderItSeesTheMembersIn() {
        List<ShardOwners.Candidate> a = members("node-a", "node-b", "node-c");
        List<ShardOwners.Candidate> b = new ArrayList<>(a);
        Collections.reverse(b);
        for (int i = 0; i < SHARDS; i++) {
            List<String> o = ShardOwners.owners(key(i), a, 2, null);
            assertThat(o).hasSize(2).doesNotHaveDuplicates();
            assertThat(ShardOwners.owners(key(i), b, 2, null)).isEqualTo(o);
        }
        assertThat(ShardOwners.owners(key(1), members("solo"), 2, null)).containsExactly("solo");     // fewer members than replicas
    }

    @Test
    void aFourthMemberMovesAboutAQuarter() {
        List<ShardOwners.Candidate> three = members("node-a", "node-b", "node-c");
        List<ShardOwners.Candidate> four = members("node-a", "node-b", "node-c", "node-d");
        int firstMoved = 0;
        int slotsMoved = 0;
        for (int i = 0; i < SHARDS; i++) {
            List<String> before = ShardOwners.owners(key(i), three, 2, null);
            List<String> after = ShardOwners.owners(key(i), four, 2, null);
            firstMoved += before.get(0).equals(after.get(0)) ? 0 : 1;
            for (String n : after) {
                slotsMoved += before.contains(n) ? 0 : 1;
                if (!before.contains(n)) {
                    assertThat(n).as("only the new member takes a slot").isEqualTo("node-d");
                }
            }
        }
        assertThat(firstMoved / (double) SHARDS).isBetween(0.20, 0.30);
        assertThat(slotsMoved / (2.0 * SHARDS)).isBetween(0.20, 0.30);
    }

    @Test
    void aMemberLeavingMovesOnlyItsShardsAndEachKeepsItsOtherReplica() {
        List<ShardOwners.Candidate> three = members("node-a", "node-b", "node-c");
        List<ShardOwners.Candidate> two = members("node-a", "node-b");
        for (int i = 0; i < SHARDS; i++) {
            List<String> before = ShardOwners.owners(key(i), three, 2, null);
            List<String> after = ShardOwners.owners(key(i), two, 2, null);
            if (!before.contains("node-c")) {
                assertThat(after).as("a shard node-c did not own keeps its owners").containsExactlyInAnyOrderElementsOf(before);
            } else {
                String kept = before.get(0).equals("node-c") ? before.get(1) : before.get(0);
                assertThat(after).as("its other replica stays an owner").contains(kept);
            }
        }
    }

    @Test
    void ownersSpreadOverZonesAndAPinComesFirst() {
        List<ShardOwners.Candidate> zoned = List.of(new ShardOwners.Candidate("a1", "az-1"), new ShardOwners.Candidate("a2", "az-1"),
                new ShardOwners.Candidate("b1", "az-2"), new ShardOwners.Candidate("b2", "az-2"));
        for (int i = 0; i < 500; i++) {
            List<String> o = ShardOwners.owners(key(i), zoned, 2, null);
            assertThat(o.get(0).charAt(0)).as("two zones").isNotEqualTo(o.get(1).charAt(0));
        }
        List<ShardOwners.Candidate> m = members("node-a", "node-b", "node-c");
        assertThat(ShardOwners.owners(key(5), m, 2, "node-c").get(0)).isEqualTo("node-c");
        assertThat(ShardOwners.owners(key(5), m, 2, "node-z")).isEqualTo(ShardOwners.owners(key(5), m, 2, null));   // not a member: ignored
    }
}
