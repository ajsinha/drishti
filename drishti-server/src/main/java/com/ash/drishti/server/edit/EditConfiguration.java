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
package com.ash.drishti.server.edit;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.SwitchLedger;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.net.RunCluster;
import com.ash.drishti.server.security.Entitlements;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Write-back wiring (docs/architecture/WRITE_BACK.md): reads {@code config/edit/} ({@code drishti.edit.file}) once at start, keeps the edit
 * log, lanes, overlays and owned values in the Drishti database (the identity database's pool), lays overlays over every document the
 * router reads, and starts the lanes this node owns. Off as shipped: nothing starts and every write answers {@code 403 DRS-9301}.
 */
@Configuration(proxyBeanMethods = false)
public class EditConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(EditConfiguration.class);

    static Path file(Environment env) {
        return Path.of(env.getProperty("drishti.edit.file", "./config/edit/edit.yaml"));
    }

    @Bean
    public EditSettings editSettings(Environment env) {
        try {
            return EditSettings.load(file(env), env::resolvePlaceholders);
        } catch (IllegalArgumentException e) {
            LOG.error("write-back: {} is not usable ({}); editing stays off", file(env), e.getMessage());
            return EditSettings.defaults();
        }
    }

    @Bean
    public EditableFields editableFields(SutraRegistry sutras) {
        return new EditableFields(sutras);
    }

    @Bean
    public ConnectorWrites connectorWrites(ObjectProvider<ConnectorManager> connectors, SourceRegistry registry, ObjectProvider<SwitchLedger> switches) {
        return new ConnectorWrites(name -> {
            ConnectorManager m = connectors.getIfAvailable();
            return m == null ? null : m.files().read(name);
        }, registry::plugin, switches.getIfAvailable());
    }

    @Bean(destroyMethod = "close")
    public EditRuntime editRuntime(EditSettings settings, HikariDataSource identityDataSource, SourceRouter router, EditableFields fields, Entitlements entitlements,
            Principals principals, PreferenceStore drafts, AuditLog audit, BusinessDates dates, ObjectProvider<RunCluster> cluster,
            ObjectProvider<NetService> net, ConnectorWrites writes, ObjectProvider<ConnectorManager> connectors) {
        NetService n = net.getIfAvailable();
        String planned = n == null ? "" : n.plannedNodeId();
        boolean clustered = !planned.isBlank() && n.node().map(NetService.Node::expectsPeers).orElse(true);
        String node = planned.isBlank() ? "local" : planned;
        EditRuntime r = new EditRuntime(settings, identityDataSource, router, fields, entitlements, user -> principals.user(user).isPresent() ? principals.of(user) : null, drafts, audit, dates::current,
                cluster.getIfAvailable(), node, clustered, writes, kind -> connectorOf(connectors.getIfAvailable(), kind));
        r.start();
        return r;
    }

    /** The connector whose {@code writes:} a source target of a kind uses when the form names none: the one connector of the kind that writes. */
    static Optional<String> connectorOf(ConnectorManager m, String kind) {
        if (m == null) {
            return Optional.empty();
        }
        List<String> writing = m.names().stream().filter(name -> {
            try {
                ConnectorFiles.Definition d = m.files().read(name);
                return d != null && !d.writes().isEmpty() && d.kinds().contains(kind);
            } catch (RuntimeException e) {
                return false;
            }
        }).toList();
        return writing.size() == 1 ? Optional.of(writing.get(0)) : Optional.empty();
    }

    @Bean
    public EditService editService(EditRuntime runtime) {
        return runtime.service();
    }

    @Bean
    public EditFeed editFeed(EditRuntime runtime) {
        return runtime.feed();
    }

    @Bean
    public EditPreview editPreview(EditRuntime runtime, Entitlements entitlements, com.ash.drishti.identity.UserService users) {
        return new EditPreview(runtime.service(), runtime.handler(), entitlements, users);
    }

    /** With an edit primary: every other node forwards write-back's API to it (after the caller is known, beside the runs' forwarding). */
    @Bean
    public FilterRegistrationBean<EditForwardFilter> editForwardFilter(ObjectProvider<EditRuntime> runtime, ObjectProvider<RunCluster> cluster) {
        FilterRegistrationBean<EditForwardFilter> f = new FilterRegistrationBean<>(new EditForwardFilter(runtime::getIfAvailable, cluster.getIfAvailable()));
        f.addUrlPatterns("/api/v1/changes", "/api/v1/changes/*", "/api/v1/entities/*", "/api/v1/admin/edits", "/api/v1/admin/edits/*");
        f.setOrder(4);
        return f;
    }
}
