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
package com.ash.drishti.server.placement;

import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.kernel.api.health.HealthFigures;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.module.runtime.placement.ModuleParts;
import com.ash.drishti.module.runtime.placement.NodeView;
import com.ash.drishti.net.member.MemberState;
import com.ash.drishti.registry.AccessRegistry;
import com.ash.drishti.registry.ModuleState;
import com.ash.drishti.server.net.DigestService;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.net.PeerCalls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Module placement in the server (KERNEL_AND_MODULES.md section 4.11): {@code config/placement.yaml} ({@code drishti.placement.file}, else
 * beside the connectors' folder), the members of Drishti*net as the nodes, today's in-process modules as placed everywhere, the runtime's
 * host, the heavy-work guard, the Health figure, and the cluster agreeing on one placement: an applied one is sent to every member, each
 * node publishes its version in its digest, and a node that hears of a higher one fetches and adopts it.
 */
@Configuration(proxyBeanMethods = false)
public class PlacementConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PlacementConfiguration.class);
    /** Who placement's own calls between nodes act as. */
    static final Principal SYSTEM = new Principal("drishti-placement", List.of());
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    public PlacementService placementService(SourcesProperties sources, ObjectProvider<NetService> net, ObjectProvider<AccessRegistry> access,
            AuditLog audit, Environment env) {
        String f = env.getProperty("drishti.placement.file", "");
        Path file = f.isBlank() ? Path.of(sources.connectorsDir()).toAbsolutePath().normalize().resolveSibling("placement.yaml") : Path.of(f);
        PlacementService s = new PlacementService(file, Path.of(env.getProperty("drishti.data.dir", "./data"), "placement", "plans.json"),
                () -> nodes(net.getIfAvailable()), () -> self(net.getIfAvailable()), () -> inProcess(access.getIfAvailable()), audit,
                Clock.systemUTC());
        s.refresh();
        return s;
    }

    /** The members of Drishti*net (a left one is gone), or this server alone when the cluster is off. */
    static List<NodeView> nodes(NetService net) {
        if (net != null && net.running() && net.node().isPresent()) {
            String me = net.nodeId();
            return net.node().get().table().entries().stream().filter(m -> m.state() != MemberState.LEFT)
                    .map(m -> new NodeView(m.nodeId(), m.tags(), m.nodeId().equals(me) || m.routable())).toList();
        }
        return List.of(new NodeView(self(net), net == null ? List.of() : net.config().node().tags(), true));
    }

    static String self(NetService net) {
        if (net == null) {
            return "local";
        }
        String id = net.nodeId().isBlank() ? net.plannedNodeId() : net.nodeId();
        return id == null || id.isBlank() ? "local" : id;
    }

    /** Today's modules built into the server (banking and the rest): placed on every node until each becomes a module. */
    static List<ModuleParts> inProcess(AccessRegistry access) {
        return access == null ? List.of() : access.modules().stream().filter(ModuleState::installed)
                .filter(m -> access.manifest(m.id()).map(x -> !x.source().endsWith(".dmod") && !x.source().startsWith("the module store")).orElse(true))
                .map(m -> ModuleParts.inProcess(m.id(), "built-in")).toList();
    }

    @Bean
    public PlacementHost placementHost(PlacementService s) {
        return new PlacementHost(s);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService placementSync(PlacementService s, ObjectProvider<DigestService> digests, PeerCalls peers, Environment env) {
        DigestService d = digests.getIfAvailable();
        if (d != null) {
            d.service("placement", () -> String.valueOf(s.current().version()));
        }
        s.publisher(p -> Thread.ofVirtual().name("drishti-placement-send").start(() -> {
            if (d != null) {
                d.announce();
            }
            byte[] body = json(Map.of("text", p.toYaml()));
            peers.all("POST", "/api/v1/net/placement", body, SYSTEM, Duration.ofSeconds(10)).stream().filter(a -> a.status() != 200)
                    .forEach(a -> LOG.info("placement: version {} not taken by {} now ({}); it catches up from the digest", p.version(), a.node(), a.status()));
        }));
        long every = Math.max(1, env.getProperty("drishti.placement.refresh-seconds", Long.class, 10L));
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("drishti-placement").factory());
        ex.scheduleWithFixedDelay(() -> {
            try {
                long before = s.current().version();
                s.refresh();
                if (d != null) {
                    catchUp(s, d, peers);
                    if (s.current().version() != before) {
                        d.announce();
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("placement: refresh failed: {}", e.getMessage());
            }
        }, every, every, TimeUnit.SECONDS);
        return ex;
    }

    /** A member whose digest says a higher placement version is asked for it; it is adopted here. */
    private static void catchUp(PlacementService s, DigestService d, PeerCalls peers) {
        d.peers().forEach((node, digest) -> {
            long theirs;
            try {
                theirs = Long.parseLong(digest.services().getOrDefault("placement", "0"));
            } catch (NumberFormatException e) {
                return;
            }
            if (theirs > s.current().version() && peers.peers().contains(node)) {
                PeerCalls.Answer a = peers.call(node, "GET", "/api/v1/net/placement", new byte[0], SYSTEM, Duration.ofSeconds(10));
                if (a.status() == 200) {
                    try {
                        JsonNode n = JSON.readTree(a.body());
                        s.adopt(n.path("text").asText(""), node);
                    } catch (Exception e) {
                        LOG.warn("placement: {}'s placement does not read: {}", node, e.getMessage());
                    }
                }
            }
        });
    }

    private static byte[] json(Object o) {
        try {
            return JSON.writeValueAsString(o).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** After the caller is known and the module gate (3): a part not placed here is forwarded or refused before any controller runs. */
    @Bean
    public FilterRegistrationBean<PlacementGuardFilter> placementGuardFilter(PlacementService s, PeerCalls peers) {
        FilterRegistrationBean<PlacementGuardFilter> r = new FilterRegistrationBean<>(new PlacementGuardFilter(s, peers));
        r.addUrlPatterns("/api/*");
        r.setOrder(4);
        return r;
    }

    @Bean
    public HealthFigures placementHealth(PlacementService s) {
        return new HealthFigures() {
            @Override
            public String key() {
                return "placement";
            }

            @Override
            public Object value() {
                return s.health();
            }
        };
    }
}
