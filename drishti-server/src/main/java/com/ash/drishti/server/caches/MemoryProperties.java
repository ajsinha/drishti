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
package com.ash.drishti.server.caches;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one cache budget (RESOURCES_AND_SCALE.md section 5.2, D3, 2.9.7; PO-2), {@code drishti.memory.*}.
 *
 * @param cacheShare the share of {@code -Xmx} every cache together may take (0.5)
 * @param weights each pool's weight ({@code connector.idmaps} 20, {@code connector.columns} 20, {@code connector.documents} 6,
 *     {@code connector.parts} 4, {@code engine.answers} 20, {@code engine.layouts} 10, {@code small} 20)
 * @param squeezeAt the heap after a collection, as a share of {@code -Xmx}, past which the lower-priority pools are halved for a minute
 *     (0.85)
 */
@ConfigurationProperties("drishti.memory")
public record MemoryProperties(Double cacheShare, Map<String, Integer> weights, Double squeezeAt) {

    public MemoryProperties {
        cacheShare = cacheShare == null || cacheShare <= 0 || cacheShare > 0.9 ? 0.5 : cacheShare;
        weights = weights == null ? Map.of() : Map.copyOf(weights);
        squeezeAt = squeezeAt == null || squeezeAt <= 0 || squeezeAt >= 1 ? 0.85 : squeezeAt;
    }
}
