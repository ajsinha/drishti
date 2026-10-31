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
 * What a measure brings to the sharding machinery ({@link com.ash.drishti.server.shard.ShardedMeasure}) and the members' view of it:
 * who owns which shard, stability and drain ({@link com.ash.drishti.server.shard.ShardMembership}) and readiness in the capability digest
 * ({@link com.ash.drishti.server.shard.ShardPeers}).
 */
package com.ash.drishti.server.shard;
