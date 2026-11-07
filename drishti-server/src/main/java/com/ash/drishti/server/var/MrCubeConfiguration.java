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

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.server.engines.BasicEngine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The market-risk cube (MR_CUBE_GUIDE.md) as a compute engine, {@code mr-cube} (COMPUTE_ENGINES.md, {@code config/engines/mr-cube.yaml}):
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

    @Bean
    public MrCubeExtras mrCubeExtras(VarService svc, PivotCore core) {
        return new MrCubeExtras(svc, core, provider(core));
    }

    @Bean
    public BasicEngine mrCubeComputeEngine(MrCubeExtras extras) {
        VarPivotProvider p = extras.provider();
        return new BasicEngine("mr-cube").onStop(p::release).memory(p::bytes);
    }
}
