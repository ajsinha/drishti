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
package com.ash.drishti.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.net.member.MemberTable;
import com.ash.drishti.net.member.PeersFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase N1's acceptance on an in-process cluster with a fake clock (DRISHTI_NET.md sections 5 and 16). */
class MembershipTest {

    @TempDir
    Path dir;

    private static SimCluster fiveFromOneSeed() {
        SimCluster c = new SimCluster();
        c.add("a", 1, List.of());
        for (String id : List.of("b", "c", "d", "e")) {
            c.add(id, 1, List.of(SimCluster.url("a")));
        }
        return c;
    }

    private static boolean allSee(SimCluster c, int others) {
        return c.byUrl.values().stream().filter(n -> n.up).allMatch(n -> n.table.alive().size() == others);
    }

    @Test
    void fiveNodesStartedFromOneSeedAllListEachOtherWithinFiveSeconds() {
        SimCluster c = fiveFromOneSeed();
        int seconds = 0;
        while (!allSee(c, 4) && seconds < 5) {
            c.tick();
            seconds++;
        }
        assertThat(allSee(c, 4)).as("after %d s", seconds).isTrue();
        assertThat(seconds).isLessThanOrEqualTo(5);
    }

    @Test
    void aKilledNodeIsDeadEverywhereWithinFortySeconds() {
        SimCluster c = fiveFromOneSeed();
        for (int i = 0; i < 5; i++) {
            c.tick();
        }
        c.node("c").up = false;
        int seconds = 0;
        while (seconds < 40 && !c.byUrl.values().stream().filter(n -> n.up).allMatch(n -> n.stateOf("c") == MemberState.DEAD)) {
            c.tick();
            seconds++;
        }
        for (String id : List.of("a", "b", "d", "e")) {
            assertThat(c.node(id).stateOf("c")).as(id + " after " + seconds + " s").isEqualTo(MemberState.DEAD);
            assertThat(c.node(id).table.alive()).extracting(Member::nodeId).containsExactlyInAnyOrderElementsOf(
                    Set.of("a", "b", "d", "e").stream().filter(x -> !x.equals(id)).toList());
        }
        assertThat(seconds).isLessThanOrEqualTo(40).isGreaterThan(5);   // never dead before suspect-after
    }

    @Test
    void aRestartedNodeRejoinsFromItsFileWithNoSeeds() {
        SimCluster c = fiveFromOneSeed();
        for (int i = 0; i < 5; i++) {
            c.tick();
        }
        PeersFile file = new PeersFile(dir.resolve("net").resolve("peers.json"));
        assertThat(file.save(c.node("c").table, c.now.get())).isTrue();
        c.node("c").up = false;
        for (int i = 0; i < 40; i++) {
            c.tick();
        }
        assertThat(c.node("a").stateOf("c")).isEqualTo(MemberState.DEAD);
        // the restart: a new table from the file (its incarnation raised), no seeds, the file's members as the join list
        PeersFile.Saved saved = file.read().orElseThrow();
        c.byUrl.remove(SimCluster.url("c"));
        SimCluster.Node back = c.add("c", saved.self().incarnation() + 1, saved.rejoinUrls());
        int seconds = 0;
        while (seconds < 10 && !(allSee(c, 4))) {
            c.tick();
            seconds++;
        }
        assertThat(allSee(c, 4)).as("rejoined after %d s", seconds).isTrue();
        assertThat(back.table.alive()).hasSize(4);
    }

    @Test
    void aNodeLeavingCleanlyIsDroppedAtOnce() {
        SimCluster c = fiveFromOneSeed();
        for (int i = 0; i < 5; i++) {
            c.tick();
        }
        c.node("d").gossiper.leave(3);
        c.node("d").up = false;
        for (String id : List.of("a", "b", "c", "e")) {
            assertThat(c.node(id).stateOf("d")).as(id).isEqualTo(MemberState.LEFT);
        }
    }

    @Test
    void aSplitCarriesOnOnEachSideAndHealsByGossip() {
        SimCluster c = fiveFromOneSeed();
        for (int i = 0; i < 5; i++) {
            c.tick();
        }
        c.split(Set.of("a", "b"));
        for (int i = 0; i < 40; i++) {
            c.tick();
        }
        assertThat(c.node("a").table.alive()).extracting(Member::nodeId).containsExactly("b");
        assertThat(c.node("c").table.alive()).extracting(Member::nodeId).containsExactlyInAnyOrder("d", "e");
        c.heal();
        int seconds = 0;
        while (seconds < 70 && !allSee(c, 4)) {
            c.tick();
            seconds++;
        }
        assertThat(allSee(c, 4)).as("healed after %d s", seconds).isTrue();
    }

