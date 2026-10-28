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

import com.ash.drishti.compute.shard.ShardGather;
import com.ash.drishti.server.shard.ShardedMeasure;
import java.util.List;

/**
 * VaR as a sharded measure (VAR_SHARDING.md): shards along legal entity, then desk (the hot file's order, a desk never split); a row is a
 * position's float32 scenario P&amp;L ({@code 4 × scenarios} bytes); a shard's partial answer is the float64 sum of the rows asked, one value
 * per scenario, and partials add element by element.
 */
final class VarMeasure implements ShardedMeasure<double[]> {

    static final VarMeasure INSTANCE = new VarMeasure();

    private VarMeasure() {}

    @Override
    public String name() {
        return "VaR";
    }

    @Override
    public String service() {
        return "var-shards";
    }

    @Override
    public List<String> keyOrder() {
        return List.of("LEGAL_ENTITY", "DESK");
    }

    @Override
    public long bytesPerRow(int width) {
        return (long) Math.max(1, width) * Float.BYTES;
    }

    @Override
    public ShardGather.Combine<double[]> combine(int width) {
        return ShardGather.sum(width);
    }
}
