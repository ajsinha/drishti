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
package com.ash.drishti.server.node;

import com.ash.drishti.server.net.DigestService;
import com.ash.drishti.server.net.NetService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.health.AdditionalHealthEndpointPath;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * This server as a node of a cluster (CD1 and CD2, 2.9.7): readiness that means ready ({@link NodeReadiness}, in the {@code readiness} health
 * group of application.yaml only) and the drain before a stop ({@link NodeDrain}, {@code /api/v1/admin/node}). The engines add their gates
 * and drainable parts as beans ({@link ReadinessGate}, {@link Drainable}); Drishti*net's are made here.
 */
@Configuration
@EnableConfigurationProperties(NodeProperties.class)
public class NodeConfiguration {

    @Bean
    public NodeDrain nodeDrain(NodeProperties props, ObjectProvider<NetService> net, ObjectProvider<Drainable> parts) {
        NetService n = net.getIfAvailable();
        Drainable netPart = n == null ? null : new NetDrainPart(n, () -> props.drain().settleSeconds(), System::currentTimeMillis);
        return new NodeDrain(() -> {
            List<Drainable> all = new ArrayList<>();
            if (netPart != null) {
                all.add(netPart);
            }
            parts.orderedStream().forEach(all::add);
            all.addAll(com.ash.drishti.common.Extensions.of(Drainable.class));      // a loaded module's
            return all;
        });
    }

    /** Registered as {@value NodeReadiness#NAME} (Spring drops the bean name's {@code HealthIndicator} suffix). */
    @Bean
    public NodeReadiness nodeReadinessHealthIndicator(NodeProperties props, NodeDrain drain, ObjectProvider<DigestService> digests,
            ObjectProvider<ReadinessGate> gates) {
        ReadinessGate joining = new ReadinessGate() {
            public String name() {
                return "net";
            }

            public String kind() {
                return "joining";
            }

            public List<String> waitingFor() {
                DigestService d = digests.getIfAvailable();
                return d != null && d.joining() ? List.of("net: joining the cluster (the peers' digests not had yet; at most routing.yaml's join-grace)")
                        : List.of();
            }
        };
        return new NodeReadiness(props::readiness, () -> {
            List<ReadinessGate> all = new ArrayList<>();
            all.add(joining);
            gates.orderedStream().forEach(all::add);
            all.addAll(com.ash.drishti.common.Extensions.of(ReadinessGate.class));  // a loaded module's
            return all;
        }, drain);
    }

    /**
     * Keeps {@link NodeReadiness} out of {@code /actuator/health} itself: the readiness group (application.yaml) includes it, the whole
     * server's health does not, so a node that waits for a date or is drained is never reported as broken (and the tools that wait for
     * {@code /actuator/health} at start are not held up by a date opening).
     */
    @Bean
    public static HealthEndpointGroupsPostProcessor readinessOnly() {
        return groups -> {
            HealthEndpointGroup primary = groups.getPrimary();
            Map<String, HealthEndpointGroup> others = new LinkedHashMap<>();
            for (String name : groups.getNames()) {
                others.put(name, groups.get(name));
            }
            return HealthEndpointGroups.of(without(primary, Set.of(NodeReadiness.NAME)), others);
        };
    }

    /** The group without the named members. */
    static HealthEndpointGroup without(HealthEndpointGroup g, Set<String> names) {
        return new HealthEndpointGroup() {
            public boolean isMember(String name) {
                return !names.contains(name) && g.isMember(name);
            }

            public boolean showComponents(SecurityContext c) {
                return g.showComponents(c);
            }

            public boolean showDetails(SecurityContext c) {
                return g.showDetails(c);
            }

            public StatusAggregator getStatusAggregator() {
                return g.getStatusAggregator();
            }

            public HttpCodeStatusMapper getHttpCodeStatusMapper() {
                return g.getHttpCodeStatusMapper();
            }

            public AdditionalHealthEndpointPath getAdditionalPath() {
                return g.getAdditionalPath();
            }
        };
    }
}
