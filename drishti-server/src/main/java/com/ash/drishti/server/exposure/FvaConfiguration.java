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
package com.ash.drishti.server.exposure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * FVA's beans (docs/guides/FVA_GUIDE.md): its settings ({@code drishti.ccr.fva}), the service over the exposure engine's answers, and the
 * compute engine {@code fva} with its own switch ({@code drishti.engines.fva.enabled}, config/engines/fva.yaml); it needs the exposure
 * engine on the same server (the engine catalogue's {@code needs}).
 */
@Configuration
@EnableConfigurationProperties(FvaProperties.class)
public class FvaConfiguration {

    @Bean
    FvaService fvaService(ExposureService exposure, FvaProperties props) {
        return new FvaService(exposure, props);
    }

    /** FVA as a compute engine (docs/architecture/COMPUTE_ENGINES.md): off, its API answers 404 DRS-1020 and the curves it read are released. */
    @Bean
    public com.ash.drishti.server.engines.BasicEngine fvaComputeEngine(FvaService svc, Environment env) {
        com.ash.drishti.server.engines.BasicEngine eng = new com.ash.drishti.server.engines.BasicEngine("fva").onStop(svc::clear);
        svc.running(() -> eng.on(() -> env.getProperty("drishti.engines.fva.enabled", Boolean.class, false)));
        return eng;
    }
}
