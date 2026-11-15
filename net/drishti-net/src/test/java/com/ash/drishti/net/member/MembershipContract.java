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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * The contract every {@link Membership} provider passes (CLUSTER_I5_DESIGN.md sections 3 and 12.1): agreed views in one order, a clean
 * leave seen at once, a killed member out of the view within the failure detector's time, small messages to every member in order, the
 * 4 KB limit, a rejoin. A provider's test extends this class and says how a group of members is made and how one is killed.
 */
public abstract class MembershipContract {

    /** A group of members, not joined yet, for one test. */
    protected interface Group extends AutoCloseable {
        Membership member(int i);

        int size();

        /** Kills member {@code i} as a crashed process: no leave. */
        void kill(int i);

        @Override
        void close();
    }

    /** {@code size} members of one cluster (each with its own port, seeds naming the others). */
    protected abstract Group group(int size) throws Exception;

    /** How long a killed member may stay in the others' view (the provider's failure detection). */
    protected Duration killDetection() {
        return Duration.ofSeconds(15);
    }

    protected static boolean await(Duration max, BooleanSupplier ok) throws InterruptedException {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end) {
            if (ok.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return ok.getAsBoolean();
    }

    private static void joinAll(Group g) {
        for (int i = 0; i < g.size(); i++) {
            g.member(i).join();
        }
    }

    @Test
    void membersJoinIntoOneAgreedView() throws Exception {
        try (Group g = group(3)) {
            joinAll(g);
            assertThat(await(Duration.ofSeconds(20), () -> g.member(0).view().size() == 3 && g.member(1).view().size() == 3
                    && g.member(2).view().size() == 3)).as("every member sees three").isTrue();
            assertThat(await(Duration.ofSeconds(5), () -> g.member(0).view().equals(g.member(1).view()) && g.member(1).view().equals(g.member(2).view())))
                    .as("the same view, id and order, everywhere").isTrue();
            View v = g.member(0).view();
            assertThat(v.ids()).containsExactlyInAnyOrder(g.member(0).self().id(), g.member(1).self().id(), g.member(2).self().id());
            assertThat(v.coordinator()).isEqualTo(v.members().get(0).id());
            assertThat(v.member(g.member(1).self().id())).isPresent();
        }
    }

    @Test
    void aCleanLeaveIsSeenAtOnce() throws Exception {
        try (Group g = group(3)) {
            joinAll(g);
            assertThat(await(Duration.ofSeconds(20), () -> g.member(0).view().size() == 3)).isTrue();
            List<ViewChange> seen = Collections.synchronizedList(new ArrayList<>());
            g.member(0).onChange(seen::add);
            String leaving = g.member(2).self().id();
            g.member(2).leave();
            assertThat(g.member(2).view()).isEqualTo(View.NONE);
            assertThat(await(Duration.ofSeconds(3), () -> g.member(0).view().size() == 2 && g.member(1).view().size() == 2))
                    .as("a clean leave needs no failure detection").isTrue();
            assertThat(await(Duration.ofSeconds(1), () -> seen.stream().anyMatch(c -> c.left().contains(leaving)))).isTrue();
        }
    }

    @Test
    void aKilledMemberLeavesTheViewWithinTheFailureDetectorsTime() throws Exception {
        try (Group g = group(3)) {
            joinAll(g);
            assertThat(await(Duration.ofSeconds(20), () -> g.member(0).view().size() == 3)).isTrue();
            String killed = g.member(1).self().id();
            g.kill(1);
            assertThat(await(killDetection(), () -> !g.member(0).view().contains(killed) && !g.member(2).view().contains(killed)))
                    .as("out of the view within " + killDetection()).isTrue();
            assertThat(g.member(0).view()).isEqualTo(g.member(2).view());
        }
    }

    @Test
    void aMessageReachesEveryMemberInOrder() throws Exception {
        try (Group g = group(3)) {
            joinAll(g);
            assertThat(await(Duration.ofSeconds(20), () -> g.member(0).view().size() == 3 && g.member(2).view().size() == 3)).isTrue();
            List<List<String>> got = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                List<String> mine = Collections.synchronizedList(new ArrayList<>());
                got.add(mine);
                g.member(i).onMessage("vitals", (from, bytes) -> mine.add(from + "|" + new String(bytes, StandardCharsets.UTF_8)));
                g.member(i).onMessage("other", (from, bytes) -> mine.add("wrong topic"));
            }
            String from = g.member(0).self().id();
            List<String> sent = new ArrayList<>();
            for (int k = 0; k < 20; k++) {
                g.member(0).publish("vitals", ("m" + k).getBytes(StandardCharsets.UTF_8));
                sent.add(from + "|m" + k);
            }
            assertThat(await(Duration.ofSeconds(10), () -> got.stream().allMatch(l -> l.size() == 20))).isTrue();
            got.forEach(l -> assertThat(List.copyOf(l)).containsExactlyElementsOf(sent));
        }
    }

    @Test
    void aMessageOverFourKilobytesOrBeforeJoiningIsRefused() throws Exception {
        try (Group g = group(1)) {
            assertThatThrownBy(() -> g.member(0).publish("vitals", new byte[10])).isInstanceOf(IllegalStateException.class);
            g.member(0).join();
            assertThatThrownBy(() -> g.member(0).publish("vitals", new byte[Membership.MAX_MESSAGE_BYTES + 1]))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most");
        }
    }

    @Test
    void aMemberThatLeftJoinsAgain() throws Exception {
        try (Group g = group(2)) {
            joinAll(g);
            assertThat(await(Duration.ofSeconds(20), () -> g.member(0).view().size() == 2)).isTrue();
            g.member(1).leave();
            assertThat(await(Duration.ofSeconds(3), () -> g.member(0).view().size() == 1)).isTrue();
            g.member(1).join();
            assertThat(await(Duration.ofSeconds(20), () -> g.member(0).view().size() == 2 && g.member(1).view().size() == 2)).isTrue();
        }
    }
}
