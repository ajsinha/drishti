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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.cluster.ClusterRoute;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.functions.Sketches;
import com.ash.drishti.bicompute.rollup.AggregateAwareness;
import com.ash.drishti.bicompute.rollup.RollupDef;
import com.ash.drishti.bicompute.rollup.RollupSource;
import com.ash.drishti.bicompute.rollup.Rollups;
import com.ash.drishti.bicompute.rollup.TabularRollupSource;
import com.ash.drishti.bicompute.scale.Progressive;
import com.ash.drishti.bicompute.scale.ScalePolicy;
import com.ash.drishti.bicompute.scale.ScanRates;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.sql.SampledResolver;
import com.ash.drishti.bicompute.sql.TabularProvider;
import com.ash.drishti.server.net.PeerCalls;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The pivot engine's scale (PIVOT_GUIDE.md, scale; PIVOT_ENGINE.md section 9): the sketches' settings, exact or ≈ by size and request, the
 * rollups of partial states with their refresh on load (each rollup's source version checked every {@code refresh-seconds}; a new version
 * merges the new files' states), aggregate awareness, the cluster's scatter and gather of partial states over Drishti*net, sampled first
 * answers and measured scan rates; all as hooks on the one {@link PivotCore}. Nothing here aggregates.
 */
@Configuration
@EnableConfigurationProperties(PivotScaleProperties.class)
public class PivotScaleConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PivotScaleConfiguration.class);

    /** The identity rollups read as: every row (a rollup answers only a person who reads every row and field). */
    static final Principal SYSTEM = new Principal("system:pivot-rollups", List.of("admin"));

    /** The scale hooks, as the controllers need them. */
    public record Scale(Rollups rollups, AggregateAwareness awareness, Progressive progressive, ScanRates rates, ClusterRoute cluster,
            Map<String, RollupSource> parted, ScheduledExecutorService timer) implements AutoCloseable {
        @Override
        public void close() {
            timer.shutdownNow();
            rollups.close();
        }
    }

    @Bean(destroyMethod = "close")
    public Scale pivotScale(PivotCore core, PivotScaleProperties props, ObjectProvider<PeerCalls> peerCalls, ObjectMapper json, Environment env) {
        Sketches.settings(props.sketchSettings());
        Map<String, RollupSource> sources = new LinkedHashMap<>();
        for (PivotProvider p : core.providers()) {
            if (p instanceof TabularProvider tp) {
                sources.put(p.id(), new TabularRollupSource(tp, new Viewer(SYSTEM.user(), SYSTEM)));
            }
        }
        String dir = props.rollups().dir();
        Path rollupDir = dir == null || dir.isBlank() ? Path.of(env.getProperty("drishti.data.dir", "./data"), "pivot", "rollups") : Path.of(dir);
        Rollups rollups = new Rollups(sources, rollupDir);
        for (Map<String, Object> d : props.definitions()) {
            try {
                rollups.define(RollupDef.parse(d));
            } catch (RuntimeException e) {
                LOG.warn("pivot rollup {} not defined: {}", d.get("id"), e.getMessage());
            }
        }
        AggregateAwareness awareness = new AggregateAwareness(rollups, props.adviseAfterScans());
        PeerCalls calls = peerCalls.getIfAvailable();
        ClusterRoute cluster = new ClusterRoute(peers(calls, json), props.clusterSettings(), sources, Executors.newVirtualThreadPerTaskExecutor());
        ScanRates rates = new ScanRates(cluster::nodes);
        core.hooks(new ScalePolicy(props.approximateAboveRows()));
        if (props.rollupsEnabled()) {
            core.hooks(awareness);                           // rollups first: a covering rollup answers before any node is asked
        }
        core.hooks(cluster);
        core.hooks(SampledResolver.hook());
        core.hooks(rates);
        Progressive progressive = new Progressive(core, props.progressiveSettings());
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drishti-pivot-rollup-check");
            t.setDaemon(true);
            return t;
        });
        if (props.rollupsEnabled()) {
            int every = props.refreshSeconds();
            timer.scheduleWithFixedDelay(() -> {
                try {
                    rollups.refreshAllLater();
                } catch (RuntimeException e) {
                    LOG.debug("pivot rollup check: {}", e.getMessage());
                }
            }, every, every, java.util.concurrent.TimeUnit.SECONDS);
        }
        return new Scale(rollups, awareness, progressive, rates, cluster, sources, timer);
    }

    /** The cluster as the route sees it: the live members (this one included, sorted), asked over {@code POST /api/v1/net/pivot/partials}. */
    static ClusterRoute.Peers peers(PeerCalls calls, ObjectMapper json) {
        return new ClusterRoute.Peers() {
            @Override
            public String self() {
                return calls == null ? "" : calls.self();
            }

            @Override
            public List<String> nodes() {
                if (calls == null || !calls.clustered()) {
                    return List.of();
                }
                List<String> all = new ArrayList<>(calls.peers());
                all.add(calls.self());
                all.sort(String::compareTo);
                return all;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> ask(Viewer v, String node, Map<String, Object> request, long timeoutMs) throws Exception {
                if (!(v.principal() instanceof Principal p)) {
                    throw new IllegalStateException("no person to ask node " + node + " for");
                }
                byte[] body = json.writeValueAsBytes(request);
                PeerCalls.Answer a = calls.call(node, "POST", "/api/v1/net/pivot/partials", body, p, Duration.ofMillis(timeoutMs));
                if (a.status() != 200) {
                    throw new IllegalStateException("node " + node + " answered " + a.status() + ": " + (a.body().length() > 300
                            ? a.body().substring(0, 300) : a.body()));
                }
                return json.readValue(a.body().getBytes(StandardCharsets.UTF_8), Map.class);
            }
        };
    }
}
