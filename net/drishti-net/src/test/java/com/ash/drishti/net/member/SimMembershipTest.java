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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** {@link SimMembership} passes the membership contract, and its partitions behave as JGroups' do: each side its own view, one on healing. */
class SimMembershipTest extends MembershipContract {

    @Override
    protected Group group(int size) {
        SimMembership.Network net = new SimMembership.Network();
        List<SimMembership> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            members.add(net.member("n" + i));
        }
        return new Group() {
            @Override
            public Membership member(int i) {
                return members.get(i);
            }

            @Override
            public int size() {
                return members.size();
            }

            @Override
            public void kill(int i) {
                members.get(i).crash();
            }

            @Override
            public void close() {
                members.forEach(SimMembership::close);
            }
        };
    }

    @Override
    protected Duration killDetection() {
        return Duration.ofMillis(200);
    }

    @Test
    void aPartitionGivesEachSideItsOwnViewAndHealingMergesThem() {
        SimMembership.Network net = new SimMembership.Network();
        SimMembership a = net.member("a");
        SimMembership b = net.member("b");
        SimMembership c = net.member("c");
        a.join();
        b.join();
        c.join();
        assertThat(a.view().ids()).containsExactly("a", "b", "c");
        net.partition(List.of("a", "b"), List.of("c"));
        assertThat(a.view().ids()).containsExactly("a", "b");
        assertThat(c.view().ids()).containsExactly("c");
        AtomicBoolean merged = new AtomicBoolean();
        c.onChange(ch -> merged.set(ch.merged()));
        net.heal();
        assertThat(c.view().ids()).containsExactly("a", "b", "c");
        assertThat(merged).isTrue();
        assertThat(a.view()).isEqualTo(c.view());
    }
}
