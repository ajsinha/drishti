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
import com.ash.drishti.compute.reactive.ReactiveHub;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Reactive connectors' wiring (REACTIVE_CONNECTORS.md section 16): installs the server's {@link ReactiveHostImpl} in the shared
 * {@link ReactiveHub} the {@code reactive} connector plugin finds (connectors are made by the plugin loader with no arguments, and ask for
 * the host only when they compute), keeps sandboxes under {@code <data dir>/compute/sandboxes}, and forgets old request events every
 * minute. Engines that are not Drishti's own run in the server's process only with {@code compute.in-process-java: true} in
 * {@code config/compute/compute.yaml}.
 */
@Configuration(proxyBeanMethods = false)
public class ReactiveConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ReactiveConfiguration.class);

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService reactiveSweeper(SourceRouter router, Entitlements entitlements, BusinessDates dates, ComputeService compute,
            ObjectProvider<AuditLog> audit, Environment env) {
        boolean inProcess = inProcessJava(ComputeConfiguration.file(env));
        ReactiveHub.get().install(new ReactiveHostImpl(router, entitlements, dates, compute, audit.getIfAvailable(), inProcess),
                Path.of(env.getProperty("drishti.data.dir", "./data")));
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("reactive-sweep").factory());
        s.scheduleWithFixedDelay(() -> {
            try {
                ReactiveHub.get().sweep();
            } catch (RuntimeException e) {
                LOG.debug("reactive: sweep failed once: {}", e.toString());
            }
        }, 1, 1, TimeUnit.MINUTES);
        LOG.info("reactive connectors: host installed ({} running; in-process Java engines other than Drishti's own: {})",
                ReactiveHub.get().connectors().size(), inProcess ? "allowed" : "refused");
        return s;
    }

    /** {@code compute.in-process-java} of the compute file (false when it is absent or unreadable). */
    @SuppressWarnings("unchecked")
    static boolean inProcessJava(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            Map<String, Object> doc = new ObjectMapper(new YAMLFactory()).readValue(file.toFile(), Map.class);
            Object c = doc == null ? null : doc.get("compute");
            return c instanceof Map<?, ?> m && "true".equalsIgnoreCase(String.valueOf(((Map<String, Object>) m).get("in-process-java")).trim());
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }
}
