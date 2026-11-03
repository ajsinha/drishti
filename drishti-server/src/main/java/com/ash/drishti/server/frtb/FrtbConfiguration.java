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
package com.ash.drishti.server.frtb;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.server.var.VarService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of position sensitivities and FRTB-SA ({@link FrtbService}) and their settings, {@code drishti.frtb}. */
@Configuration
@EnableConfigurationProperties(FrtbProperties.class)
public class FrtbConfiguration {

    @Bean
    FrtbService frtbService(FrtbProperties props, SourceRouter router, ObjectProvider<VarService> var, ObjectProvider<LoadService> loads,
            ObjectProvider<com.ash.drishti.packs.PackRegistry> packs) {
        FrtbData data = new FrtbData((kind, asOf, sink) -> {
            try {
                return router.documents(kind, asOf, (EntityDocument d) -> sink.accept(d.data()));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(FrtbConfiguration.class).debug("{} not read: {}", kind, e.getMessage());
                return false;
            }
        }, props);
        VarService v = var.getIfAvailable();
        FrtbService svc = new FrtbService(data, v == null ? null : v::scopeMembers, props, parametersDir(props, packs.getIfAvailable()));
        LoadService l = loads.getIfAvailable();
        if (l != null) {
            l.onReady(r -> {
                if (data.kinds().contains(r.kind())) {
                    data.clear();                                     // a new or restated batch read again
                }
            });
        }
        return svc;
    }

    /** The parameter files' folder: the setting when it holds them, else the market-risk pack's {@code capital/} wherever the pack is. */
    static java.nio.file.Path parametersDir(FrtbProperties props, com.ash.drishti.packs.PackRegistry packs) {
        java.nio.file.Path set = java.nio.file.Path.of(props.parametersDir());
        if (java.nio.file.Files.isRegularFile(set.resolve("frtb-sa-sbm.yaml")) || packs == null) {
            return set;
        }
        return packs.packs().stream().filter(p -> p.name().equals("market-risk")).map(p -> p.dir().resolve("capital"))
                .filter(d -> java.nio.file.Files.isRegularFile(d.resolve("frtb-sa-sbm.yaml"))).findFirst().orElse(set);
    }

    /**
     * The position sensitivities as a compute engine (docs/architecture/COMPUTE_ENGINES.md): the SENS sums and the factor dictionary
     * (/api/v1/sensitivities, /api/v1/risk-factors), read by FRTB-SA and SIMM, which need it on the same node. Off: the dates read are released.
     */
    @Bean
    public com.ash.drishti.server.engines.BasicEngine sensitivitiesComputeEngine(FrtbService svc) {
        return new com.ash.drishti.server.engines.BasicEngine("sensitivities").onStop(() -> svc.data().clear());
    }

    /** FRTB-SA as a compute engine: off, its API answers 404 DRS-1020 and the sensitivities it read are released. Needs VaR and sensitivities. */
    @Bean
    public com.ash.drishti.server.engines.BasicEngine frtbComputeEngine(FrtbService svc,
            @org.springframework.beans.factory.annotation.Qualifier("sensitivitiesComputeEngine") com.ash.drishti.server.engines.BasicEngine sensitivities) {
        com.ash.drishti.server.engines.BasicEngine eng = new com.ash.drishti.server.engines.BasicEngine("frtb-sa").onStop(() -> svc.data().clear());
        svc.running(() -> eng.on(svc::switchedOn), () -> sensitivities.on(() -> true));
        return eng;
    }
}
