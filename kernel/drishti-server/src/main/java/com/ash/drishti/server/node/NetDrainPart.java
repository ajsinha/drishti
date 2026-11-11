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
package com.ash.drishti.server.node;

import com.ash.drishti.net.member.Member;
import com.ash.drishti.server.net.NetService;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Drishti*net's forwarding as a part of the drain (CD2): drained, the member is not routable, so peers stop forwarding work to it (and it
 * leaves the shard views); safe to stop once the flag has had {@code drishti.node.drain.settle-seconds} to reach every peer. Off (no
 * cluster here): nothing to hand over.
 */
final class NetDrainPart implements Drainable {

    private final NetService net;
    private final IntSupplier settleSeconds;
    private final LongSupplier clock;
    private volatile long drainedAt;

    NetDrainPart(NetService net, IntSupplier settleSeconds, LongSupplier clock) {
        this.net = net;
        this.settleSeconds = settleSeconds;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "net";
    }

    @Override
    public void drain(boolean on) {
        if (net.running()) {
            net.drain(on);
        }
        drainedAt = on ? clock.getAsLong() : 0L;
    }

    @Override
    public boolean draining() {
        return net.running() && net.node().map(n -> n.table().self()).map(Member::drained).orElse(false);
    }

    @Override
    public boolean safeToStop() {
        if (!net.running()) {
            return true;
        }
        if (!draining()) {
            return false;
        }
        long at = drainedAt;
        if (at == 0L) {                                                  // drained from Admin -> Net: counted from when first seen here
            at = clock.getAsLong();
            drainedAt = at;
        }
        return clock.getAsLong() - at >= settleSeconds.getAsInt() * 1000L;
    }

    @Override
    public String detail() {
        if (!net.running()) {
            return "the cluster is off on this server: no forwarded work";
        }
        if (!draining()) {
            return "takes forwarded work";
        }
        return safeToStop() ? "drained: peers forward nothing here" : "drained: waiting " + settleSeconds.getAsInt() + " s for every peer to hear it";
    }
}
