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
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/** Admin &rarr; Connectors: the connector folder, the plugin settings catalogue, and the manager that applies file changes live. */
@Configuration(proxyBeanMethods = false)
public class ConnectorsConfiguration {

    @Bean
    public ConnectorFiles connectorFiles(SourcesProperties props, org.springframework.core.env.Environment env) {
        return new ConnectorFiles(Path.of(props.connectorsDir()), Path.of(env.getProperty("drishti.data.dir", "./data"), "connectors"));
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

    @Bean
    public ConnectorFileDrift connectorFileDrift(ConnectorFiles files, ConnectorManager manager,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.net.DigestService> digests,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.net.RunCluster> cluster) {
        ConnectorFileDrift d = new ConnectorFileDrift(files, digests::getIfAvailable, cluster::getIfAvailable);
        manager.fileDrift(d);
        return d;
    }

    /** When the server is ready: the digest carries this node's connector files, for Admin, Health's comparison across nodes. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void publishConnectorFiles(org.springframework.boot.context.event.ApplicationReadyEvent e) {
        var ctx = e.getApplicationContext();
        ConnectorFileDrift drift = ctx.getBean(ConnectorFileDrift.class);
        ctx.getBeanProvider(com.ash.drishti.server.net.DigestService.class).ifAvailable(d -> d.service(ConnectorFileDrift.SERVICE, drift::wire));
    }

    @Bean(destroyMethod = "stopWatching")
    public ConnectorManager connectorManager(ConnectorFiles files, SourceRegistry registry, SourcesProperties props, PackRegistry packs,
            ConfigurableEnvironment env, SettingCatalogue catalogue, ConnectionProbe probe, DeployProperties deploy, AuditLog audit,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.identity.content.ContentStore> store) {
        List<String> folded = store.stream().findFirst().map(s -> new ConnectorOverlayFold(files, s, audit).run()).orElse(List.of());
        ConnectorManager m = new ConnectorManager(files, registry, props, packs, env, catalogue, probe, deploy.probeDates(), deploy.probeTimeoutSeconds());
        m.generated().forEach(n -> audit.record("system", "connector-generated", n, "written from a pack's connector template at start"));
        m.migrated().forEach(n -> audit.record("system", "connector-migrated", n, "moved from a pack data-source override into " + n + ".yaml"));
        if (!folded.isEmpty()) {
            m.applyFolded(folded);
        }
        m.startWatching();
        return m;
    }
}
