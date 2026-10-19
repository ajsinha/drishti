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
package com.ash.drishti.net.config;

import java.time.Duration;

/**
 * The gossip protocol's timings ({@code config/net/gossip.yaml}); applied on the next round.
 *
 * @param interval how often a round runs (1 s)
 * @param fanout how many members each round exchanges tables with (3)
 * @param suspectAfter a member whose heartbeat has not risen this long is probed (5 s)
 * @param indirectProbes how many other members are asked to probe it when it does not answer directly (2)
 * @param deadAfter how long a member stays suspect before it is dead (30 s)
 * @param forgetAfter how long a dead or left member is remembered, so a stale table cannot bring it back (24 h)
 * @param persistEvery how often the member table is saved to {@code <data dir>/net/peers.json} (10 min)
 */
public record GossipSettings(Duration interval, int fanout, Duration suspectAfter, int indirectProbes, Duration deadAfter,
        Duration forgetAfter, Duration persistEvery) {

    public static final GossipSettings DEFAULTS = new GossipSettings(Duration.ofSeconds(1), 3, Duration.ofSeconds(5), 2,
            Duration.ofSeconds(30), Duration.ofHours(24), Duration.ofMinutes(10));
}
