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
package com.ash.drishti.server.cube;

import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.admission.AdmissionGate;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The shared cube core's beans (MR_CUBE_GUIDE.md section 9): the engine over every cube source that is switched on ({@code drishti.cube.mr.enabled}
 * builds the market-risk cube's source in the VaR package), saved views and the settings {@code drishti.cube}. With every cube off the engine
 * has no source: its API answers {@code 404 DRS-5603} in words and holds nothing.
 */
@Configuration
@EnableConfigurationProperties(CubeProperties.class)
public class CubeConfiguration {

    /** The cell cache's name in Admin, Caches and the one memory budget. */
    public static final String CACHE = "cube.cells";

    @Bean
    public CubeEngine cubeEngine(CubeProperties props, ObjectProvider<CubeSource<?>> sources, ObjectProvider<AdmissionGate> gate) {
        List<CubeSource<?>> on = sources.orderedStream().toList();
        CubeEngine e = new CubeEngine(props, on);
        e.admission(gate.getIfAvailable());
        return e;
    }

    @Bean
    public CubeViews cubeViews(ContentStore store) {
        return new CubeViews(store);
    }
}
