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
package com.ash.drishti.server.bi.runs;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Reads {@code config/runs/runs.yaml} (the file named by {@code drishti.bi.runs.file}) once at start (docs/architecture/BI_RUNS.md section 15). */
@Configuration
public class RunsConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(RunsConfiguration.class);

    @Bean
    RunsProperties runsProperties(Environment env) {
        Path file = Path.of(env.getProperty("drishti.bi.runs.file", "./config/runs/runs.yaml"));
        Path data = Path.of(env.getProperty("drishti.data.dir", "./data"));
        RunsProperties p = RunsProperties.load(file, data, env::resolvePlaceholders);
        LOG.info("BI runs: {} (settings {}, results in {})", p.enabled() ? "on" : "off", p.origin(), p.store().dir());
        return p;
    }
}
