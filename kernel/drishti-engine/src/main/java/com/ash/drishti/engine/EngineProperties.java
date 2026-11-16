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
package com.ash.drishti.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.engine.*}: caches and parallelism of the view pipeline.
 *
 * @param layoutCacheSize effective layouts kept, keyed by (Sutra version, kind, shape fingerprint)
 * @param fingerprintCacheSize fingerprints kept, keyed by (entity, generation)
 * @param bindParallelism threads binding panels; 0 means one per core
 */
@ConfigurationProperties("drishti.engine")
public record EngineProperties(Long layoutCacheSize, Long fingerprintCacheSize, Integer bindParallelism) {

    public EngineProperties {
        layoutCacheSize = layoutCacheSize == null ? 10_000L : layoutCacheSize;
        fingerprintCacheSize = fingerprintCacheSize == null ? 100_000L : fingerprintCacheSize;
        bindParallelism = bindParallelism == null || bindParallelism <= 0 ? Runtime.getRuntime().availableProcessors() : bindParallelism;
    }
}
