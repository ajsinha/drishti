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
package com.ash.drishti.server.metrics;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.server.caches.CacheBoard;
import com.ash.drishti.server.caches.MemoryBudget;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.disk.DiskBudget;
import com.ash.drishti.server.loads.ExpectationService;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.server.net.NetService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * The platform metrics and alerts (OPERATIONS_GUIDE.md section 13.6): the gauges, the rules' evaluator, and the one thread that samples and
 * evaluates every {@code drishti.metrics.sample-interval} once the server is ready.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetricsProperties.class)
public class MetricsConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsConfiguration.class);
    /** The inbox row's kind for a platform alert, and the page it opens. */
    static final String KIND = "platform";
    static final String LINK = "/admin/health#alerts";
    private static final int INBOX_LINE = 280;

    @Bean
    public PlatformMetrics platformMetrics(MeterRegistry registry, ObjectProvider<CacheBoard> caches, ObjectProvider<MemoryBudget> memory,
            ObjectProvider<DiskBudget> disk, SourceRegistry sources, ObjectProvider<ExpectationService> expectations,
            ObjectProvider<LoadService> loads) {
        PlatformMetrics m = new PlatformMetrics(registry, caches::getIfAvailable, memory::getIfAvailable, disk::getIfAvailable, sources,
                expectations::getIfAvailable, loads::getIfAvailable, Clock.systemUTC());
        loads.ifAvailable(l -> l.onReady(m::loadReady));
        return m;
    }

    @Bean
    public PlatformAlerts platformAlerts(MeterRegistry registry, MetricsProperties props, ObjectProvider<NetService> net,
            ObjectProvider<UserService> users, ObjectProvider<InboxService> inbox, ObjectProvider<InboxHub> hub) {
        String member = memberName(net.getIfAvailable());
        return new PlatformAlerts(registry, props.alerts(), member, Clock.systemUTC(), a -> tell(props.alerts(), users, inbox, hub, a));
    }

    /** The sampling thread: no bean (a ScheduledExecutorService bean would take the place of Spring's own task scheduler). */
    private ScheduledExecutorService sampler;

    /** Starts the sampling once everything is up (the caches and engines have joined their budgets). */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start(ApplicationReadyEvent e) {
        var ctx = e.getApplicationContext();
        MetricsProperties props = ctx.getBean(MetricsProperties.class);
        if (!props.enabled() || sampler != null) {
            return;
        }
        PlatformMetrics metrics = ctx.getBean(PlatformMetrics.class);
        PlatformAlerts alerts = ctx.getBean(PlatformAlerts.class);
        long every = props.sampleInterval().toMillis();
        long disk = props.diskInterval().toMillis();
        sampler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("drishti-metrics").factory());
        sampler.scheduleWithFixedDelay(() -> {
            try {
                metrics.sample(disk);
                alerts.evaluate();
            } catch (RuntimeException ex) {
                LOG.warn("platform metrics: a sample failed: {}", ex.toString());
            }
        }, 0, every, TimeUnit.MILLISECONDS);
    }

    @jakarta.annotation.PreDestroy
    public synchronized void stop() {
        if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
    }

    /** The member's name in a cluster, else this machine's host name. */
    static String memberName(NetService net) {
        String id = net == null ? null : net.node().map(NetService.Node::id).orElse(null);
        if (id != null && !id.isBlank()) {
            return id;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException | RuntimeException e) {
            return "this server";
        }
    }

    /** An inbox row (and the bell) for every enabled user with one of the notify roles. */
    static void tell(MetricsProperties.Alerts props, ObjectProvider<UserService> users, ObjectProvider<InboxService> inbox,
            ObjectProvider<InboxHub> hub, PlatformAlerts.Alert a) {
        UserService us = users.getIfAvailable();
        InboxService in = inbox.getIfAvailable();
        if (us == null || in == null || props.notifyRoles().isEmpty()) {
            return;
        }
        String line = "[" + a.severity() + "] " + a.member() + ": " + a.summary();
        line = line.length() > INBOX_LINE ? line.substring(0, INBOX_LINE - 1) + "…" : line;
        for (User u : us.list("")) {
            if (u.enabled() && u.roles().stream().anyMatch(props.notifyRoles()::contains)) {
                Notice stored = in.add(new Notice(0, u.username(), a.openedAt(), "alert", KIND, LINK, line, null, null, null, "drishti", null));
                InboxHub h = hub.getIfAvailable();
                if (h != null) {
                    h.publish(stored);
                }
            }
        }
    }
}
