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

    @Bean
    public NetViews netViews(NetService net) {
        return new NetViews(net);
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
