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

import com.ash.drishti.net.config.NetFolder;
import com.ash.drishti.net.config.NetSchema;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transport settings: node.yaml's cluster port (7946 by default, a number; the gossip-era gossip-port is refused naming it), seeds,
 * routing.yaml's peer-protocol, and a member's vitals on the wire (CLUSTER_I5_DESIGN.md sections 4.1 and 8).
 */
class TransportSettingsTest {

    private static final String KEY = "a-cluster-key-of-at-least-thirty-two-bytes";
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @TempDir
    Path tmp;

    private NetFolder folder() {
        return new NetFolder(tmp.resolve("config/net"), tmp.resolve("data/net"), new NetSchema(Map.of("DRISHTI_NET_SECRET", KEY)::get));
    }

    private NetFolder.Loaded node(String extra) {
        NetFolder f = folder();
        f.write("node.yaml", "enabled: true\nadvertise-url: http://192.0.2.10:18783\nsecret: ${DRISHTI_NET_SECRET}\n" + (extra == null ? "" : extra + "\n"), null);
        return f.load(null);
    }

    @Test
    void theClusterPortIs7946ByDefaultAndANumberWhenSet() {
        assertThat(node(null).config().node().clusterPort()).isEqualTo(7946);
        assertThat(node(null).config().node().agreementPort()).isEqualTo(7947);
        assertThat(node("cluster-port: 18790").config().node().clusterPort()).isEqualTo(18790);
        assertThat(node("cluster-port: auto").refused("node.yaml")).as("seeds are dialled: a number").isTrue();
        assertThat(node("cluster-port: 70000").refused("node.yaml")).isTrue();
        assertThat(node("cluster-port: 7946\nagreement-port: 7946").refused("node.yaml")).isTrue();
    }

    @Test
    void theGossipPortIsRefusedNamingTheClusterPort() {
        NetFolder.Loaded bad = node("gossip-port: auto");
        assertThat(bad.refused("node.yaml")).isTrue();
        assertThat(bad.problems().get("node.yaml")).anySatisfy(p -> {
            assertThat(p.field()).isEqualTo("gossip-port");
            assertThat(p.message()).contains("cluster-port");
        });
    }

    @Test
    void seedsAreHostAndClusterPortOnRealAddresses() {
        assertThat(node("seeds: [192.0.2.11:7946, 192.0.2.12:7946]").config().node().seeds()).containsExactly("192.0.2.11:7946", "192.0.2.12:7946");
        assertThat(node("seeds: [127.0.0.1:7946]").refused("node.yaml")).isTrue();
        assertThat(node("seeds: [192.0.2.11]").refused("node.yaml")).as("no port").isTrue();
    }

    @Test
    void peerDataIsHttp2ByDefaultAndMayBeHttp1() {
        NetFolder f = folder();
        assertThat(f.load(null).config().routing().http2()).isTrue();
        f.write("routing.yaml", "peer-protocol: http1\n", null);
        assertThat(f.load(null).config().routing().http2()).isFalse();
        f.write("routing.yaml", "peer-protocol: spdy\n", null);
        NetFolder.Loaded l = f.load(null);
        assertThat(l.refused("routing.yaml")).isTrue();
        assertThat(l.problems().get("routing.yaml")).anySatisfy(p -> assertThat(p.message()).contains("is not http2 or http1"));
    }

    @Test
    void aMembersVitalsTravelWholeAndAGossipEraEntryReadsAsProtocolOne() throws Exception {
        Member m = new Member("n-1", "http://192.0.2.10:18783", MemberState.ALIVE, "h1", "2.9.11", Member.PROTOCOL, List.of("region:emea"), 0, false,
                "192.0.2.10:7946", "s1", "server");
        Member back = JSON.readValue(JSON.writeValueAsBytes(m), Member.class);
        assertThat(back).isEqualTo(m);
        assertThat(JSON.writeValueAsBytes(m).length).as("vitals fit the channel's 4 KB").isLessThan(1024);
        String old = "{\"nodeId\":\"n-2\",\"url\":\"https://192.0.2.11:8480\",\"incarnation\":1,\"heartbeat\":3,\"state\":\"alive\",\"digestHash\":\"\","
                + "\"version\":\"2.6.6\",\"protocol\":1,\"tags\":[],\"since\":0,\"drained\":false,\"gossipPort\":41234}";
        Member older = JSON.readValue(old, Member.class);
        assertThat(older.protocol()).isEqualTo(1);
        assertThat(older.routable()).as("a node of protocol 1 is listed, never routed to").isFalse();
    }
}
