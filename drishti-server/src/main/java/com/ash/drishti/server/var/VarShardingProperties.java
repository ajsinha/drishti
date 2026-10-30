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

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The server's settings of VaR sharding across Drishti*net (VAR_SHARDING.md section 9). What is sharded comes from the market-risk pack's
 * {@code sharding:} block ({@code enabled}, {@code key}, {@code kinds}, {@code target-shard-mb}, {@code replicas}); the same keys here, when
 * set, win over it (a test, or a site that does not edit its pack). No block and no {@code enabled} here: sharding is off and every date is
 * loaded whole, exactly as before 2.9.6. The rest are this server's own.
 *
 * @param enabled sharding on (true) or off (false); unset: the pack's block decides (off without one)
 * @param key the shard key: {@code [LEGAL_ENTITY, DESK]} (default) or {@code [LEGAL_ENTITY]}, the hot file's own order
 * @param kinds the kinds sharded: the trades' scenario P&amp;L ({@code pnl-vector}, {@code scenario-pv}); every other kind is read whole
 * @param targetShardMb a shard's target size, its float32 rows in MB (2048); a desk larger than it is one shard of its own
 * @param replicas the owners of each shard (2)
 * @param stableSeconds how long the members must stay the same before shards move to new owners (30)
 * @param graceSeconds how long a new owner must have been ready before the old one releases the shard (30)
 * @param coordinatorOnly this member owns no shard: it puts dates together and coordinates queries only
 * @param zoneTag the member tag prefix naming its zone ({@code az}: a tag {@code az:us-east-1a}); owners spread over zones; blank: none
 * @param pins a shard pinned to a member, first owner whatever the hashing (tests): shard range or id to node id, the same on every member
 * @param drain start drained: this member hands its shards over and takes no new ones (also {@code POST /api/v1/var/shards/drain})
 * @param callTimeoutSeconds how long a partial sum may take on an owner before its other replica is asked (30)
 * @param skeletonTimeoutSeconds how long an owner may take to build and send a shard's skeleton (600)
 * @param rebalanceSeconds how often this member looks at the members and its shards (2)
 * @param releaseAfterSeconds how long a shard book is kept after no member has had its date open and nothing read it (900), unless the
 *     date is among the lake's newest {@code hot-dates}; 2.9.7 (RESOURCES_AND_SCALE.md section 5.3)
 */
@ConfigurationProperties("drishti.var.sharding")
public record VarShardingProperties(Boolean enabled, List<String> key, List<String> kinds, Double targetShardMb, Integer replicas,
        Integer stableSeconds, Integer graceSeconds, Boolean coordinatorOnly, String zoneTag, Map<String, String> pins, Boolean drain,
        Integer callTimeoutSeconds, Integer skeletonTimeoutSeconds, Integer rebalanceSeconds, Integer releaseAfterSeconds) {


    public VarShardingProperties {
        key = key == null || key.isEmpty() ? null : List.copyOf(key);
        kinds = kinds == null || kinds.isEmpty() ? null : List.copyOf(kinds);
        stableSeconds = stableSeconds == null || stableSeconds < 0 ? 30 : stableSeconds;
        graceSeconds = graceSeconds == null || graceSeconds < 0 ? 30 : graceSeconds;
        coordinatorOnly = coordinatorOnly != null && coordinatorOnly;
        zoneTag = zoneTag == null ? "" : zoneTag.strip();
        pins = pins == null ? Map.of() : Map.copyOf(pins);
        drain = drain != null && drain;
        callTimeoutSeconds = callTimeoutSeconds == null || callTimeoutSeconds < 1 ? 30 : callTimeoutSeconds;
        skeletonTimeoutSeconds = skeletonTimeoutSeconds == null || skeletonTimeoutSeconds < 1 ? 600 : skeletonTimeoutSeconds;
        rebalanceSeconds = rebalanceSeconds == null || rebalanceSeconds < 1 ? 2 : rebalanceSeconds;
        releaseAfterSeconds = releaseAfterSeconds == null || releaseAfterSeconds < 0 ? 900 : releaseAfterSeconds;
    }

    /** No settings: off unless a pack's block switches it on. */
    static VarShardingProperties none() {
        return new VarShardingProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
