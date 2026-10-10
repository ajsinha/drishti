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
package com.ash.drishti.server.connectors;

import com.ash.drishti.engine.source.ConnectionProbe;
import com.ash.drishti.engine.source.SettingCatalogue;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.deploy.DeployProperties;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/** Admin &rarr; Connectors: the connector folder, the plugin settings catalogue, and the manager that applies file changes live. */
@Configuration(proxyBeanMethods = false)
public class ConnectorsConfiguration {

    @Bean
    public ConnectorFiles connectorFiles(SourcesProperties props) {
        return new ConnectorFiles(Path.of(props.connectorsDir()));
    }

    @Bean
    public SettingCatalogue settingCatalogue(ConnectionProbe probe) {
        return new SettingCatalogue(probe.discovered());
    }

    @Bean(destroyMethod = "stopWatching")
    public ConnectorManager connectorManager(ConnectorFiles files, SourceRegistry registry, SourcesProperties props, PackRegistry packs,
            ConfigurableEnvironment env, SettingCatalogue catalogue, ConnectionProbe probe, DeployProperties deploy, AuditLog audit) {
        ConnectorManager m = new ConnectorManager(files, registry, props, packs, env, catalogue, probe, deploy.probeDates(), deploy.probeTimeoutSeconds());
        m.generated().forEach(n -> audit.record("system", "connector-generated", n, "written from a pack's connector template at start"));
        m.migrated().forEach(n -> audit.record("system", "connector-migrated", n, "moved from a pack data-source override into " + n + ".yaml"));
        m.startWatching();
        return m;
    }
}
