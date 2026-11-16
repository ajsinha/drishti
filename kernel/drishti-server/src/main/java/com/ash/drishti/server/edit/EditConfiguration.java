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
 * Write-back wiring (docs/guides/APPROVALS_GUIDE.md): reads {@code config/edit/} ({@code drishti.edit.file}) once at start, keeps the edit
 * log, lanes, overlays and owned values in the Drishti database (the identity database's pool), lays overlays over every document the
 * router reads, and starts the lanes this node owns. Off as shipped: nothing starts and every write answers {@code 403 DRS-9301}.
 */
@Configuration(proxyBeanMethods = false)
public class EditConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(EditConfiguration.class);

    static Path file(Environment env) {
        return Path.of(env.getProperty("drishti.edit.file", com.ash.drishti.api.Roots.config("edit/edit.yaml")));
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
    public ConnectorWrites connectorWrites(ObjectProvider<ConnectorManager> connectors, SourceRegistry registry, ObjectProvider<SwitchLedger> switches,
            org.springframework.core.env.Environment env) {
        return new ConnectorWrites(name -> {
            ConnectorManager m = connectors.getIfAvailable();
            return m == null ? null : m.effective(name).orElse(null);       // the effective connector: a module's template counts, not only a file
        }, registry::plugin, switches.getIfAvailable()).resolvedBy(env::resolvePlaceholders);
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
        r.service().route(sourceRoute(router, connectors::getIfAvailable));
        r.start();
        return r;
    }

    /**
     * Where a change goes when several trading systems feed a kind (DATA_LAKE_GUIDE.md, "Several source systems"): the record's
     * {@code SOURCE_SYSTEM} as read (its connector's where or set), and that system's running connectors.
     */
    static SourceRoute sourceRoute(SourceRouter router, java.util.function.Supplier<ConnectorManager> manager) {
        return new SourceRoute() {
            @Override
            public Optional<String> systemOf(String kind, String entity) {
                if (router.registry().connectorsBySystem().isEmpty()) {
                    return Optional.empty();
                }
                com.ash.drishti.api.EntityDocument d = router.fetch(com.ash.drishti.api.EntityRef.of(kind, entity)).handle((x, e) -> x).join();
                if (d == null || d.data() == null) {
                    return Optional.empty();
                }
                // the record's own source first: the connector it was read from names its system; else the record's SOURCE_SYSTEM
                if (d.provenance() != null && d.provenance().source() != null) {
                    Optional<String> own = router.registry().shape(d.provenance().source()).flatMap(com.ash.drishti.engine.source.SourceShape::system);
                    if (own.isPresent()) {
                        return own;
                    }
                }
                var v = d.data().get(com.ash.drishti.engine.source.SourceShape.SOURCE_SYSTEM);
                String s = v == null ? "" : v.asText();
                return s.isBlank() ? Optional.empty() : Optional.of(s);
            }

            @Override
            public List<String> connectorsOf(String system) {
                java.util.Set<String> out = new java.util.TreeSet<>(router.registry().connectorsBySystem().getOrDefault(system, List.of()));
                ConnectorManager m = manager.get();
                if (m != null && system != null) {              // a bound or defined write connector of the system, running or not
                    String tail = "-" + system.toLowerCase(java.util.Locale.ROOT);
                    for (String n : m.names()) {
                        boolean writes = m.effective(n).map(d -> !d.writes().isEmpty()).orElse(false);
                        boolean ofSystem = n.toLowerCase(java.util.Locale.ROOT).contains(tail)
                                || m.effective(n).map(d -> system.equalsIgnoreCase(String.valueOf(setOf(d).get(com.ash.drishti.engine.source.SourceShape.SOURCE_SYSTEM)))).orElse(false);
                        if (writes && ofSystem) {
                            out.add(n);
                        }
                    }
                    m.bindings().byKind().values().forEach(b -> b.writeConnector(system).filter(c -> !c.isBlank()).ifPresent(out::add));
                }
                return List.copyOf(out);
            }

            @Override
            public Optional<String> boundWrite(String kind, String system) {
                ConnectorManager m = manager.get();
                return m == null ? Optional.empty() : m.bindings().of(kind).flatMap(b -> b.writeConnector(system));
            }
        };
    }

    /** A connector's {@code settings.set} (the fields it stamps on every record, {@code SOURCE_SYSTEM} among them), or empty. */
    private static java.util.Map<?, ?> setOf(com.ash.drishti.packs.ConnectorFiles.Definition d) {
        Object flat = d.settings().get("set." + com.ash.drishti.engine.source.SourceShape.SOURCE_SYSTEM);      // settings as flattened keys
        return flat == null ? java.util.Map.of() : java.util.Map.of(com.ash.drishti.engine.source.SourceShape.SOURCE_SYSTEM, flat);
    }

    /**
     * The connector whose {@code writes:} a source target of a kind uses when the form names none (KERNEL_AND_MODULES.md section 18.6): the
     * effective connectors (a site file, or the template of a pack or a module that is on) whose {@code writes:} name the kind; the only
     * one, else none (a record names its system: {@link #writeConnectorOf}).
     */
    static Optional<String> connectorOf(ConnectorManager m, String kind) {
        List<String> writing = writersOf(m, kind);
        return writing.size() == 1 ? Optional.of(writing.get(0)) : Optional.empty();
    }

    /** The effective connectors whose {@code writes:} name {@code kind}, in name order. */
    static List<String> writersOf(ConnectorManager m, String kind) {
        if (m == null) {
            return List.of();
        }
        return m.names().stream().sorted().filter(name -> m.effective(name).map(d -> d.writes().values().stream()
                .anyMatch(w -> w instanceof java.util.Map<?, ?> wm && kind.equals(String.valueOf(wm.get("kind")))) || !d.writes().isEmpty() && d.kinds().contains(kind))
                .orElse(false)).toList();
    }

    /**
     * The write path of a record of {@code kind} from {@code system} (DRS-9308 none, DRS-9309 several): of the effective connectors that
     * write the kind, the one of that system ({@code source-name} or name {@code <kind>-<system>-...}); without a system, the only one.
     */
    static String writeConnectorOf(ConnectorManager m, String kind, String system) {
        List<String> writing = writersOf(m, kind);
        List<String> mine = system == null || system.isBlank() ? writing : writing.stream()
                .filter(n -> n.toLowerCase(java.util.Locale.ROOT).contains("-" + system.toLowerCase(java.util.Locale.ROOT))).toList();
        if (mine.size() == 1) {
            return mine.get(0);
        }
        if (mine.isEmpty()) {
            throw new IllegalStateException("kind '" + kind + "'" + (system == null || system.isBlank() ? "" : " (source " + system + ")")
                    + " has no write path: no connector that serves it offers writes" + (writing.isEmpty() ? "" : " for that system (writers: " + writing + ")")
                    + "; switch on the source module's live connector or bind one in Admin -> Data bindings (write) (DRS-9308)");
        }
        throw new IllegalStateException("kind '" + kind + "' has " + mine.size() + " write paths, " + mine + ", and the record names no source system;"
                + " name one in Admin -> Data bindings (write) (DRS-9309)");
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
