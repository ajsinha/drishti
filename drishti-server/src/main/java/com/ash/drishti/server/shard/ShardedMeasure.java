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
package com.ash.drishti.server.shard;

import com.ash.drishti.compute.shard.ShardGather;
import java.util.List;

/**
 * What one measure brings to the cluster's sharding machinery (VAR_SHARDING.md, Decisions: the extension points). The machinery is the
 * measure's own nowhere: the shard map and its cut by key ranges ({@link com.ash.drishti.compute.shard.ShardMap}), ownership by rendezvous
 * hashing with replicas ({@link ShardMembership}), readiness announced in the capability digest, handover and drain ({@link ShardPeers},
 * {@link com.ash.drishti.compute.shard.ShardHandover}), and the coordinator's scatter-gather with its exactly-once check
 * ({@link ShardGather}). A measure says what its shard key may be, how large a row is, what a partial answer is and how two combine. VaR is
 * the first (its partial a {@code double[]} of scenario P&amp;L, added); a measure with another unit (exposure's netting sets) plugs in the
 * same way.
 *
 * @param <P> the measure's partial answer of one shard
 */
public interface ShardedMeasure<P> {

    /** The measure's name, in messages ({@code VaR}). */
    String name();

    /** The capability digest's service its members' shard state is published under ({@code var-shards}). */
    String service();

    /** The order the measure keeps its rows in: a shard key is a non-empty prefix of it, so a shard is a run of rows. */
    List<String> keyOrder();

    /** A row's size in a shard, for cutting near the target size ({@code width}: the row's width, VaR's scenarios). */
    long bytesPerRow(int width);

    /** How two partial answers combine ({@code width} as above). */
    ShardGather.Combine<P> combine(int width);
}
