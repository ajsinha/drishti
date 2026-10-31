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

import com.ash.drishti.net.digest.CapabilityTable;
import com.ash.drishti.net.digest.Digest;
import com.ash.drishti.net.digest.Versions;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The capability digest and table (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, sections 6 and 10): renderers, the newest pack, replicas and shards, warnings. */
class CapabilityTableTest {

    static Digest.SourceEntry src(String connector, String table) {
        return new Digest.SourceEntry(connector, "delta", table, "2026-10-02", "2026-10-16", "v1", true, true, false);
    }

    static Digest digest(String node, String packVersion, String hash, List<Digest.SourceEntry> trades, String calendar, List<String> redact) {
        return new Digest(node, "http://" + node, "2.5.0", 1, List.of(new Digest.PackEntry("trading", "TRD", packVersion, hash, false)),
                List.of(new Digest.KindEntry("trade", "TRD", "Trade", "trading", true, trades)), new Digest.Load(1, 2), calendar, List.of(),
                redact, Map.of(), List.of(), 1);
    }

    static Member member(String id, MemberState s, boolean drained) {
        return new Member(id, "http://" + id, 1, 1, s, "", "2.5.0", Member.PROTOCOL, List.of(), 0, drained);
    }

    @Test
    void theHashIgnoresTheLoadAndTheBuildTimeButNotWhatTheNodeServes() {
        Digest a = digest("a", "2.4.0", "h", List.of(src("trade-endur", "delta:1")), "USNY", List.of());
        Digest busier = new Digest(a.nodeId(), a.url(), a.version(), a.protocol(), a.packs(), a.kinds(), new Digest.Load(99, 99), a.calendar(), a.tags(),
                a.redact(), a.access(), a.reports(), 99);
        assertThat(busier.hash()).isEqualTo(a.hash());
        assertThat(digest("a", "2.4.1", "h", List.of(src("trade-endur", "delta:1")), "USNY", List.of()).hash()).isNotEqualTo(a.hash());
        assertThat(a.kindOfMnemonic("trd")).contains("trade");
    }

    @Test
    void theNewestVersionOfAPackIsTheOneServed() {
        assertThat(Versions.compare("2.10.0", "2.9.1")).isPositive();
        assertThat(Versions.compare("2.4.0", "2.4.0-rc1")).isPositive();
        assertThat(Versions.compare("2.4", "2.4.0")).isZero();
        Map<String, Digest> peers = Map.of("b", digest("b", "2.4.0", "x", List.of(), "USNY", List.of()));
        CapabilityTable t = CapabilityTable.of("a", digest("a", "2.3.0", "y", List.of(), "USNY", List.of()),
                List.of(member("a", MemberState.ALIVE, false), member("b", MemberState.ALIVE, false)), peers, Map.of());
        assertThat(t.newest("trading")).contains("2.4.0");
        assertThat(t.renderers("trade")).extracting(CapabilityTable.Renderer::nodeId).containsExactly("a", "b");
        // a drained or suspect peer is not routed to, so it does not count
        CapabilityTable drained = CapabilityTable.of("a", digest("a", "2.3.0", "y", List.of(), "USNY", List.of()),
                List.of(member("a", MemberState.ALIVE, false), member("b", MemberState.ALIVE, true)), peers, Map.of()).routable();
        assertThat(drained.newest("trading")).contains("2.3.0");
    }

    @Test
    void replicasAreAskedOnceAndShardsAll() {
        // a reads ENDUR; b reads the same ENDUR table (a replica) and CALYPSO; c and d both read MUREX (replicas of each other); e an
        // undated source with no identity (a shard of its own)
        Digest a = digest("a", "2.4.0", "h", List.of(src("trade-endur", "delta:endur")), "USNY", List.of());
        Map<String, Digest> peers = Map.of(
                "b", digest("b", "2.4.0", "h", List.of(src("trade-endur", "delta:endur"), src("trade-calypso", "delta:calypso")), "USNY", List.of()),
                "c", digest("c", "2.4.0", "h", List.of(src("trade-murex", "delta:murex")), "USNY", List.of()),
                "d", digest("d", "2.4.0", "h", List.of(src("trade-murex", "delta:murex")), "USNY", List.of()),
                "e", digest("e", "2.4.0", "h", List.of(src("trade-files", "")), "USNY", List.of()));
        List<Member> members = List.of(member("a", MemberState.ALIVE, false), member("b", MemberState.ALIVE, false), member("c", MemberState.ALIVE, false),
                member("d", MemberState.ALIVE, false), member("e", MemberState.ALIVE, false));
        CapabilityTable t = CapabilityTable.of("a", a, members, peers, Map.of("b", 5.0, "c", 9.0, "d", 2.0, "e", 1.0));
        assertThat(t.scatterTargets("trade")).containsExactlyInAnyOrder("b", "d", "e");   // d is faster than c, which reads the same table
        // roles that may reach only a and c
        CapabilityTable reach = t.reachable(n -> n.id().equals("c"));
        assertThat(reach.scatterTargets("trade")).containsExactly("c");
        assertThat(reach.nodes()).extracting(CapabilityTable.Node::id).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void theWarningsNameEqualVersionsWithOtherContentsOtherCalendarsAndOtherMasks() {
        Map<String, Digest> peers = Map.of("b", digest("b", "2.4.0", "other", List.of(), "GBLO", List.of("trader")));
        CapabilityTable t = CapabilityTable.of("a", digest("a", "2.4.0", "mine", List.of(), "USNY", List.of()),
                List.of(member("a", MemberState.ALIVE, false), member("b", MemberState.ALIVE, false)), peers, Map.of());
        assertThat(t.warnings()).extracting(CapabilityTable.Warning::what).containsExactlyInAnyOrder("pack-content", "calendar", "redact");
    }
}
