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
package com.ash.drishti.server.enginehost;

import com.ash.drishti.api.Roots;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.enginehost.EngineHost;
import com.ash.drishti.enginehost.EngineHostSettings;
import com.ash.drishti.enginehost.HostedEngine;
import com.ash.drishti.enginehost.KindContracts;
import com.ash.drishti.enginehost.LakeOutputStore;
import com.ash.drishti.enginehost.Outputs;
import com.ash.drishti.enginehost.PackUses;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.engines.EngineCatalogue;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.engine.Health;
import com.ash.engine.LifecycleState;
import com.ash.engine.host.HealthBoard;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.support.CronExpression;

/**
 * Drishti hosts engines (docs/architecture/ENGINES_IN_DRISHTI.md): the {@link EngineHost} built over the server's router, access rules,
 * EL compiler, packs and audit log; started when the server is ready (the connectors are up, so binding by contract sees the kinds served);
 * each engine with an engine file attached to the one engine registry, so Admin -> Engines, the switch, the API gate and the cluster's view
 * treat it as they treat every engine; the {@code engineHost} health entry; the {@code drishti_lifecycle_*} series of each engine
 * (docs/architecture/LIFECYCLE.md section 7: {@code type=engine}).
 */
@Configuration
public class EngineHostConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(EngineHostConfiguration.class);

    /** The host rules that turn a caller into a visibility ({@code access.visibility}): the server's row rules and masks. */
    public static final Set<String> VISIBILITY_RULES = Set.of("row-rules");

    @Bean(destroyMethod = "close")
    public EngineHost engineHost(ConfigurableEnvironment env, SourceRouter router, PackRegistry packs, EngineCatalogue catalogue,
            ObjectProvider<AuditLog> audit, ObjectProvider<EngineRegistry> registry) {
        Path home = Roots.home();
        Path config = Path.of(env.getProperty(Roots.CONFIG, Roots.config()));
        Path data = Path.of(env.getProperty("drishti.data.dir", Roots.data()));
        EngineHostSettings settings = EngineHostSettings.of(env::getProperty, home, config, data);
        Map<String, Map<String, Object>> manifests = new TreeMap<>();
        for (Pack p : packs.packs()) {
            manifests.put(p.name(), p.manifest());
        }
        AuditLog log = audit.getIfAvailable();
        HostedEngine.Auditor auditor = log == null ? null : log::record;
        Outputs outputs = new Outputs(new LakeOutputStore(env::resolveRequiredPlaceholders, env.getProperty("drishti.engine-lib.store-format", "delta")));
        EngineHost.Ports ports = new EngineHost.Ports(new RouterKindData(router, Duration.ofSeconds(env.getProperty("drishti.engine-lib.read-seconds",
                Long.class, 60L))), KindContracts.of(manifests), PackUses.of(manifests), new ElWhere(), outputs, auditor, VISIBILITY_RULES,
                env::resolvePlaceholders, id -> catalogue.get(id).isPresent() || registry.getIfAvailable() != null && registry.getIfAvailable().known(id),
                EngineHostConfiguration::nextCron);
        return new EngineHost(settings, EngineHost.class.getClassLoader(), ports);
    }

    static Instant nextCron(String cron, String zone, Instant now) {
        ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.ofInstant(now, ZoneId.of(zone)));
        return next == null ? null : next.toInstant();
    }

    /**
     * When the server is ready: the lib folder read, the engines started, each engine with an engine file attached to the registry (its
     * switch read from the file, as a site's engine file is), and its metrics registered.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startEngines(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        EngineHost host = ctx.getBean(EngineHost.class);
        ConfigurableEnvironment env = ctx.getBean(ConfigurableEnvironment.class);
        try {
            host.start();
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("engines: the engine lib folder {} could not be read: {}; no hosted engine runs", host.settings().libPath(), e.toString());
            return;
        }
        PackUses uses = PackUses.of(manifests(ctx.getBean(PackRegistry.class)));
        EngineRegistry registry = ctx.getBean(EngineRegistry.class);
        for (HostedEngine h : host.engines()) {
            if (h.file() == null && h.problems().isEmpty() || h.descriptor() == null) {
                continue;                                    // not configured, or not installed: listed by the host's own view
            }
            Path file = host.settings().enginesDir().resolve(h.id() + ".yaml");
            env.getPropertySources().addLast(new MapPropertySource("Config resource 'file [" + file + "]' via the engine host",
                    Map.<String, Object>of("drishti.engines." + h.id() + ".enabled", h.enabled())));
            try {
                registry.attach(new HostedComputeEngine(host, h), entry(h, uses));
            } catch (RuntimeException e) {
                LOG.warn("engines: {} could not be listed with the engine switches: {}", h.id(), e.getMessage());
            }
        }
        ctx.getBeanProvider(MeterRegistry.class).ifAvailable(m -> host.engines().forEach(h -> metrics(m, host, h)));
    }

    private static Map<String, Map<String, Object>> manifests(PackRegistry packs) {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        packs.packs().forEach(p -> out.put(p.name(), p.manifest()));
        return out;
    }

    /** The switch page's entry of a hosted engine: drawn from its descriptor; what turning it off hides is what the packs name of it. */
    static EngineCatalogue.Entry entry(HostedEngine h, PackUses uses) {
        Map<String, List<String>> lists = new LinkedHashMap<>();
        lists.put("api", List.of("/api/v1/engines/" + h.id()));
        List<String> words = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        uses.of(h.id()).forEach(u -> u.as().values().forEach(a -> {
            if (a.get("mnemonic") != null) {
                words.add(a.get("mnemonic"));
            }
            if (a.get("kind") != null) {
                kinds.add(a.get("kind"));
            }
        }));
        if (!words.isEmpty()) {
            lists.put("words", words);
        }
        if (!kinds.isEmpty()) {
            lists.put("kinds", kinds);
        }
        String title = h.descriptor().title() + " " + h.descriptor().version();
        return new EngineCatalogue.Entry(h.id(), title, h.descriptor().about() == null ? "" : h.descriptor().about(), h.id() + ".yaml",
                "drishti.engines." + h.id() + ".enabled", "", List.of(), h.enabled(), true, true, lists);
    }

    /** One engine's series, tagged {@code type=engine,name=<id>}: state, health, counters and latencies, read from the last poll. */
    static void metrics(MeterRegistry m, EngineHost host, HostedEngine h) {
        Tags tags = Tags.of("type", "engine", "name", h.id());
        Gauge.builder("drishti_lifecycle_state", h, x -> state(x.lifecycle())).tags(tags).register(m);
        Gauge.builder("drishti_lifecycle_health", host, x -> health(x, h.id())).tags(tags).register(m);
        counter(m, "drishti_lifecycle_requests", host, h, tags, e -> e.stats().sinceStart().asked());
        counter(m, "drishti_lifecycle_errors", host, h, tags, e -> e.stats().sinceStart().failed());
        counter(m, "drishti_lifecycle_refusals", host, h, tags, e -> e.stats().sinceStart().refused());
        counter(m, "drishti_lifecycle_rows_written", host, h, tags, e -> e.stats().sinceStart().rowsOut());
        counter(m, "drishti_lifecycle_cache_hits", host, h, tags, e -> e.stats().sinceStart().cacheHits());
        FunctionCounter.builder("drishti_lifecycle_rows_read", h, HostedEngine::rowsDelivered).tags(tags).register(m);
        FunctionCounter.builder("drishti_lifecycle_host_refusals", h, HostedEngine::refusedByHost).tags(tags).register(m);
        Gauge.builder("drishti_lifecycle_latency_p50", host, x -> nanos(x, h.id(), 50)).tags(tags).baseUnit("milliseconds").register(m);
        Gauge.builder("drishti_lifecycle_latency_p95", host, x -> nanos(x, h.id(), 95)).tags(tags).baseUnit("milliseconds").register(m);
        Gauge.builder("drishti_lifecycle_latency_p99", host, x -> nanos(x, h.id(), 99)).tags(tags).baseUnit("milliseconds").register(m);
        Gauge.builder("drishti_lifecycle_in_flight", host, x -> last(x, h.id()).map(e -> (double) e.stats().sinceStart().inFlight()).orElse(0d))
                .tags(tags).register(m);
    }

    private static void counter(MeterRegistry m, String name, EngineHost host, HostedEngine h, Tags tags, ToDoubleFunction<HealthBoard.Entry> f) {
        FunctionCounter.builder(name, host, x -> last(x, h.id()).map(f::applyAsDouble).orElse(0d)).tags(tags).register(m);
    }

    private static java.util.Optional<HealthBoard.Entry> last(EngineHost host, String id) {
        return host.report().engines().stream().filter(e -> e.engineId().equals(id)).findFirst();
    }

    private static double nanos(EngineHost host, String id, int p) {
        return last(host, id).map(e -> {
            var c = e.stats().sinceStart();
            long n = p == 50 ? c.p50Nanos() : p == 95 ? c.p95Nanos() : c.p99Nanos();
            return n / 1e6;
        }).orElse(0d);
    }

    /** LIFECYCLE.md section 7: 0 new, 1 initialized, 2 running, 3 suspended, 4 failed, 5 shut down. */
    static double state(LifecycleState s) {
        return switch (s) {
            case NEW -> 0;
            case INITIALISED -> 1;
            case RUNNING -> 2;
            case SUSPENDED -> 3;
            case FAILED -> 4;
            case SHUTDOWN -> 5;
        };
    }

    /** LIFECYCLE.md section 7: 0 up, 1 degraded, 2 suspended, 3 down. */
    static double health(EngineHost host, String id) {
        Health.State s = last(host, id).map(e -> e.health().state()).orElse(Health.State.DOWN);
        return switch (s) {
            case UP -> 0;
            case DEGRADED -> 1;
            case SUSPENDED -> 2;
            case DOWN -> 3;
        };
    }

    /**
     * {@code /actuator/health}'s {@code engineHost}: every hosted engine's state and health with its words, the JARs, the packs' uses that do
     * not hold, misplaced JARs. Always UP: an engine that is off, blocked or suspended is a site's choice or a fact to fix, never the server's
     * fault (the optional-externals rule).
     */
    @Bean
    public HealthIndicator engineHostHealthIndicator(EngineHost host, ConfigurableEnvironment env) {
        return () -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("platform", host.platform());
            for (HostedEngine h : host.engines()) {
                Health hh = h.health();
                d.put(h.id(), h.state() + (h.words().isBlank() ? "" : ": " + h.words()) + " | " + hh.state() + (hh.reasons().isEmpty() ? "" : " " + hh.reasons()));
            }
            if (!host.packNotes().isEmpty()) {
                d.put("packs", host.packNotes());
            }
            Path shared = Path.of(env.getProperty("drishti.lib.path", Roots.home(com.ash.drishti.common.SharedLib.DEFAULT)));
            List<String> misplaced = EngineHost.misplaced(shared.isAbsolute() ? shared : Roots.home().resolve(shared), host.settings().libPath());
            if (!misplaced.isEmpty()) {
                d.put("misplaced", misplaced);
            }
            return org.springframework.boot.actuate.health.Health.up().withDetails(d).build();
        };
    }
}