    @Test
    void theMergeRuleHigherIncarnationThenWorseStateThenNewerHeartbeat() {
        long[] t = {1000};
        MemberTable table = new MemberTable(m("self", 1, 0, MemberState.ALIVE), () -> t[0]);
        table.merge(List.of(m("x", 1, 5, MemberState.ALIVE)));
        table.merge(List.of(m("x", 1, 4, MemberState.ALIVE)));               // older heartbeat: ignored
        assertThat(table.known("x").orElseThrow().member().heartbeat()).isEqualTo(5);
        table.merge(List.of(m("x", 1, 3, MemberState.SUSPECT)));             // worse state at equal incarnation wins
        assertThat(table.known("x").orElseThrow().member().state()).isEqualTo(MemberState.SUSPECT);
        table.merge(List.of(m("x", 1, 9, MemberState.ALIVE)));               // an alive at the same incarnation does not undo it
        assertThat(table.known("x").orElseThrow().member().state()).isEqualTo(MemberState.SUSPECT);
        table.merge(List.of(m("x", 2, 0, MemberState.ALIVE)));               // only the member raising its incarnation does
        assertThat(table.known("x").orElseThrow().member().state()).isEqualTo(MemberState.ALIVE);
        // news of this node itself is refuted, never taken
        MemberTable.Changes ch = table.merge(List.of(m("self", 1, 0, MemberState.DEAD)));
        assertThat(ch.refuted()).isTrue();
        assertThat(table.self().state()).isEqualTo(MemberState.ALIVE);
        assertThat(table.self().incarnation()).isEqualTo(2);
        table.merge(List.of(m("self", 7, 0, MemberState.SUSPECT)));
        assertThat(table.self().incarnation()).isEqualTo(8);
    }

    @Test
    void anAdvertisementNoOtherMachineCanCallIsNeverStored() {
        // QA1-09: every spelling of loopback and wildcard, and a name that resolves here to loopback, are dropped at the gossip intake
        MemberTable table = new MemberTable(m("self", 1, 0, MemberState.ALIVE), () -> 1000);
        List<String> bad = new java.util.ArrayList<>(List.of("http://0:18611", "http://localhost.:18611", "http://ip6-localhost:18611",
                "http://2130706433:18611", "http://0x7f000001:18611", "http://127.1:18611", "http://[fe80::1]:18611", "http://169.254.10.1:18611"));
        hostName().filter(h -> com.ash.drishti.net.config.HostAddress.loopbackOrWildcard(h)).ifPresent(h -> bad.add("http://" + h + ":18611"));
        int i = 0;
        for (String url : bad) {
            table.merge(List.of(new Member("bad-" + i++, url, 1, 1, MemberState.ALIVE, "", "", Member.PROTOCOL, List.of(), 0, false)));
        }
        assertThat(table.others()).isEmpty();
        table.merge(List.of(new Member("good", "http://192.0.2.10:18611", 1, 1, MemberState.ALIVE, "", "", Member.PROTOCOL, List.of(), 0, false)));
        assertThat(table.known("good")).isPresent();
    }

    /** This machine's host name, when it has one. */
    static java.util.Optional<String> hostName() {
        try {
            return java.util.Optional.of(java.net.InetAddress.getLocalHost().getHostName());
        } catch (java.net.UnknownHostException e) {
            return java.util.Optional.empty();
        }
    }

    @Test
    void peersFileIsWrittenAtomicallyAndACorruptOneIsIgnored() throws Exception {
        MemberTable table = new MemberTable(m("self", 3, 0, MemberState.ALIVE), () -> 1000);
        table.merge(List.of(m("x", 1, 1, MemberState.ALIVE), m("y", 1, 1, MemberState.LEFT), m("z", 1, 1, MemberState.SUSPECT)));
        Path f = dir.resolve("net").resolve("peers.json");
        PeersFile file = new PeersFile(f);
        assertThat(file.save(table, 1000)).isTrue();
        try (var s = Files.list(f.getParent())) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactly("peers.json");   // no temporary file left
        }
        PeersFile.Saved saved = file.read().orElseThrow();
        assertThat(saved.nodeId()).isEqualTo("self");
        assertThat(saved.self().incarnation()).isEqualTo(3);
        assertThat(saved.rejoinUrls()).containsExactly(SimCluster.url("x"), SimCluster.url("z"));   // alive first, never left
        Files.writeString(f, "{ not json");
        assertThat(file.read()).isEmpty();
    }

    private static Member m(String id, long inc, long beat, MemberState s) {
        return new Member(id, SimCluster.url(id), inc, beat, s, "", "", Member.PROTOCOL, List.of(), 0, false);
    }
}
