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
package com.ash.drishti.server.net;

import com.ash.drishti.net.config.NetFolder;
import com.ash.drishti.net.config.NetSchema;
import com.ash.drishti.net.member.NetClock;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Drishti*net's beans (docs/architecture/DRISHTI_NET.md). They always exist, and do nothing while {@code config/net/node.yaml}
 * does not say {@code enabled: true}: no thread gossips, no peer is asked, no request is forwarded, and the cluster's endpoints
 * answer that the cluster is off.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NetProperties.class)
public class NetConfiguration {

    @Bean(destroyMethod = "close")
    public NetService netService(NetProperties props, Environment env, ObjectProvider<org.springframework.boot.info.BuildProperties> build) {
        Path state = Path.of(env.getProperty("drishti.data.dir", "./data"), "net");
        // ${ENV} references in the files are read from the environment, then from the server's own properties (a test, a run configuration)
        NetSchema schema = new NetSchema(name -> {
            String v = System.getenv(name);
            return v != null ? v : env.getProperty(name);
        });
        NetFolder folder = new NetFolder(Path.of(props.dir()), state, schema);
        String version = build.getIfAvailable() == null ? "dev" : build.getIfAvailable().getVersion();
        NetService net = new NetService(folder, state, props, version, NetClock.SYSTEM);
        net.advertiseDefault("true".equalsIgnoreCase(env.getProperty("server.ssl.enabled")) ? "https" : "http",
                env.getProperty("server.port", Integer.class, 8080));
        net.start();
        return net;
    }

    @Bean(destroyMethod = "close")
    public DigestService digestService(NetService net, com.ash.drishti.packs.PackRegistry packs, com.ash.drishti.server.security.PackAccess packAccess,
            com.ash.drishti.engine.command.Mnemonics mnemonics, com.ash.drishti.engine.source.SourceRegistry sources,
            com.ash.drishti.engine.time.BusinessDates dates, com.ash.drishti.server.api.RequestActivity activity,
            com.ash.drishti.server.security.SecurityProperties security) {
        return new DigestService(net, packs, packAccess, mnemonics, sources, () -> dates.calendar().name(), activity, security);
    }

    @Bean
    public NetViews netViews(NetService net, DigestService digests, ObjectProvider<StateSync> state) {
        NetViews v = new NetViews(net, digests);
        v.digestSummary(id -> NetViews.summary(id.equals(net.nodeId()) && net.running() ? digests.self() : digests.peers().get(id)));
        v.userState(() -> {
            StateSync s = state.getIfAvailable();
            return s == null ? java.util.Map.of() : s.summary();
        });
        return v;
    }

    @Bean
    public ForwardLog forwardLog(io.micrometer.core.instrument.MeterRegistry meters) {
        return new ForwardLog(meters);
    }

