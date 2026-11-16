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
package com.ash.drishti.banking.simm;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.banking.frtb.FrtbData;
import com.ash.drishti.banking.frtb.FrtbService;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.banking.var.VarService;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of ISDA SIMM ({@link SimmService}) and their settings, {@code drishti.simm}; the sensitivities are FRTB-SA's ({@link FrtbData}). */
@Configuration
@EnableConfigurationProperties(SimmProperties.class)
public class SimmConfiguration {

    @Bean
    SimmService simmService(SimmProperties props, SourceRouter router, FrtbService frtb, ObjectProvider<VarService> var, ObjectProvider<LoadService> loads) {
        FrtbData.Reader reader = (kind, asOf, sink) -> {
            try {
                return router.documents(kind, asOf, (EntityDocument d) -> sink.accept(d.data()));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(SimmConfiguration.class).debug("{} not read: {}", kind, e.getMessage());
                return false;
            }
        };
        SimmData data = new SimmData(reader, frtb.data(), props);
        VarService v = var.getIfAvailable();
        SimmService svc = new SimmService(data, v == null ? null : v::scopeMembers, props, Path.of(props.parametersDir()));
        LoadService l = loads.getIfAvailable();
        if (l != null) {
            l.onReady(r -> {
                if (data.kinds().contains(r.kind())) {
                    svc.clear();                                      // a new or restated batch, or new netting sets, read again
                }
            });
        }
        return svc;
    }

    /** ISDA SIMM as a compute engine (docs/guides/ADMIN_GUIDE.md): off, its API answers 404 DRS-1020 and what it read is released. */
    @Bean
    public com.ash.drishti.server.engines.BasicEngine simmComputeEngine(SimmService svc) {
        com.ash.drishti.server.engines.BasicEngine eng = new com.ash.drishti.server.engines.BasicEngine("simm").onStop(svc::clear);
        svc.running(() -> eng.on(svc::switchedOn));
        return eng;
    }
}
