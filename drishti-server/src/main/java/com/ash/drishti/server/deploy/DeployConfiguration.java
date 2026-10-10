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
package com.ash.drishti.server.deploy;

import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.design.AutoDesigner;
import com.ash.drishti.engine.shape.ShapeService;
import com.ash.drishti.engine.source.ConnectionProbe;
import com.ash.drishti.engine.source.PluginDiscovery;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.registry.RegistryProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/** Admin → Packs: deploying an archive, and the data source of a pack. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeployProperties.class)
public class DeployConfiguration {

    @Bean
    public PackDeployService packDeployService(ConfigurableEnvironment env, DeployProperties props, RegistryProperties registryProps, PackRegistry running,
            ObjectProvider<BuildProperties> build, SutraRegistry sutras, ViewPipeline pipeline, ShapeService shapes, AutoDesigner designer, JsonCodec codec,
            com.ash.drishti.packs.ConnectorFiles connectorFiles) {
        return new PackDeployService(env, props, registryProps, running, build, sutras, pipeline, shapes, designer, codec, connectorFiles);
    }

    @Bean
    public ConnectionProbe connectionProbe(SourcesProperties sources, JsonCodec codec) {
        return new ConnectionProbe(new PluginDiscovery().discover(sources.pluginDir()), codec);
    }
}
