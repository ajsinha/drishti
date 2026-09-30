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
package com.ash.drishti.engine;

import com.ash.drishti.common.CommonConfiguration;
import com.ash.drishti.inference.InferenceConfiguration;
import com.ash.drishti.sutra.SutraConfiguration;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.PluginDiscovery;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Beans contributed by {@code drishti-engine}. */
@Configuration(proxyBeanMethods = false)
@Import({CommonConfiguration.class, SutraConfiguration.class, InferenceConfiguration.class})
@EnableConfigurationProperties(SourcesProperties.class)
public class EngineConfiguration {

    /** One virtual thread per task: fetches, link fan-out and searches block cheaply here. */
    @Bean(destroyMethod = "close")
    public ExecutorService drishtiVirtualExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("drishti-vt-", 0).factory());
    }

    @Bean(destroyMethod = "close")
    public SourceRegistry sourceRegistry(SourcesProperties props, JsonCodec codec) {
        return new SourceRegistry(new PluginDiscovery().discover(props.pluginDir()), props, codec);
    }

    @Bean
    public SourceRouter sourceRouter(SourceRegistry registry, SourcesProperties props, ExecutorService drishtiVirtualExecutor) {
        return new SourceRouter(registry, props, drishtiVirtualExecutor);
    }
}
