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
package com.ash.drishti.server.saccr;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.frtb.FrtbData;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.server.var.VarService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The beans of SA-CCR ({@link SaccrService}) and its settings, {@code drishti.ccr.saccr}; its switch is {@code drishti.engines.sa-ccr.enabled}. */
@Configuration
@EnableConfigurationProperties(SaccrProperties.class)
public class SaccrConfiguration {

    @Bean
    SaccrService saccrService(SaccrProperties props, SourceRouter router, ObjectProvider<VarService> var, ObjectProvider<LoadService> loads) {
        FrtbData.Reader reader = (kind, asOf, sink) -> {
            try {
                return router.documents(kind, asOf, (EntityDocument d) -> sink.accept(d.data()));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(SaccrConfiguration.class).debug("{} not read: {}", kind, e.getMessage());
                return false;
            }
        };
        SaccrData data = new SaccrData(reader, props);
        VarService v = var.getIfAvailable();
        SaccrService svc = new SaccrService(data, v == null ? null : v::scopeMembers, props);
        LoadService l = loads.getIfAvailable();
        if (l != null) {
            l.onReady(r -> {
                if (data.kinds().contains(r.kind())) {
                    svc.clear();                                      // a new or restated batch of an input: read the date again
                }
            });
        }
        return svc;
    }

    /** SA-CCR as a compute engine (docs/architecture/COMPUTE_ENGINES.md): off, its API answers 404 DRS-1020 and what it read is released. */
    @Bean
    public com.ash.drishti.server.engines.BasicEngine saccrComputeEngine(SaccrService svc) {
        com.ash.drishti.server.engines.BasicEngine eng = new com.ash.drishti.server.engines.BasicEngine("sa-ccr").onStop(svc::clear);
        svc.running(() -> eng.on(() -> true));
        return eng;
    }
}
