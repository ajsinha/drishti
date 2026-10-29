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
package com.ash.drishti.server.admin;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.EmbedAppStore;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.packs.PackPaths;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.PackOverlay;
import com.ash.drishti.server.PackReloader;
import com.ash.drishti.server.bi.BiFolder;
import com.ash.drishti.server.bi.BiProperties;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.security.PackAccess;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Admin, Artifacts: the catalogue, the impact reader, the archive of uninstalled artifacts and the daily pruning of it. Settings:
 * {@code drishti.admin.uninstalled-dir} (default {@code <data>/uninstalled}), {@code drishti.admin.uninstalled-keep} (default 30d; 0 keeps
 * everything), {@code drishti.admin.uninstalled-prune-interval} (default 1d), {@code drishti.admin.hidden-bi-file} (default
 * {@code <data>/admin/bi-hidden.txt}) and {@code drishti.admin.bi-upload-max-bytes} (default 1 MiB).
 */
@Configuration(proxyBeanMethods = false)
public class ArtifactConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactConfiguration.class);

    @Bean
    public ArtifactPaths artifactPaths(ConfigurableEnvironment env, ConnectorManager connectors, BiProperties bi) {
        return new ArtifactPaths(Path.of(PackPaths.packsDir(env)), Path.of(PackPaths.installedDir(env)), connectors.files().dir(), Path.of(bi.dir()));
    }

    @Bean
    public ArtifactArchive artifactArchive(ConfigurableEnvironment env) {
        return new ArtifactArchive(Path.of(env.getProperty("drishti.admin.uninstalled-dir", PackPaths.dataDir(env) + "/uninstalled")), Clock.systemUTC());
    }

    @Bean
    public ArtifactImpact artifactImpact(PackRegistry packs, ConnectorManager connectors, BiFolder folder, BiStore bi, PreferenceStore prefs,
            ObjectProvider<EmbedAppStore> embeds, ObjectProvider<ShareStore> shares) {
        return new ArtifactImpact(packs, connectors, folder, bi, prefs, embeds, shares);
    }

    @Bean
    public ArtifactCatalog artifactCatalog(ArtifactPaths paths, PackRegistry packs, PackAccess access, ConfigurableEnvironment env, ConnectorManager connectors,
            BiFolder folder, AuditLog audit) {
        return new ArtifactCatalog(paths, packs, access, new PackOverlay(Path.of(PackPaths.overlay(env))), connectors, folder, audit);
    }

    @Bean
    public ArtifactService artifactService(ArtifactPaths paths, ArtifactArchive archive, ArtifactImpact impact, PackRegistry packs, PackAccess access, PackReloader reloader,
            ConfigurableEnvironment env, ConnectorManager connectors, BiFolder folder, AuditLog audit) {
        Duration keep = env.getProperty("drishti.admin.uninstalled-keep", Duration.class, Duration.ofDays(30));
        Path hidden = Path.of(env.getProperty("drishti.admin.hidden-bi-file", PackPaths.dataDir(env) + "/admin/bi-hidden.txt"));
        ArtifactService s = new ArtifactService(paths, archive, impact, packs, access, reloader, new PackOverlay(Path.of(PackPaths.overlay(env))), connectors, folder, audit, hidden,
                keep, com.ash.drishti.packs.PackPaths.enabled(env), env.getProperty("drishti.admin.bi-upload-max-bytes", Integer.class, 1_048_576));
        s.start();
        return s;
    }

    /** Prunes the archive once a day (and a minute after start): archives older than the retention are deleted for good. */
    @Bean(destroyMethod = "close")
    public ArtifactPruner artifactPruner(ArtifactService service, ConfigurableEnvironment env) {
        Duration every = env.getProperty("drishti.admin.uninstalled-prune-interval", Duration.class, Duration.ofDays(1));
        return new ArtifactPruner(service, every);
    }

    /** The daily pruning of the archive. */
    public static final class ArtifactPruner implements AutoCloseable {

        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("drishti-artifact-prune").factory());

        ArtifactPruner(ArtifactService service, Duration every) {
            if (!every.isZero() && !every.isNegative()) {
                timer.scheduleWithFixedDelay(() -> {
                    try {
                        service.prune();
                    } catch (RuntimeException e) {
                        LOG.warn("pruning the uninstalled archive failed: {}", e.getMessage());
                    }
                }, 60, Math.max(60, every.toSeconds()), TimeUnit.SECONDS);
            }
        }

        @Override
        public void close() {
            timer.shutdownNow();
        }
    }
}
