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
    public NetViews netViews(NetService net, DigestService digests) {
        NetViews v = new NetViews(net, digests);
        v.digestSummary(id -> NetViews.summary(id.equals(net.nodeId()) && net.running() ? digests.self() : digests.peers().get(id)));
        return v;
    }

    @Bean
    public ForwardLog forwardLog(io.micrometer.core.instrument.MeterRegistry meters) {
        return new ForwardLog(meters);
    }

    @Bean
    public NetHealth netHealth(NetService net, ForwardLog log, io.micrometer.core.instrument.MeterRegistry meters) {
        return new NetHealth(net, log, meters);
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

    @Bean
    public PeerSources peerSources(NetRouting routing, NetService net, com.ash.drishti.common.JsonCodec codec, ForwardLog log,
            com.ash.drishti.engine.source.SourceRouter router) {
        return new PeerSources(routing, net, codec, log, router);
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
            SearchScatter scatter) {
        FilterRegistrationBean<ViewForwardFilter> r = new FilterRegistrationBean<>(new ViewForwardFilter(routing, entitlements, log, access, mnemonics, scatter));
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
