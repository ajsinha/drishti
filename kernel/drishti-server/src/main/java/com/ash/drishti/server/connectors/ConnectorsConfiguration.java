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

    /** The connector edits an earlier release kept in the content store and this node never folded into files (its marker is absent). */
    static List<String> storeEdits(ConnectorFiles files, com.ash.drishti.identity.content.ContentStore store) {
        if (java.nio.file.Files.exists(files.stateDir().resolve("overlay-folded.txt"))) {
            return List.of();                                   // folded by the earlier release on this node: nothing is left
        }
        try {
            return store.list("config/connectors/").stream().filter(k -> k.endsWith(".yaml"))
                    .map(k -> k.substring("config/connectors/".length(), k.length() - 5)).filter(n -> !n.contains("/")).sorted().toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Bean(destroyMethod = "stopWatching")
    public ConnectorManager connectorManager(ConnectorFiles files, SourceRegistry registry, SourcesProperties props, PackRegistry packs,
            ConfigurableEnvironment env, SettingCatalogue catalogue, ConnectionProbe probe, DeployProperties deploy, AuditLog audit,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.identity.content.ContentStore> store) {
        ConnectorManager m = new ConnectorManager(files, registry, props, packs, env, catalogue, probe, deploy.probeDates(), deploy.probeTimeoutSeconds());
        // the server writes no connector file at start (KERNEL_AND_MODULES.md section 18.5): an edit an earlier release kept in the content
        // store is not applied; Health lists it, to be saved in Admin -> Connectors
        store.stream().findFirst().ifPresent(s -> m.storeLeftovers(storeEdits(files, s)));
        m.startWatching();
        return m;
    }
}
