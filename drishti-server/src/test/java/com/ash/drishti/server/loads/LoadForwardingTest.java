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
package com.ash.drishti.server.loads;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.net.NetCallFilter;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.net.PeerCalls;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The one way a data-load signal reaches the other members (DATA_LAKE_GUIDE.md, PO-5): with a stand-in for the members, a ready
 * load is passed on once to each live member as whoever made it (a person, or this node for the data inbox); a failed load, a load that
 * came from a peer and a server outside a cluster pass nothing on; the receiving side knows a member's own signal from a person's.
 */
class LoadForwardingTest {

    /** One call the stand-in members received. */
    record Call(String method, String path, JsonNode body, Principal as) {}

    /** Stand-in members: clustered or not, the peers alive, and every call made to them. */
    static final class Peers implements LoadForwarding.Members {
        final List<Call> calls = new ArrayList<>();
        boolean clustered = true;
        List<String> alive = List.of("drishti-b", "drishti-c");

        @Override
        public boolean clustered() {
            return clustered;
        }

        @Override
        public List<String> peers() {
            return alive;
        }

        @Override
        public List<PeerCalls.Answer> all(String method, String pathAndQuery, byte[] body, Principal p, Duration timeout) {
            try {
                calls.add(new Call(method, pathAndQuery, new ObjectMapper().readTree(body), p));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return alive.stream().map(n -> new PeerCalls.Answer(n, n.endsWith("c") ? 0 : 201, n.endsWith("c") ? "unreachable" : "{}")).toList();
        }
    }

    private static LoadService.Announcement ready() {
        return new LoadService.Announcement("trade", "2026-11-02", null, "ready", 48L, null, "inbox: trades-endur-2026-11-02.jsonl.gz",
                "inbox:20261103T150000Z-drishti-main#1", "loaded from the data inbox: trades-endur-2026-11-02.jsonl.gz");
    }

    @Test
    void theInboxsSignalGoesOnceToEveryLiveMemberAsThisNode() {
        Peers peers = new Peers();
        Principal self = LoadForwarding.node("drishti-main");
        assertThat(self.user()).isEqualTo("node:drishti-main");
        assertThat(self.roles()).containsExactly(Entitlements.SERVICE);
        List<String> to = new LoadForwarding(peers, Runnable::run).forward("trading", ready(), self);
        assertThat(to).containsExactly("drishti-b", "drishti-c");
        assertThat(peers.calls).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("POST");
            assertThat(c.path()).isEqualTo("/api/v1/packs/trading/loads");
            assertThat(c.as()).isEqualTo(self);
            assertThat(c.body().path("kind").asText()).isEqualTo("trade");
            assertThat(c.body().path("businessDate").asText()).isEqualTo("2026-11-02");
            assertThat(c.body().path("batchId").asText()).as("the same batch: each member records it once").isEqualTo("inbox:20261103T150000Z-drishti-main#1");
        });
    }

    @Test
    void aPersonsAnnouncementGoesOnAsThatPersonThroughTheSamePath() {
        Peers peers = new Peers();
        Principal person = new Principal("a.sinha", List.of("admin"));
        assertThat(new LoadForwarding(peers, Runnable::run).forward("trading", ready(), person)).hasSize(2);
        assertThat(peers.calls.get(0).as()).isEqualTo(person);
    }

    @Test
    void nothingGoesOnOutsideAClusterForAFailedLoadOrForALoadFromAPeer() {
        Peers peers = new Peers();
        LoadForwarding f = new LoadForwarding(peers, Runnable::run);
        LoadService.Announcement failed = new LoadService.Announcement("trade", "2026-11-02", null, "failed", null, null, null, "b1", "copy failed");
        assertThat(f.forward("trading", failed, LoadForwarding.node("drishti-main"))).isEmpty();
        List<List<String>> fromPeer = new ArrayList<>();
        NetCaller.with(new NetCaller.Caller(new Principal("node:drishti-b", List.of(Entitlements.SERVICE)), "drishti-b", "r-1"),
                () -> fromPeer.add(f.forward("trading", ready(), LoadForwarding.node("drishti-main"))));
        assertThat(fromPeer.get(0)).as("one hop").isEmpty();
        peers.clustered = false;
        assertThat(f.forward("trading", ready(), LoadForwarding.node("drishti-main"))).isEmpty();
        peers.clustered = true;
        peers.alive = List.of();
        assertThat(f.forward("trading", ready(), LoadForwarding.node("drishti-main"))).isEmpty();
        assertThat(new LoadForwarding((PeerCalls) null).forward("trading", ready(), LoadForwarding.node("x"))).isEmpty();
        assertThat(peers.calls).isEmpty();
    }

    @Test
    void theReceivingMemberTakesASignedNodeSignalButNotAPersonOrAnUnsignedCall() {
        MockHttpServletRequest signed = new MockHttpServletRequest("POST", "/api/v1/packs/trading/loads");
        signed.setAttribute(NetCallFilter.PEER, "drishti-main");
        assertThat(LoadForwarding.fromNode(signed, LoadForwarding.node("drishti-main"))).isTrue();
        assertThat(LoadForwarding.fromNode(signed, new Principal("a.sinha", List.of("viewer")))).as("a person forwarded keeps their own rights").isFalse();
        assertThat(LoadForwarding.fromNode(signed, new Principal("node:drishti-main", List.of()))).as("a node without the service role").isFalse();
        MockHttpServletRequest unsigned = new MockHttpServletRequest("POST", "/api/v1/packs/trading/loads");
        assertThat(LoadForwarding.fromNode(unsigned, LoadForwarding.node("drishti-main"))).as("not signed with the cluster key").isFalse();
    }
}
