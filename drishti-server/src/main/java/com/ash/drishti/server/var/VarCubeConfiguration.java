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

import com.ash.drishti.server.cube.CubeProperties;
import com.ash.drishti.server.frtb.FrtbService;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The market-risk cube (engine {@code mr-cube}, COMPUTE_ENGINES.md): built only when {@code drishti.cube.mr.enabled} is on (the default).
 * It needs the VaR engine (its scopes, books and sums) and uses the FRTB-SA engine's sensitivities softly: with it off, DV01, CS01, SBM, the
 * ladder and the heat map are not offered.
 */
@Configuration
@ConditionalOnProperty(name = "drishti.cube.mr.enabled", havingValue = "true", matchIfMissing = true)
public class VarCubeConfiguration {

    /** The FRTB-SA engine when it is on and its parameter files were read, else null. */
    static Supplier<FrtbService> frtb(ObjectProvider<FrtbService> f) {
        return () -> {
            FrtbService s = f.getIfAvailable();
            return s != null && s.props().enabled() && s.problem() == null ? s : null;
        };
    }

    @Bean
    public VarCubeSource varCubeSource(VarService svc, CubeProperties props, ObjectProvider<FrtbService> frtb) {
        return new VarCubeSource(svc, props.mr(), frtb(frtb));
    }

    @Bean
    public VarCubeExtras varCubeExtras(VarService svc, VarExplain explain, CubeProperties props, ObjectProvider<FrtbService> frtb) {
        return new VarCubeExtras(svc, explain, props.mr(), frtb(frtb));
    }
}
