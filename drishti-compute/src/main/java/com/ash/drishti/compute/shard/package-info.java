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
/**
 * The measure-agnostic sharding machinery (docs/guides/CLUSTER_GUIDE.md, VaR and exposure sharding): the shard map and its
 * cut by key ranges ({@link com.ash.drishti.compute.shard.ShardMap}), rendezvous ownership with replicas
 * ({@link com.ash.drishti.compute.shard.ShardOwners}), the handover rule ({@link com.ash.drishti.compute.shard.ShardHandover}) and the
 * coordinator's scatter-gather with its exactly-once check and the measure's own combine ({@link com.ash.drishti.kernel.api.shard.ShardGather}).
 * A measure plugs in through {@code com.ash.drishti.kernel.api.shard.ShardedMeasure}.
 */
package com.ash.drishti.compute.shard;
