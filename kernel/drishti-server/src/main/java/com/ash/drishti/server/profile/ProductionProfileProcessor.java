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
package com.ash.drishti.server.profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Applies the profile before any bean is built (docs/admin/PRODUCTION_STANDARD.md): sets the two default switches that
 * {@code application.yaml} reads for the settings whose default differs ({@code drishti.defaults.secure}: sign-in on;
 * {@code drishti.defaults.open}: the dev admin, the demo source and the API description on), then refuses the start while
 * {@link ProductionProfile#refusals} is not empty. An explicit setting always beats a default, so an unsafe one is refused, never overridden.
 */
public final class ProductionProfileProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE = "drishti-profile-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        boolean production = ProductionProfile.production(env);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("drishti.defaults.secure", production);
        defaults.put("drishti.defaults.open", !production);
        env.getPropertySources().addLast(new MapPropertySource(SOURCE, defaults));
        List<String> refusals = ProductionProfile.refusals(env);
        if (!refusals.isEmpty()) {
            throw new IllegalStateException(ProductionProfile.message(refusals));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