    /**
     * The per-user store tells Drishti*net of every change, so a user's settings, layouts, pivots and workspaces follow them to another node
     * (N7). A plain store while the cluster is off.
     */
    @Bean
    public static org.springframework.beans.factory.config.BeanPostProcessor netReplicatedPreferences(ObjectProvider<StateSync> sync) {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof com.ash.drishti.identity.PreferenceStore p && !(bean instanceof ReplicatedPreferences)) {
                    return new ReplicatedPreferences(p, sync::getIfAvailable);
                }
                return bean;
            }
        };
    }

    @Bean(destroyMethod = "close")
    public StateSync stateSync(NetService net, com.ash.drishti.identity.PreferenceStore prefs, ForwardLog log,
            ObjectProvider<com.ash.drishti.identity.SessionStore> sessions) {
        com.ash.drishti.identity.PreferenceStore raw = prefs instanceof ReplicatedPreferences r ? r.delegate() : prefs;
        com.ash.drishti.identity.SessionStore ss = sessions.getIfAvailable();
        return new StateSync(net, () -> raw, () -> ss, log);
    }

    /**
     * Users, roles and API tokens follow over the same batches (DRISHTI_NET.md section 11): a person known to one node is known to all, so
     * a console that falls over keeps them signed in. A user deleted on a peer is forgotten here as a deletion here forgets them.
     */
    @Bean(destroyMethod = "close")
    public IdentitySync identitySync(StateSync sync, com.ash.drishti.identity.JpaUserStore users, com.ash.drishti.identity.RoleStore roles,
            com.ash.drishti.identity.ApiTokenStore tokens, com.ash.drishti.identity.JpaAuditLog audit, Environment env,
            com.ash.drishti.identity.PreferenceStore prefs, com.ash.drishti.server.security.PackAccess packAccess,
            ObjectProvider<com.ash.drishti.identity.SessionStore> sessions, ObjectProvider<com.ash.drishti.identity.design.DesignService> designs,
            ObjectProvider<com.ash.drishti.identity.AlertHistory> alerts, ObjectProvider<com.ash.drishti.identity.collab.InboxStore> inbox) {
        com.ash.drishti.identity.PreferenceStore raw = prefs instanceof ReplicatedPreferences r ? r.delegate() : prefs;
        Path state = Path.of(env.getProperty("drishti.data.dir", "./data"), "net");
        return new IdentitySync(sync, users, roles, tokens, audit, state, packAccess::forget, user -> {
            packAccess.forget(user);
            raw.forget(user);
            tokens.forget(user);
            sessions.ifAvailable(s -> s.dropAllAdopted(user));
            designs.ifAvailable(d -> d.forget(user));
            alerts.ifAvailable(a -> a.forget(user));
            inbox.ifAvailable(i -> i.forget(user));
        });
    }

    @Bean
    public NetHealth netHealth(NetService net, ForwardLog log, io.micrometer.core.instrument.MeterRegistry meters,
            ObjectProvider<IdentitySync> identity) {
        return new NetHealth(net, log, meters, identity::getIfAvailable);
    }

    @Bean
    public NetRouting netRouting(NetService net, DigestService digests, com.ash.drishti.packs.PackRegistry packs,
            com.ash.drishti.server.security.PackAccess packAccess) {
        // a peer-only pack is hidden from this node's own users and served to peers (DRISHTI_NET.md section 6)
        packAccess.hiddenFromUsers(pack -> net.running() && net.config().node().peerOnlyPacks().contains(pack) && !NetCaller.fromPeer());
        return new NetRouting(net, digests, packs, packAccess);
    }

    @Bean
    public SearchScatter searchScatter(NetRouting routing, ForwardLog log, java.util.concurrent.ExecutorService drishtiVirtualExecutor) {
        return new SearchScatter(routing, log, drishtiVirtualExecutor);
    }

    /** The node that answered a view, named to administrators in "How this view was built". */
    @Bean
    public NetServedBy netServedBy(NetService net, com.ash.drishti.server.security.Entitlements entitlements) {
        return new NetServedBy(net, entitlements);
    }

    @Bean
    public PeerSources peerSources(NetRouting routing, NetService net, com.ash.drishti.common.JsonCodec codec, ForwardLog log,
            com.ash.drishti.engine.source.SourceRouter router, com.ash.drishti.engine.live.TopicHub hub, SearchScatter scatter) {
        // a live relay from a peer is masked there for the user it was opened for: while the cluster runs, viewers share an upstream only
        // with viewers of the same roles (DRISHTI_NET.md section 8, as built)
        hub.partition(() -> !net.running() ? "" : NetCaller.current().map(NetCaller.Caller::principal)
                .map(p -> String.join(",", new java.util.TreeSet<>(p.roles())) + "|" + (p.embedApp() == null ? "" : p.embedApp())).orElse(""));
        PeerSources sources = new PeerSources(routing, net, codec, log, router);
        sources.groups(new GroupGather(router, routing, scatter, codec));   // grouped pages gathered across the nodes (section 8)
        return sources;
    }

    /** After the token filter (the caller is known): who the request is for, for the router and the peer sources. */
    @Bean
    public FilterRegistrationBean<NetCaller.Filter> netCallerFilter() {
        FilterRegistrationBean<NetCaller.Filter> r = new FilterRegistrationBean<>(new NetCaller.Filter());
        r.addUrlPatterns("/api/*");
        r.setOrder(2);
        return r;
    }

    /** After the caller is known: a request for a kind another node serves goes there whole (view forwarding). */
    @Bean
    public FilterRegistrationBean<ViewForwardFilter> viewForwardFilter(NetRouting routing, com.ash.drishti.server.security.Entitlements entitlements,
            ForwardLog log, ObjectProvider<com.ash.drishti.identity.AccessLog> access, ObjectProvider<com.ash.drishti.engine.command.Mnemonics> mnemonics,
            SearchScatter scatter, ObjectProvider<com.ash.drishti.server.bi.report.BiReportService> reports,
            ObjectProvider<com.ash.drishti.server.bi.BiFolder> biFolder, DigestService digests) {
        // the BI reports this node ships, gossiped in its digest: a report's query goes to a node that has it and holds its data
        digests.reports(() -> {
            var folder = biFolder.getIfAvailable();
            return folder == null ? java.util.List.of() : folder.items().stream().filter(i -> "report".equals(i.kind())).map(i -> i.id()).toList();
        });
        FilterRegistrationBean<ViewForwardFilter> r = new FilterRegistrationBean<>(new ViewForwardFilter(routing, entitlements, log, access, mnemonics, scatter)
                .withReports(reports));
        r.addUrlPatterns("/api/*");
        r.setOrder(3);
        return r;
    }

    /** After the path guard and the size limit, before the token filter: a node's call is verified here and needs no bearer token. */
    @Bean
    public FilterRegistrationBean<NetCallFilter> netCallFilter(NetService net) {
        FilterRegistrationBean<NetCallFilter> r = new FilterRegistrationBean<>(new NetCallFilter(net));
        r.addUrlPatterns("/api/*");
        r.setOrder(0);
        return r;
    }
}
