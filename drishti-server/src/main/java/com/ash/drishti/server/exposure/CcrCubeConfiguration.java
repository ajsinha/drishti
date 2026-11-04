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

import com.ash.drishti.server.engines.BasicEngine;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.server.saccr.SaccrService;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The CCR cube (EXPOSURE_GUIDE.md section 30) as a compute engine, {@code ccr-cube} (COMPUTE_ENGINES.md, {@code config/engines/ccr-cube.yaml}):
 * not a second aggregation engine but the pivot engine's source {@code ccr} over the exposure engine, with the CCR lens. It needs the exposure
 * engine and the pivot engine and uses SA-CCR and FVA softly: with either off, its measure ({@code ead()}, {@code fva()}) is not offered.
 * Off, its API ({@code /api/v1/pivot/ccr}) answers {@code 404 DRS-1020}, the source {@code ccr} is gone from the pivot engine, and the
 * {@code CCUBE} word, its menu entry and Help pages are gone.
 */
@Configuration
@EnableConfigurationProperties(CcrCubeProperties.class)
public class CcrCubeConfiguration {

    static boolean running(ObjectProvider<EngineRegistry> engines, String id) {
        EngineRegistry r = engines.getIfAvailable();
        return r == null || !r.known(id) || r.running(id);
    }

    /** The CCR cube's source of the pivot engine (PivotConfiguration adds it to the providers). */
    @Bean
    CcrPivotProvider ccrPivotProvider(ExposureService exposure, CcrCubeProperties props, ObjectProvider<SaccrService> saccr,
            ObjectProvider<FvaService> fva, ObjectProvider<EngineRegistry> engines) {
        BooleanSupplier saccrOn = () -> saccr.getIfAvailable() != null && running(engines, "sa-ccr");
        BooleanSupplier fvaOn = () -> fva.getIfAvailable() != null && running(engines, "fva");
        CcrPivotProvider.PerSet ead = CcrPivotProvider.cached(saccrOn, pd -> {
            Map<String, Double> m = new HashMap<>();
            saccr.getObject().run(pd.getKey(), "BANK", pd.getValue(), com.ash.drishti.compute.saccr.Saccr.Choices.REGULATORY, false).sets()
                    .forEach(s -> m.merge(s.id(), s.ead(), Double::sum));       // a set split by SPECIFIC_WWR rows: its parts added
            return m;
        });
        CcrPivotProvider.PerSet fvas = CcrPivotProvider.cached(fvaOn, pd -> {
            Map<String, Double> m = new HashMap<>();
            fva.getObject().answer(pd.getKey(), "BANK", pd.getValue(), FvaService.Choices.none(), null, null).sets()
                    .forEach(s -> m.put(s.id(), s.result().fva()));
            return m;
        });
        return new CcrPivotProvider(exposure, props, ead, fvas);
    }

    @Bean
    CcrCubeExtras ccrCubeExtras(CcrPivotProvider provider) {
        return new CcrCubeExtras(provider);
    }

    /** The CCR cube as a compute engine: off, the source ccr is gone and its API answers 404 DRS-1020. */
    @Bean
    public BasicEngine ccrCubeComputeEngine(CcrPivotProvider provider, Environment env) {
        BasicEngine eng = new BasicEngine("ccr-cube").onStop(provider::release);
        provider.running(() -> eng.on(() -> env.getProperty("drishti.engines.ccr-cube.enabled", Boolean.class, false)));
        return eng;
    }
}
