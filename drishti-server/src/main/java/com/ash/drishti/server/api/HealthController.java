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
package com.ash.drishti.server.api;

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.engine.live.LiveMetrics;
import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * The health of everything Drishti depends on, in one answer (admins): each connector's state, traffic, latency and
 * last error; each pack's connectors and Sutras (loaded and failing); live streaming; the server itself. An overall
 * status says OK, DEGRADED (something is down or broken) or DOWN (nothing can serve). Cheap to call every few seconds.
 */
@RestController
public class HealthController {

    private final SourceRegistry registry;
    private final SourceRouter router;
    private final PackRegistry packs;
    private final SutraRegistry sutras;
    private final LiveMetrics live;
    private final TopicHub hub;
    private final LiveStreamSlots slots;
    private final Entitlements entitlements;
    private final String version;
    private final List<String> overrides;

    public HealthController(SourceRegistry registry, SourceRouter router, PackRegistry packs, SutraRegistry sutras, LiveMetrics live,
            TopicHub hub, LiveStreamSlots slots, Entitlements entitlements, ObjectProvider<BuildProperties> build,
            org.springframework.core.env.Environment env) {
        this.overrides = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("drishti.packs.overrides", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class)).orElse(List.of());
        this.registry = registry;
        this.router = router;
        this.packs = packs;
        this.sutras = sutras;
        this.live = live;
        this.hub = hub;
        this.slots = slots;
        this.entitlements = entitlements;
        this.version = build.getIfAvailable() == null ? "dev" : build.getIfAvailable().getVersion();
    }

    @GetMapping("/api/v1/admin/health")
    public Map<String, Object> health(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> sources = new ArrayList<>();
        int down = 0;
        for (SourcePlugin s : registry.plugins()) {
            var m = s.manifest();
            Map<String, Object> row = new LinkedHashMap<>();
            String health = safeHealth(s);
            boolean up = health.startsWith("UP");
            down += up ? 0 : 1;
            row.put("name", m.name());
            row.put("version", m.version());
            row.put("status", up ? "UP" : "DOWN");
            row.put("health", health);
            row.put("kinds", m.kinds().stream().sorted().toList());
            row.put("live", m.capabilities().live());
            row.put("dated", m.capabilities().dated());
            row.put("search", m.capabilities().search());
            row.put("reads", router.stats().snapshot(m.name()));
            try {
                row.put("cache", s.cacheStats());
            } catch (RuntimeException e) {
                row.put("cache", Map.of());
            }
            sources.add(row);
        }
        sources.sort((a, b) -> a.get("status").equals(b.get("status")) ? String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name")))
                : "DOWN".equals(a.get("status")) ? -1 : 1);                     // what needs attention first
        Map<String, String> failures = registry.failures();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> packRows = packs(sources);
        long packProblems = packRows.stream().filter(r -> !"OK".equals(r.get("status"))).count();
        String overall = sources.isEmpty() || down == sources.size() ? "DOWN" : down > 0 || !failures.isEmpty() || packProblems > 0 ? "DEGRADED" : "OK";
        out.put("status", overall);
        out.put("summary", Map.of("sources", sources.size(), "sourcesDown", down, "failedToStart", failures.size(), "packs", packRows.size(),
                "packsWithProblems", packProblems));
        out.put("server", server());
        out.put("sources", sources);
        out.put("failedToStart", failures);
        out.put("packs", packRows);
        out.put("overrides", overrides);
        out.put("live", Map.of("streams", slots.open(), "topics", hub.topicCount(), "frames", live.frames(), "droppedFrames", live.droppedFrames(),
                "p50Ms", live.percentile(50), "p99Ms", live.percentile(99)));
        return out;
    }

    private List<Map<String, Object>> packs(List<Map<String, Object>> sources) {
        Map<String, String> statusOf = new LinkedHashMap<>();
        sources.forEach(s -> statusOf.put(String.valueOf(s.get("name")), String.valueOf(s.get("status"))));
        Map<String, List<SutraProblem>> problems = sutras.problems();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pack pack : packs.packs()) {
            Path dir = pack.dir().resolve("sutras").normalize();
            List<Map<String, Object>> broken = new ArrayList<>();
            problems.forEach((file, ps) -> {
                if (Path.of(file).toAbsolutePath().normalize().startsWith(dir)) {
                    broken.add(Map.of("file", dir.relativize(Path.of(file).toAbsolutePath().normalize()).toString(),
                            "problems", ps.stream().map(x -> x.code() + " " + x.message()).toList()));
                }
            });
            List<String> connectors = new ArrayList<>();
            if (pack.manifest().get("connectors") instanceof Map<?, ?> c) {
                c.keySet().forEach(k -> connectors.add(String.valueOf(k)));
            }
            List<String> connectorsDown = connectors.stream().filter(c -> statusOf.containsKey(c) && !"UP".equals(statusOf.get(c))).toList();
            List<String> connectorsMissing = connectors.stream().filter(c -> !statusOf.containsKey(c)).toList();   // disabled or failed to start
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", pack.name());
            row.put("title", pack.title());
            row.put("version", pack.version());
            row.put("extends", pack.parents());
            row.put("overrides", overrides.stream().filter(o -> o.contains(": " + pack.name() + " overrides ")).toList());
            row.put("kinds", pack.kinds().size());
            row.put("sutras", countSutras(dir));
            row.put("sutraProblems", broken);
            row.put("connectors", connectors);
            row.put("connectorsDown", connectorsDown);
            row.put("connectorsOff", connectorsMissing);
            row.put("status", !broken.isEmpty() || !connectorsDown.isEmpty() ? "DEGRADED" : "OK");
            out.add(row);
        }
        return out;
    }

    private static long countSutras(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(f -> f.toString().endsWith(".sutra.yaml")).count();
        } catch (IOException e) {
            return -1;
        }
    }

    private static String safeHealth(SourcePlugin s) {
        try {
            String h = s.health();
            return h == null ? "UP" : h;
        } catch (RuntimeException e) {
            return "DOWN: " + e.getMessage();
        }
    }

    private Map<String, Object> server() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        m.put("java", System.getProperty("java.version"));
        m.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1_048_576);
        m.put("heapMaxMb", rt.maxMemory() / 1_048_576);
        m.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        m.put("cpus", rt.availableProcessors());
        return m;
    }
}
