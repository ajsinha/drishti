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
package com.ash.drishti.server.dataload;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.engines.EngineGateFilter;
import com.ash.drishti.server.security.Principal;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** An upload reaching a node that does not run the inbox goes to the one that does, in parts that each fit a signed peer call. */
class ReadyRelayTest {

    @Test
    void aFileIsPassedOnInPartsCarryingTheSameId() throws Exception {
        List<String> calls = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        EngineGateFilter.Peers peers = new EngineGateFilter.Peers() {
            @Override
            public Optional<String> runs(String engine) {
                return Optional.of("node-b");
            }

            @Override
            public boolean fromPeer(jakarta.servlet.http.HttpServletRequest request) {
                return false;
            }

            @Override
            public PeerHttp.Answer call(String node, String method, String path, byte[] body, Principal p) {
                calls.add(node + " " + path);
                sizes.add(body.length);
                return new PeerHttp.Answer(200, Map.of(), "{\"id\":\"u1\"}".getBytes(StandardCharsets.UTF_8));
            }
        };
        ReadyController c = new ReadyController(null, null, null, null, peers);
        ResponseEntity<?> r = c.relay("node-b", "a b.jsonl", new ByteArrayInputStream(new byte[(8 << 20) * 2 + 5]), new Principal("alice", List.of("admin")));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(sizes).containsExactly(8 << 20, 8 << 20, 5);
        assertThat(calls.get(0)).contains("file=a+b.jsonl", "last=false").doesNotContain("id=");
        assertThat(calls.get(1)).contains("last=false", "id=u1");
        assertThat(calls.get(2)).contains("last=true", "id=u1");
    }
}
