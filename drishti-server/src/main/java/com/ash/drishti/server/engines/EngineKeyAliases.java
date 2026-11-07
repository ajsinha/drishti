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
package com.ash.drishti.server.engines;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Settings that moved to another engine's file under a new name (docs/architecture/ARCHITECTURE.md section 25.4): the code still reads the
 * old name, and this lowest layer gives the old name the new one's value. A site that sets the old name anywhere (application.local.yaml,
 * the environment) still wins, so nothing an install set before changes: {@code drishti.frtb.sensitivity-kind}, {@code factor-kind} and
 * {@code cache-minutes} read {@code drishti.sensitivities.*} (config/engines/sensitivities.yaml).
 */
public final class EngineKeyAliases implements EnvironmentPostProcessor, Ordered {

    public static final String SOURCE = "drishti-engine-key-aliases";

    /** Old name, the new name it reads, and the product default when neither is set. */
    static final String[][] MOVED = {
        {"drishti.frtb.sensitivity-kind", "drishti.sensitivities.sensitivity-kind", "position-sensitivity"},
        {"drishti.frtb.factor-kind", "drishti.sensitivities.factor-kind", "risk-factor"},
        {"drishti.frtb.cache-minutes", "drishti.sensitivities.cache-minutes", "10"},
    };

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String[] k : MOVED) {
            m.put(k[0], "${" + k[1] + ":" + k[2] + "}");
        }
        env.getPropertySources().addLast(new MapPropertySource(SOURCE, m));
    }
}
