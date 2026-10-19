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
package com.ash.drishti.server.net;

import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.member.MemberState;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drishti*net on Admin → Health and in the metrics (DRISHTI_NET.md section 13): the members in each state, the requests forwarded to
 * peers and those that failed over the last ten minutes; the meters {@code drishti.net.members} (state) and
 * {@code drishti.net.gossip.rounds} beside the forwarding meters of {@link ForwardLog}.
 */
public final class NetHealth {

    private final NetService net;
    private final ForwardLog log;

    public NetHealth(NetService net, ForwardLog log, MeterRegistry meters) {
        this.net = net;
        this.log = log;
        if (meters != null) {
            for (MemberState s : MemberState.values()) {
                Gauge.builder("drishti.net.members", this, h -> h.count(s)).description("Drishti*net members in a state, this node included")
                        .tag("state", s.wire()).register(meters);
            }
            FunctionCounter.builder("drishti.net.gossip.rounds", net, n -> n.node().map(x -> x.gossiper().rounds()).orElse(0L))
                    .description("Gossip rounds this node has run").register(meters);
        }
    }

    private double count(MemberState s) {
        return net.node().map(n -> n.table().entries().stream().filter(m -> m.state() == s).count()).orElse(0L);
    }

    /** The section of Admin → Health. */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", net.config().enabled());
        out.put("running", net.running());
        out.put("nodeId", net.nodeId());
        Map<String, Long> states = new LinkedHashMap<>();
        for (MemberState s : MemberState.values()) {
            states.put(s.wire(), (long) count(s));
        }
        out.put("members", states);
        out.put("drained", net.node().map(n -> n.table().self()).map(Member::drained).orElse(false));
        out.put("last10Minutes", log.totals(10));
        return out;
    }
}
