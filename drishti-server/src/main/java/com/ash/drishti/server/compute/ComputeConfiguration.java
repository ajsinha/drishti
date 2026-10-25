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
package com.ash.drishti.server.compute;

import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.compute.ComputeSettings;
import com.ash.drishti.compute.approval.ApprovalItem;
import com.ash.drishti.compute.approval.ApprovalStore;
import com.ash.drishti.compute.approval.Approvals;
import com.ash.drishti.compute.cluster.PeerTransport;
import com.ash.drishti.compute.env.Requires;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.net.RunCluster;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Compute workers wiring (docs/architecture/PYTHON_COMPUTE.md): reads {@code config/compute/compute.yaml} ({@code drishti.compute.file}) once
 * at start, keeps approval requests and approved items in the Drishti database (memory without one), audits every decision, fans out over
 * Drishti*net members through {@link RunCluster}, and gates connectors whose files carry {@code requires:}. Off as shipped: no worker starts;
 * approvals still work, so requirements can be reviewed before compute is switched on.
 */
@Configuration(proxyBeanMethods = false)
public class ComputeConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ComputeConfiguration.class);

    static Path file(Environment env) {
        return Path.of(env.getProperty("drishti.compute.file", "./config/compute/compute.yaml"));
    }

    @Bean
    public ComputeSettings computeSettings(Environment env, ObjectProvider<com.ash.drishti.server.net.NetService> net) {
        Path data = Path.of(env.getProperty("drishti.data.dir", "./data"));
        com.ash.drishti.server.net.NetService n = net.getIfAvailable();
        String node = n == null ? "" : n.plannedNodeId();
        try {
            return ComputeSettings.load(file(env), data, node, env::resolvePlaceholders);
        } catch (IllegalArgumentException e) {
            LOG.error("compute: {} is not usable ({}); compute stays off", file(env), e.getMessage());
            return ComputeSettings.defaults(data);
        }
    }

    @Bean
    public Approvals computeApprovals(ObjectProvider<HikariDataSource> identity, ObjectProvider<AuditLog> audit) {
        HikariDataSource ds = identity.getIfAvailable();
        AuditLog log = audit.getIfAvailable();
        ApprovalStore store = ds == null ? new ApprovalStore.Memory() : new ApprovalStore.Jdbc(ds);
        return new Approvals(store, log == null ? null : log::record, Clock.systemUTC(), 5_000);
    }

    @Bean(destroyMethod = "close")
    public ComputeService computeService(ComputeSettings settings, Approvals approvals, Environment env, ObjectProvider<RunCluster> cluster,
            ObjectProvider<com.ash.drishti.server.net.NetService> net, ObjectProvider<ConnectorManager> connectors) {
        RunCluster c = cluster.getIfAvailable();
        PeerTransport peers = c == null ? PeerTransport.NONE : new NetComputePeers(c);
        com.ash.drishti.server.net.NetService n = net.getIfAvailable();
        String node = n != null && !n.plannedNodeId().isBlank() ? n.plannedNodeId() : "local";
        Path configDir = file(env).toAbsolutePath().getParent();
        ComputeService service = new ComputeService(settings, node, configDir, approvals, peers);
        try {
            service.start();
        } catch (RuntimeException e) {
            LOG.error("compute: could not start ({}); requests are refused until it is fixed", e.getMessage());
        }
        if (!settings.enabled()) {
            LOG.info("compute: off ({}); requirements can still be approved in Admin -> Compute", settings.origin());
        }
        ConnectorManager m = connectors.getIfAvailable();
        if (m != null) {
            Path base = m.files().dir();
            m.requirementsGate((name, requires, by) -> gate(approvals, base, name, requires, by));
            approvals.onDecision(r -> {
                if (r.subject().startsWith("connector:")) {
                    Thread.ofVirtual().name("compute-recheck").start(m::recheckRequirements);
                }
            });
        }
        return service;
    }

    /** A connector's requirements: approved, or why it stays off (filing the request for approval on the way). */
    static Optional<String> gate(Approvals approvals, Path base, String name, java.util.Map<String, Object> requires, String by) {
        Requires r;
        List<ApprovalItem> items;
        try {
            r = Requires.parse(requires, name + ".yaml");
            items = r.items(base);
        } catch (RuntimeException e) {
            return Optional.of("its requires: block is not usable: " + e.getMessage());
        }
        if (items.isEmpty()) {
            return Optional.empty();
        }
        Approvals.Status s = approvals.request("connector:" + name, items, by);
        return s.approved() ? Optional.empty() : Optional.of(s.words());
    }
}
