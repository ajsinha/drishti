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
package com.ash.drishti.banking.var;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.server.engines.BasicEngine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The market-risk cube (MR_CUBE_GUIDE.md) as a compute engine, {@code mr-cube} (ADMIN_GUIDE.md, {@code config/engines/mr-cube.yaml}):
 * not a second aggregation engine but the pivot engine's VaR source with the MR lens. It needs the VaR engine and the pivot engine and uses
 * the sensitivities and FRTB-SA engines softly (with them off, DV01, CS01, SBM, the ladder and the heat map are not offered). Off, its API
 * ({@code /api/v1/pivot/mr}) answers {@code 404 DRS-1020}, the VaR source offers VaR, ES, PV and trades by the positions' columns only, the
 * {@code MCUBE} word, its menu entry and Help pages are gone, and its kept dimension codes are released.
 */
@Configuration
@EnableConfigurationProperties(MrCubeProperties.class)
public class MrCubeConfiguration {

    /** The VaR provider the pivot engine was built with (PivotConfiguration), whose MR lens these beans serve. */
    static VarPivotProvider provider(PivotCore core) {
        return core.providers().stream().filter(VarPivotProvider.class::isInstance).map(VarPivotProvider.class::cast).findFirst()
                .orElseThrow(() -> new IllegalStateException("the pivot engine has no VaR provider: the MR cube needs the VaR engine"));
    }

    /**
     * The VaR source of the pivot engine, with the MR cube's lens (DOMAIN_MODULES.md J1: the pivot engine asks every
     * {@link com.ash.drishti.server.pivot.PivotSourceProvider}; it names no module's source).
     */
    @Bean
    public com.ash.drishti.server.pivot.PivotSourceProvider varPivotSource(org.springframework.beans.factory.ObjectProvider<VarService> var,
            org.springframework.beans.factory.ObjectProvider<MrCubeProperties> mr,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.banking.frtb.FrtbService> frtb, org.springframework.core.env.Environment env,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines,
            org.springframework.beans.factory.ObjectProvider<VarParametricProperties> parametric) {
        return () -> {
            VarService v = var.getIfAvailable();
            if (v == null) {
                return null;
            }
            boolean mrSwitch = env.getProperty("drishti.engines.mr-cube.enabled", Boolean.class, false);
            return new VarPivotProvider(v, () -> com.ash.drishti.server.pivot.PivotConfiguration.running(engines, "var", true),
                    () -> com.ash.drishti.server.pivot.PivotConfiguration.running(engines, "mr-cube", mrSwitch), mr.getIfAvailable(MrCubeProperties::defaults),
                    () -> com.ash.drishti.server.pivot.PivotConfiguration.running(engines, "frtb-sa", true) ? MrCubeExtras.running(frtb.getIfAvailable()) : null)
                    .parametric(parametric.getIfAvailable());
        };
    }

    @Bean
    public MrCubeExtras mrCubeExtras(VarService svc, PivotCore core, com.ash.drishti.banking.RiskLimits limits) {
        return new MrCubeExtras(svc, core, provider(core), limits);
    }

    @Bean
    public BasicEngine mrCubeComputeEngine(MrCubeExtras extras) {
        VarPivotProvider p = extras.provider();
        return new BasicEngine("mr-cube").onStop(p::release).memory(p::bytes);
    }
}
