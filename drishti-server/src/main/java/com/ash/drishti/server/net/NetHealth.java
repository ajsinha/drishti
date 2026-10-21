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
 * {@code drishti.net.gossip.rounds} beside the forwarding meters of {@link ForwardLog}; and the warnings: an enabled administrator kept
 * because peers' changes made at the same moment would have left none ({@link IdentitySync#warnings()}).
 */
public final class NetHealth {

    private final NetService net;
    private final ForwardLog log;
    private final java.util.function.Supplier<IdentitySync> identity;

    public NetHealth(NetService net, ForwardLog log, MeterRegistry meters) {
        this(net, log, meters, () -> null);
    }

    /** @param identity the identity sync, whose last-administrator repairs are shown as warnings (null when there is none) */
    public NetHealth(NetService net, ForwardLog log, MeterRegistry meters, java.util.function.Supplier<IdentitySync> identity) {
        this.net = net;
        this.log = log;
        this.identity = identity;
        if (meters != null) {
            for (MemberState s : MemberState.values()) {
                Gauge.builder("drishti.net.members", this, h -> h.count(s)).description("Drishti*net members in a state, this node included")
                        .tag("state", s.wire()).register(meters);
            }
            FunctionCounter.builder("drishti.net.gossip.rounds", net, n -> n.node().map(x -> x.gossiper().rounds()).orElse(0L))
                    .description("Gossip rounds this node has run").register(meters);
        }
    }

    private volatile java.util.function.BooleanSupplier joining = () -> false;

    /** Tells whether the node is still joining (it has not had its peers' first digests; SOAK3-01), for the summary's {@code state}. */
    public NetHealth joining(java.util.function.BooleanSupplier j) {
        this.joining = j == null ? () -> false : j;
        return this;
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
        // joining: a member that does not know yet what its peers hold (a read nothing here holds waits, then says DRS-5052, never 404)
        out.put("state", !net.config().enabled() ? "off" : joining.getAsBoolean() ? "joining" : net.running() ? "member" : "not joined");
        Map<String, Long> states = new LinkedHashMap<>();
        for (MemberState s : MemberState.values()) {
            states.put(s.wire(), (long) count(s));
        }
        out.put("members", states);
        out.put("drained", net.node().map(n -> n.table().self()).map(Member::drained).orElse(false));
        out.put("last10Minutes", log.totals(10));
        IdentitySync id = identity.get();
        out.put("warnings", id == null ? java.util.List.of() : id.warnings());
        return out;
    }
}
