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
import com.ash.drishti.net.config.NodeSettings;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The transport settings of 2.6.7: node.yaml's gossip-port, routing.yaml's peer-protocol, and a member's gossip port on the wire. */
class TransportSettingsTest {

    private static final String KEY = "a-cluster-key-of-at-least-thirty-two-bytes";
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @TempDir
    Path tmp;

    private NetFolder folder() {
        return new NetFolder(tmp.resolve("config/net"), tmp.resolve("data/net"), new NetSchema(Map.of("DRISHTI_NET_SECRET", KEY)::get));
    }

    private NetFolder.Loaded node(String gossipPort) {
        NetFolder f = folder();
        f.write("node.yaml", "enabled: true\nadvertise-url: http://192.0.2.10:18783\nsecret: ${DRISHTI_NET_SECRET}\n"
                + (gossipPort == null ? "" : "gossip-port: " + gossipPort + "\n"), null);
        return f.load(null);
    }

    @Test
    void theGossipPortIsAutoByDefaultAndMayBeOffOrAPort() {
        assertThat(node(null).config().node().gossipPort()).isEqualTo(NodeSettings.GOSSIP_AUTO);
        assertThat(node("auto").config().node().gossipPort()).isEqualTo(NodeSettings.GOSSIP_AUTO);
        assertThat(node("off").config().node().gossipPort()).as("YAML reads a bare off as false").isEqualTo(NodeSettings.GOSSIP_OFF);
        assertThat(node("\"off\"").config().node().gossipPort()).isEqualTo(NodeSettings.GOSSIP_OFF);
        assertThat(node("18790").config().node().gossipPort()).isEqualTo(18790);
        NetFolder.Loaded bad = node("near");
        assertThat(bad.refused("node.yaml")).isTrue();
        assertThat(bad.problems().get("node.yaml")).anySatisfy(p -> {
            assertThat(p.field()).isEqualTo("gossip-port");
            assertThat(p.message()).contains("is not auto, off or a port number");
        });
        assertThat(node("70000").refused("node.yaml")).isTrue();
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
    void aMembersGossipPortTravelsAndAnOlderNodesEntryHasNone() throws Exception {
        Member m = new Member("n-1", "http://192.0.2.10:18783", 1, 5, MemberState.ALIVE, "", "2.6.7", Member.PROTOCOL, List.of(), 0, false, 41234);
        assertThat(m.gossipUrl()).isEqualTo("http://192.0.2.10:41234");
        Member back = JSON.readValue(JSON.writeValueAsBytes(m), Member.class);
        assertThat(back).isEqualTo(m);
        assertThat(back.withHeartbeat(6).gossipPort()).isEqualTo(41234);
        // an entry from a node of before 2.6.7: no gossipPort, gossip goes to its server port
        String old = "{\"nodeId\":\"n-2\",\"url\":\"https://192.0.2.11:8480\",\"incarnation\":1,\"heartbeat\":3,\"state\":\"alive\",\"digestHash\":\"\","
                + "\"version\":\"2.6.6\",\"protocol\":1,\"tags\":[],\"since\":0,\"drained\":false}";
        Member older = JSON.readValue(old, Member.class);
        assertThat(older.gossipPort()).isZero();
        assertThat(older.gossipUrl()).isEqualTo("https://192.0.2.11:8480");
        assertThat(Member.gossipUrl("https://[2001:db8::1]:8480", 9000)).isEqualTo("https://[2001:db8::1]:9000");
    }
}
