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
    public ConnectorFiles connectorFiles(SourcesProperties props, org.springframework.core.env.Environment env,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.identity.content.ContentStore> store) {
        ConnectorFiles files = new ConnectorFiles(Path.of(props.connectorsDir()), Path.of(env.getProperty("drishti.data.dir", "./data"), "connectors"));
        // Admin edits are a shared overlay over the files (CONTENT_STORE.md section 11); "file" keeps them in the folder of this node
        boolean shared = !"file".equalsIgnoreCase(env.getProperty("drishti.connectors.store", "content").trim());
        com.ash.drishti.identity.content.ContentStore s = shared ? store.getIfAvailable() : null;
        return s == null ? files : files.overlay(new ContentConnectorOverlay(s));
    }

    /**
     * Connectors drifted from their pack's template, merged automatically when additive. The mode is {@code template-updates} in
     * the connectors settings file ({@code drishti.connectors.settings-file}, default {@code ./config/connectors-settings.yaml}),
     * overridden by {@code drishti.connectors.template-updates}: additive (the default), notify or off.
     */
    @Bean(destroyMethod = "close")
    public TemplateUpdates templateUpdates(ConnectorManager manager, AuditLog audit, org.springframework.core.env.Environment env) {
        String mode = env.getProperty("drishti.connectors.template-updates");
        if (mode == null) {
            Path f = Path.of(env.getProperty("drishti.connectors.settings-file", "./config/connectors-settings.yaml"));
            if (java.nio.file.Files.isRegularFile(f)) {
                try {
                    Object v = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                            .readValue(f.toFile(), java.util.Map.class).get("template-updates");
                    mode = v == null ? null : String.valueOf(v);
                } catch (java.io.IOException | RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger(ConnectorsConfiguration.class).warn("{} cannot be read ({}); template-updates is additive", f, e.getMessage());
                }
            }
        }
        return new TemplateUpdates(manager, audit, TemplateUpdates.Mode.of(mode))
                .start(env.getProperty("drishti.connectors.template-check-ms", Long.class, 60_000L));
    }

    @Bean
    public SettingCatalogue settingCatalogue(ConnectionProbe probe) {
        return new SettingCatalogue(probe.discovered());
    }

    @Bean(destroyMethod = "stopWatching")
    public ConnectorManager connectorManager(ConnectorFiles files, SourceRegistry registry, SourcesProperties props, PackRegistry packs,
            ConfigurableEnvironment env, SettingCatalogue catalogue, ConnectionProbe probe, DeployProperties deploy, AuditLog audit,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.identity.content.ContentWatch> watch) {
        ConnectorManager m = new ConnectorManager(files, registry, props, packs, env, catalogue, probe, deploy.probeDates(), deploy.probeTimeoutSeconds());
        m.generated().forEach(n -> audit.record("system", "connector-generated", n, "written from a pack's connector template at start"));
        m.migrated().forEach(n -> audit.record("system", "connector-migrated", n, "moved from a pack data-source override into " + n + ".yaml"));
        if (files.overlay() instanceof ContentConnectorOverlay o) {
            watch.ifAvailable(w -> w.subscribe(ContentConnectorOverlay.PREFIX, keys -> {
                if (!o.refresh(keys).isEmpty()) {
                    m.scan();                                // another node's Admin edit: hot-reload as for a file edit
                }
            }));
            m.scan();
        }
        m.startWatching();
        return m;
    }
}
