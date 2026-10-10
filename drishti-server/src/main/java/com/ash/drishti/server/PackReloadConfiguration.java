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
package com.ash.drishti.server;

import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.packs.SamplePolicy;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.connectors.ConnectorManager;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/** Live pack reload: the reloader that watches the packs folder and serves Admin &rarr; Packs Load and Unload. */
@Configuration(proxyBeanMethods = false)
public class PackReloadConfiguration {

    @Bean(destroyMethod = "close")
    public PackReloader packReloader(ConfigurableEnvironment env, PackRegistry registry, ConnectorManager connectors, SutraRegistry sutras,
            Mnemonics mnemonics, AuditLog audit, SamplePolicy samples) {
        PackReloader r = new PackReloader(env, registry, connectors, sutras, mnemonics,
                new PackOverlay(Path.of(env.getProperty("drishti.packs.overlay", "./data/packs/added.yaml"))), audit, samples);
        r.start();
        return r;
    }
}
