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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.live.LivePivots;
import com.ash.drishti.bicompute.rollup.RollupDef;
import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.server.loads.LoadRecord;
import com.ash.drishti.server.loads.LoadService;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Live pivots wired to the server's existing change signals (ARCHITECTURE.md section 26.7, item 5; BI_GUIDE.md, live pivots); nothing
 * here computes a figure:
 *
 * <ul>
 *   <li><b>a data load</b>: {@link LoadService#onReady} (the same signal BI's layers rebuild on) touches the views of the load's kind for its
 *       business date (and the views that follow the latest date);</li>
 *   <li><b>a live table's update</b>: a panel source ({@code panel/<kind>/<panel>/<id>}) is fed by the {@link TopicHub}, the channel the
 *       Sutra live views stream from: one hub subscription per live panel while a live view reads it;</li>
 *   <li>before the touched views are computed again, the rollups of that source are refreshed for the date (their new files' states merged),
 *       so a view a rollup answers moves with the load;</li>
 *   <li>a heartbeat sends {@code status} to every stream, ends views nobody streams and drops hub subscriptions no live view needs.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(PivotLiveProperties.class)
public class PivotLiveConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PivotLiveConfiguration.class);

    /** The live pivots and the panels' hub subscriptions they hold. */
    public record Live(LivePivots pivots, PanelTopics topics, ScheduledExecutorService timer) implements AutoCloseable {
        @Override
        public void close() {
            timer.shutdownNow();
            topics.close();
            pivots.close();
        }
    }

    /** One hub subscription per live panel source. */
    public static final class PanelTopics implements AutoCloseable {
        private final TopicHub hub;
        private final Map<String, Subscription> open = new ConcurrentHashMap<>();

        PanelTopics(TopicHub hub) {
            this.hub = hub;
        }

        /** Feeds {@code source}'s updates to the live pivots (once, however many views read it). */
        void follow(String source, LivePivots pivots) {
            if (hub == null || source == null || !source.startsWith(PanelSources.PREFIX) || open.containsKey(source)) {
                return;
            }
            String[] parts = source.substring(PanelSources.PREFIX.length()).split("/", 3);
            if (parts.length < 3) {
                return;
            }
            open.computeIfAbsent(source, s -> hub.subscribe(EntityRef.of(parts[0], parts[2]), doc -> pivots.signal(LivePivots.Signal.live(s))));
        }

        /** Hub subscriptions no live view reads any more, closed. */
        void prune(java.util.Set<String> live) {
            open.entrySet().removeIf(e -> {
                if (live.contains(e.getKey())) {
                    return false;
                }
                e.getValue().close();
                return true;
            });
        }

        int size() {
            return open.size();
        }

        @Override
        public void close() {
            open.values().forEach(Subscription::close);
            open.clear();
        }
    }

    @Bean(destroyMethod = "close")
    public Live pivotLive(PivotCore core, PivotLiveProperties props, PivotScaleConfiguration.Scale scale, ObjectProvider<LoadService> loads,
            ObjectProvider<TopicHub> hub) {
        LivePivots pivots = new LivePivots(core, props.settings(), Clock.systemUTC());
        pivots.before(s -> {
            for (RollupDef d : scale.rollups().definitions()) {
                if (!d.source().equals(s.source())) {
                    continue;
                }
                try {
                    if (s.dates().isEmpty()) {
                        scale.rollups().refresh(d.id(), scale.rollups().sourceOf(d.source()).latestDate(d.source()));
                    } else {
                        for (String day : s.dates()) {
                            scale.rollups().refresh(d.id(), day);
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.debug("pivot live: rollup {} not refreshed for {}: {}", d.id(), s.detail(), e.getMessage());
                }
            }
        });
        LoadService ls = loads.getIfAvailable();
        if (ls != null) {
            ls.onReady(r -> {
                if (LoadRecord.READY.equals(r.status()) && r.kind() != null) {
                    pivots.signal(LivePivots.Signal.load(r.kind(), r.businessDate() == null ? null : r.businessDate().toString()));
                }
            });
        }
        PanelTopics topics = new PanelTopics(hub.getIfAvailable());
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drishti-pivot-live-heartbeat");
            t.setDaemon(true);
            return t;
        });
        int every = props.heartbeat();
        timer.scheduleWithFixedDelay(() -> {
            try {
                pivots.heartbeat();
                topics.prune(pivots.sources());
            } catch (RuntimeException e) {
                LOG.debug("pivot live heartbeat: {}", e.getMessage());
            }
        }, every, every, TimeUnit.SECONDS);
        return new Live(pivots, topics, timer);
    }
}
