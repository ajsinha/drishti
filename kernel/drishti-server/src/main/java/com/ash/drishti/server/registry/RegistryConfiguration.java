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
package com.ash.drishti.server.registry;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The signed pack registry ({@code drishti.packs.registry}); installs go to {@code drishti.packs.installed-dir}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RegistryProperties.class)
public class RegistryConfiguration {

    @Bean
    public PackRegistryClient packRegistryClient(RegistryProperties props,
            @Value("${drishti.packs.installed-dir:${drishti.data.dir:./data}/packs/installed}") String installedDir) {
        return new PackRegistryClient(props, Path.of(installedDir));
    }
}
