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
package com.ash.drishti.server.flight;

import com.ash.drishti.flight.FlightLimits;
import com.ash.drishti.flight.FlightService;
import com.ash.drishti.flight.FlightSettings;
import com.ash.drishti.flight.FlightSqlCatalog;
import com.ash.drishti.identity.ApiTokenStore;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.memlake.MemoryLake;
import com.ash.drishti.server.admission.AdmissionGate;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.mq.ModelCatalog;
import com.ash.drishti.server.bi.mq.MqService;
import com.ash.drishti.server.data.DataViewController;
import com.ash.drishti.server.engines.BasicEngine;
import com.ash.drishti.server.engines.EngineSwitches;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.pivot.PivotController;
import com.ash.drishti.server.security.Entitlements;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * The server's wiring of Arrow Flight (the plain-Java module {@code drishti-flight}; docs/guides/FLIGHT_GUIDE.md): the settings from
 * {@code config/engines/flight.yaml}, the backend over the existing services, the service started when the server is ready and the engine
 * {@code flight} is on (off as shipped; turning it on needs a restart: the port opens at start), its Arrow memory joined to busy
 * protection, and Admin → Data out.
 */
@Configuration
public class FlightConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FlightConfiguration.class);
    private static final String P = "drishti.engines.flight.";

    /** The settings, from the engine file (every key in CONFIGURATION.md). */
    @Bean
    public FlightSettings flightSettings(Environment env, ObjectProvider<EngineSwitches> switches) {
        EngineSwitches sw = switches.getIfAvailable();
        boolean on = sw == null ? env.getProperty(P + "enabled", Boolean.class, false) : sw.of("flight").on();
        String cert = env.getProperty(P + "tls.certificate", "");
        String key = env.getProperty(P + "tls.private-key", "");
        String ca = env.getProperty(P + "tls.client-ca", "");
        Map<String, FlightLimits> roles = new LinkedHashMap<>();
        String rolesKey = P + "limits.roles";
        org.springframework.boot.context.properties.bind.Binder.get(env).bind(rolesKey,
                org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, Map.class)).ifBound(m -> m.forEach((role, v) ->
                        roles.put(role, limits(v))));
        Map<?, ?> def = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind(P + "limits.default", org.springframework.boot.context.properties.bind.Bindable.of(Map.class)).orElse(Map.of());
        return new FlightSettings(on, env.getProperty(P + "bind", "0.0.0.0"), env.getProperty(P + "port", Integer.class, FlightSettings.DEFAULT_PORT),
                env.getProperty(P + "advertise", ""), cert.isBlank() ? null : Path.of(cert), key.isBlank() ? null : Path.of(key),
                ca.isBlank() ? null : Path.of(ca), env.getProperty(P + "memory.total-mb", Long.class, 4096L) << 20,
                env.getProperty(P + "memory.request-mb", Long.class, 1024L) << 20, env.getProperty(P + "batch-rows", Integer.class, 65_536),
                Duration.ofMinutes(env.getProperty(P + "session-minutes", Long.class, 60L)), env.getProperty(P + "stream-keep", Integer.class, 1024),
                Duration.ofHours(env.getProperty(P + "readers-hours", Long.class, 24L)), def.isEmpty() ? FlightLimits.DEFAULT : limits(def), roles);
    }

    /** {@code { rows, gb, minutes, memory-mb }} (0: no limit). */
    static FlightLimits limits(Map<?, ?> m) {
        return new FlightLimits(num(m, "rows", 50_000_000L), num(m, "gb", 8L) << 30, Duration.ofMinutes(num(m, "minutes", 10L)),
                num(m, "memory-mb", 512L) << 20);
    }

    private static long num(Map<?, ?> m, String k, long fallback) {
        Object v = m.get(k);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(P + "limits: " + k + " is a whole number, not '" + v + "'");
        }
    }

    @Bean
    public FlightNodes flightNodes(FlightSettings settings, ObjectProvider<NetService> net) {
        return new FlightNodes(net.getIfAvailable(), settings.advertiseHost(), settings.port());
    }

    @Bean
    public ServerFlightBackend flightBackend(FlightSettings settings, FlightNodes nodes, ApiTokenStore tokens, UserService users,
            ObjectProvider<AuditLog> audit, ObjectProvider<AdmissionGate> gate, ObjectProvider<DataViewController> data,
            ObjectProvider<ModelCatalog> catalog, ObjectProvider<MqService> mq, ObjectProvider<BiService> bi, ObjectProvider<PivotController> pivot,
            ObjectProvider<com.ash.drishti.server.bi.sql.SqlService> sql,
            ObjectProvider<MemoryLake> memory, ObjectProvider<FlightForm> measures,
            ObjectProvider<com.ash.drishti.server.embed.EmbedTokenService> embed) {
        List<FlightForm> forms = new ArrayList<>(measures.orderedStream().toList());                 // the engines' own forms (VaR's measure form)
        DataViewController dv = data.getIfAvailable();
        if (dv != null) {
            KindFlightForm kinds = new KindFlightForm(dv);
            forms.add(kinds);
            forms.add(new LakeFlightForm(com.ash.drishti.lakeconfig.Lakes::current, kinds, dv, memory.getIfAvailable()));   // the lake files (DATA_ARCHITECTURE.md section 8)
        }
        if (catalog.getIfAvailable() != null && mq.getIfAvailable() != null && bi.getIfAvailable() != null) {
            forms.add(new AnswerFlightForms.Model(catalog.getObject(), mq.getObject(), bi.getObject(), sql.getIfAvailable()));
        }
        PivotController pc = pivot.getIfAvailable();
        if (pc != null) {
            forms.add(new AnswerFlightForms.Pivot(pc));
        }
        return new ServerFlightBackend(tokens, users, audit.getIfAvailable(), gate.getIfAvailable(), nodes, forms, embed.getIfAvailable())
                .batchRows(settings.batchRows());
    }

    @Bean(destroyMethod = "close")
    public FlightService flightService(FlightSettings settings, ServerFlightBackend backend, ObjectProvider<ModelCatalog> catalog,
            ObjectProvider<BiService> bi, ObjectProvider<com.ash.drishti.server.bi.sql.SqlService> sql) {
        // Flight SQL on the same port: read-only DSQL over the BI models, answered as a model read (FLIGHT_GUIDE.md, Flight SQL)
        FlightSqlCatalog models = catalog.getIfAvailable() != null && bi.getIfAvailable() != null && sql.getIfAvailable() != null
                ? new ServerFlightSqlCatalog(catalog.getObject(), bi.getObject()) : null;
        FlightService service = new FlightService(settings, backend, Clock.systemUTC(), models);
        backend.nodes().port(service::port);                             // the endpoints name the port in use (0 in the file: any free one)
        return service;
    }

    /** Arrow Flight as a compute engine (ARCHITECTURE.md section 25): off stops the port live; on needs a restart. */
    @Bean
    public BasicEngine flightComputeEngine(FlightService service) {
        return new BasicEngine("flight").onStop(service::close).memory(() -> service.memory()[0]).threads("flight-");
    }

    @Bean
    public DataOutController dataOutController(FlightService service, ServerFlightBackend backend, Entitlements entitlements,
            ObjectProvider<AuditLog> audit, ObjectProvider<ApiTokenStore> tokens) {
        return new DataOutController(service, backend, entitlements, audit.getIfAvailable(), tokens.getIfAvailable());
    }

    private static volatile String startProblem;

    /** Why the port did not open at start, or null. */
    static String startProblem() {
        return startProblem;
    }

    /** When the server is ready: the port opens (the engine on), and Arrow memory joins busy protection. */
    @EventListener(ApplicationReadyEvent.class)
    public void start(ApplicationReadyEvent e) {
        var ctx = e.getApplicationContext();
        FlightSettings settings = ctx.getBean(FlightSettings.class);
        if (!settings.enabled()) {
            return;
        }
        FlightService service = ctx.getBean(FlightService.class);
        ctx.getBeanProvider(AdmissionGate.class).ifAvailable(g -> g.memoryStore("arrow-flight", () -> service.memory()[0], () -> service.memory()[1]));
        try {
            service.start();
        } catch (IOException | RuntimeException ex) {
            // the server keeps serving; Admin -> Data out and Health say why the port is closed
            log.error("Arrow Flight did not start: {}", ex.getMessage());
            startProblem = ex.getMessage();
        }
    }
}
