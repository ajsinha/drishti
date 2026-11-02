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
package com.ash.drishti.server.var;

import com.ash.drishti.server.node.Drainable;
import com.ash.drishti.server.node.NewestDate;
import com.ash.drishti.server.node.ReadinessGate;
import java.util.List;

/**
 * The VaR engine as parts of this node (CD1 and CD2): its newest date's readiness gate ({@link NewestDate}), its shards' readiness gate
 * ({@link VarShards#unready}) and its shards as a {@link Drainable} part of the drain before a stop. Off when the engine or its sharding is.
 */
public final class VarNodeParts {

    private VarNodeParts() {
    }

    /** At most this many shards are named in readiness (the rest are counted). */
    static final int NAMED = 8;

    /** The newest date's gate. */
    static ReadinessGate newestDate(VarService svc) {
        return new NewestDate("var", NewestDate.engine(svc::running, () -> svc.loader().latest(), svc::open, svc::refusedNow,
                d -> svc.loader().version(d), d -> svc.date(d)));
    }

    /** The owned shards' gate. */
    static ReadinessGate shards(VarService svc) {
        return new ReadinessGate() {
            public String name() {
                return "var-shards";
            }

            public String kind() {
                return "shards";
            }

            public List<String> waitingFor() {
                VarShards s = svc.shards();
                return s == null || !svc.props().enabled() || !s.on() ? List.of() : s.unready(NAMED);
            }
        };
    }

    /** The shards as a part of the drain: safe once this member holds none. */
    static Drainable drain(VarService svc) {
        return new Drainable() {
            public String name() {
                return "var-shards";
            }

            private boolean on() {
                VarShards s = svc.shards();
                return s != null && svc.props().enabled() && s.on();
            }

            public void drain(boolean on) {
                if (on()) {
                    svc.shards().drain(on);
                }
            }

            public boolean draining() {
                return on() && svc.shards().draining();
            }

            public boolean safeToStop() {
                return !on() || svc.shards().draining() && svc.shards().empty();
            }

            public String detail() {
                if (!on()) {
                    return "VaR sharding is off on this server: no shards to hand over";
                }
                VarShards s = svc.shards();
                if (!s.draining()) {
                    return "owns shards";
                }
                int held = (int) s.books().keys().stream().filter(k -> k.ccy().equals(VarDate.USD)).count();
                return held == 0 ? "drained: holds no shard" : "drained: " + held + " shard book(s) still held until their next owners are ready";
            }
        };
    }
}
