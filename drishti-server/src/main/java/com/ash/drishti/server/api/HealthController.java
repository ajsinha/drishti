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

import com.ash.drishti.kernel.api.health.HealthFigures;
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
import com.ash.drishti.kernel.api.security.Principal;
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
    private final com.ash.drishti.server.loads.ExpectationService expectations;
    private final com.ash.drishti.server.connectors.ConnectorManager connectors;
    /** Per kind page times (set by Spring; absent in a test that makes the controller itself). */
    private PageTimes pageTimes;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void pageTimes(PageTimes times) {
        this.pageTimes = times;
    }

    private RequestActivity activity;

    /** The disk budget (2.9.7, O7): Health, Disk; absent in a test that makes the controller itself. */
    private com.ash.drishti.server.disk.DiskBudget disk;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void disk(com.ash.drishti.server.disk.DiskBudget d) {
        this.disk = d;
    }

    /** The native libraries (BLAS): Health, Native; absent in a test that makes the controller itself. */
    private com.ash.drishti.server.nativelib.NativeLibraries nativeLibraries;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void nativeLibraries(com.ash.drishti.server.nativelib.NativeLibraries n) {
        this.nativeLibraries = n;
    }

    /** Busy protection (OPERATIONS_GUIDE.md, "Busy protection"): its card on Health; absent in a test that makes the controller itself. */
    private com.ash.drishti.server.admission.AdmissionGate busy;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void busy(com.ash.drishti.server.admission.AdmissionGate g) {
        this.busy = g;
    }

    /** The in-memory lake (docs/architecture/DATA_ARCHITECTURE.md section 12): its stores, pinned tables and host checks on Health; absent in such a test. */
    private com.ash.drishti.memlake.MemoryLake memoryLake;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void memoryLake(com.ash.drishti.memlake.MemoryLake m) {
        this.memoryLake = m;
    }

    /** The platform alerts (OPERATIONS_GUIDE.md section 13.6): the open ones on Health; absent in a test that makes the controller itself. */
    private com.ash.drishti.server.metrics.PlatformAlerts alerts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void alerts(com.ash.drishti.server.metrics.PlatformAlerts a) {
        this.alerts = a;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void activity(RequestActivity a) {
        this.activity = a;
    }
    private final com.ash.drishti.server.bi.BiFolder biFolder;
    private final com.ash.drishti.server.RetryService retry;
    private final com.ash.drishti.server.design.HistoryDefinitions history;
    private final ObjectProvider<com.zaxxer.hikari.HikariDataSource> identityDataSource;
    private final com.ash.drishti.identity.IdentityProperties identity;
    private final com.ash.drishti.server.security.MaskCoverage masks;

    /** The modules' engines' figures (a module's beans); none in a test that makes the controller itself. */
    private java.util.List<HealthFigures> moduleFigures = java.util.List.of();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void moduleFigures(java.util.List<HealthFigures> figures) {
        this.moduleFigures = java.util.List.copyOf(figures);
    }

    /** The access registry, for the modules row; none in a test that makes the controller itself. */
    private com.ash.drishti.registry.AccessRegistry access;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void access(com.ash.drishti.registry.AccessRegistry access) {
        this.access = access;
    }

    public HealthController(SourceRegistry registry, SourceRouter router, PackRegistry packs, SutraRegistry sutras, LiveMetrics live,
            TopicHub hub, LiveStreamSlots slots, Entitlements entitlements, ObjectProvider<BuildProperties> build,
            org.springframework.core.env.Environment env, com.ash.drishti.server.loads.ExpectationService expectations, com.ash.drishti.server.connectors.ConnectorManager connectors,
            com.ash.drishti.server.bi.BiFolder biFolder, com.ash.drishti.server.RetryService retry,
            ObjectProvider<com.zaxxer.hikari.HikariDataSource> identityDataSource, com.ash.drishti.identity.IdentityProperties identity,
            com.ash.drishti.server.security.MaskCoverage masks, com.ash.drishti.server.design.HistoryDefinitions history) {
        this.history = history;
        this.masks = masks;
        this.identityDataSource = identityDataSource;
        this.identity = identity;
        this.biFolder = biFolder;
        this.retry = retry;
        this.expectations = expectations;
        this.connectors = connectors;
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
        List<String> poolsAttention = new ArrayList<>();
        int down = 0;
        int degraded = 0;
        int stale = 0;
        for (SourcePlugin s : registry.plugins()) {
            var m = s.manifest();
            Map<String, Object> row = new LinkedHashMap<>();
            String health = safeHealth(s);
            // "UP …"; "DEGRADED: …" (it serves, but some of its data cannot be read: the tables it names); else down
            String status = com.ash.drishti.engine.source.SourceHealth.of(health).name();
            down += "DOWN".equals(status) ? 1 : 0;
            degraded += "DEGRADED".equals(status) ? 1 : 0;
            row.put("name", m.name());
            row.put("version", m.version());
            row.put("status", status);
            row.put("health", health);
            row.put("kinds", m.kinds().stream().sorted().toList());
            row.put("live", m.capabilities().live());
            row.put("dated", m.capabilities().dated());
            row.put("search", m.capabilities().search());
            row.put("reads", router.stats().snapshot(m.name()));
            var fresh = registry.freshness(m.name());
            row.put("lastUpdate", fresh.lastUpdate());
            row.put("retry", registry.retryStatus(m.name()).map(r -> com.ash.drishti.common.RetryBoard.describe(r, java.time.Instant.now())).orElse(null));
            row.put("staleAfter", fresh.staleAfter() == null ? null : fresh.staleAfter().toString());
            row.put("stale", fresh.stale());
            stale += fresh.stale() ? 1 : 0;
            try {
                row.put("cache", s.cacheStats());
            } catch (RuntimeException e) {
                row.put("cache", Map.of());
            }
            Map<String, Object> pool = PoolReport.of(s);              // open, in use, idle, waiting, longest borrow; null for a source with no database
            row.put("pool", pool);
            if (PoolReport.needsAttention(pool)) {
                poolsAttention.add(m.name() + (((Number) pool.get("leaksDetected")).longValue() > 0 ? ": a connection was held past pool-leak-threshold (see the log)"
                        : ": every connection is in use and callers are waiting (raise pool-size, or find what holds connections)"));
            }
            sources.add(row);
        }
        List<String> attention = List.of("DOWN", "DEGRADED", "UP");                // what needs attention first
        sources.sort(java.util.Comparator.<Map<String, Object>>comparingInt(r -> attention.indexOf(String.valueOf(r.get("status"))))
                .thenComparing(r -> String.valueOf(r.get("name"))));
        Map<String, String> failures = registry.failures();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> packRows = packs(sources);
        long packProblems = packRows.stream().filter(r -> !"OK".equals(r.get("status"))).count();
        String hotReload = sutras.hotReload();          // STOPPED: the Sutra watcher ended, edits are not picked up until a restart
        List<com.ash.drishti.server.loads.ExpectationService.State> dataLate = expectations.attention(java.time.Instant.now());
        String overall = sources.isEmpty() || down == sources.size() ? "DOWN"
                : down > 0 || degraded > 0 || stale > 0 || !failures.isEmpty() || packProblems > 0 || !packs.problems().isEmpty() || !dataLate.isEmpty() || hotReload.startsWith("STOPPED") || !connectors.fileProblems().isEmpty() ? "DEGRADED"
                : "OK";   // stale: behind its stale-after; degraded: a connector that cannot read some of its data
        out.put("status", overall);
        out.put("summary", Map.of("sources", sources.size(), "sourcesDown", down, "sourcesDegraded", degraded, "failedToStart", failures.size(),
                "packs", packRows.size(), "packsWithProblems", packProblems));
        out.put("server", server());
        if (busy != null) {
            out.put("busy", busy.describe());                          // busy protection: the readings against their levels, the last refusals
        }
        if (disk != null) {
            Map<String, Object> d = disk.describe();                    // O7: every folder against its cap, free space against the guard
            out.put("disk", d);
            if ("red".equals(d.get("state")) && "OK".equals(out.get("status"))) {
                out.put("status", "DEGRADED");
            }
        }
        if (memoryLake != null && memoryLake.configured()) {
            Map<String, Object> m = memoryLake.health();                // amber: a store over 85% or a refused write; red: a pinned table empty,
            out.put("memoryLake", m);                                   // the direct-memory limit below the stores' caps
            if ("red".equals(m.get("state")) && "OK".equals(out.get("status"))) {
                out.put("status", "DEGRADED");
            }
        }
        if (alerts != null) {
            out.put("alerts", alerts.summary());                        // the platform alerts open on this member, and whether the rules were read
        }
        if (nativeLibraries != null) {
            Map<String, Object> blas = nativeLibraries.describe();       // auto fell back to Java: DEGRADED (same results, slower), never down
            out.put("native", Map.of("blas", blas));
            if ("DEGRADED".equals(blas.get("status")) && "OK".equals(out.get("status"))) {
                out.put("status", "DEGRADED");
            }
        }
        out.put("tmp", com.ash.drishti.common.TempFiles.info());
        out.put("externalLib", com.ash.drishti.common.ExternalLib.info());
        out.put("sources", sources);
        if (access != null) {
            out.put("modules", modules(access));                      // the thin jar: no code modules, and Health says so
        }
        for (HealthFigures f : moduleFigures) {                     // the modules' engines' figures (DOMAIN_MODULES.md J4)
            out.put(f.key(), f.value());
        }
        Map<String, Object> identityPool = PoolReport.identity(identityDataSource.getIfAvailable(), identity.sqlite()).map(com.ash.drishti.api.pool.PoolStats::toMap).orElse(null);
        out.put("identityPool", identityPool);                        // the identity database's pool (SQLite: embedded, exempt)
        if (PoolReport.needsAttention(identityPool)) {
            poolsAttention.add("identity: every connection is in use and callers are waiting, or a connection was held past leak-threshold");
        }
        out.put("poolsAttention", poolsAttention);
        out.put("failedToStart", failures);
        out.put("packs", packRows);
        Map<String, String> loadedNames = new LinkedHashMap<>();
        packs.problems().forEach((n, m) -> {
            if (packRows.stream().noneMatch(r -> n.equals(r.get("name")))) {
                loadedNames.put(n, m);                                          // a pack on disk that could not be loaded
            }
        });
        out.put("packsNotLoaded", loadedNames);
        out.put("masksMatchingNothing", masks.unmatched());              // DRS-6011: a redact entry that names no field of the loaded packs
        out.put("retry", retry.snapshot());
        out.put("sutras", Map.of("hotReload", hotReload, "problemFiles", sutras.problems().size()));
        out.put("dataLate", dataLate.stream().map(d -> Map.of("pack", d.pack(), "kind", d.kind(), "businessDate", d.businessDate().toString(), "state", d.state(),
                "by", d.by() + " " + d.zone(), "line", d.line())).toList());
        out.put("overrides", overrides);
        out.put("connectors", connectors.healthSummary());
        if (pageTimes != null) {
            out.put("pages", pageTimes.summary());             // per kind: views, p50, p95, p99 over ten minutes
        }
        if (activity != null) {
            out.put("activity", activity.lastMinutes(30));     // requests, average and slowest time, minute by minute
        }
        out.put("bi", Map.of("watch", biFolder.watchState(), "shippedItems", biFolder.items().size(), "problems", biFolder.problems().stream()
                .map(x -> Map.of("file", x.file(), "message", x.message(), "keptLastGood", x.keptLastGood())).toList()));
        out.put("live", Map.of("streams", slots.open(), "topics", hub.topicCount(), "frames", live.frames(), "droppedFrames", live.droppedFrames(),
                "p50Ms", live.percentile(50), "p99Ms", live.percentile(99)));
        if (net != null) {
            out.put("net", net.get());                         // Drishti*net: members alive and suspect, forwarded requests and failures
        }
        return out;
    }

    /** Drishti*net's part of the page (set by Spring; absent in a test that makes the controller itself). */
    private java.util.function.Supplier<Map<String, Object>> net;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void net(com.ash.drishti.server.net.NetHealth health) {
        this.net = health::summary;
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
            List<String> refs = pack.connectorRefs();
            List<String> connectorsDown = refs.stream().filter(c -> statusOf.containsKey(c) && !"UP".equals(statusOf.get(c))).toList();
            List<String> connectorsMissing = refs.stream().filter(c -> !statusOf.containsKey(c)).toList();   // disabled, failed to start, or not configured
            List<String> notConfigured = connectors.missingFor(pack);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", pack.name());
            row.put("title", pack.title());
            row.put("version", pack.version());
            row.put("extends", pack.parents());
            row.put("overrides", overrides.stream().filter(o -> o.contains(": " + pack.name() + " overrides ")).toList());
            row.put("kinds", pack.kinds().size());
            row.put("sutras", countSutras(dir));
            row.put("sutraProblems", broken);
            row.put("connectors", refs);
            row.put("connectorsDown", connectorsDown);
            row.put("connectorsOff", connectorsMissing);
            row.put("connectorsNotConfigured", notConfigured);
            String bad = packs.problems().get(pack.name());                     // a bad edit: the last good version keeps running
            String historyProblem = history.problem(pack);                       // a bad history: block (QA2-06): the default history applies
            String problem = bad == null ? historyProblem : historyProblem == null ? bad : bad + "; " + historyProblem;
            row.put("problem", problem);
            row.put("sample", pack.sample());
            Map<String, List<String>> lakes = lakesOf(refs);
            String lakeWarning = lakeWarning(pack.name(), lakes);
            row.put("lakes", lakes);
            row.put("lakeWarning", lakeWarning);
            row.put("status", !broken.isEmpty() || !connectorsDown.isEmpty() || !notConfigured.isEmpty() || problem != null || lakeWarning != null
                    ? "DEGRADED" : "OK");
            out.add(row);
        }
        return out;
    }

    /**
     * The lakes a pack's Delta and Iceberg connectors read, each with its connectors, sorted: the server's published results lakes
     * ({@code var-official}, {@code ccr-official}: read-only results, never an input) left out.
     */
    Map<String, List<String>> lakesOf(List<String> refs) {
        Map<String, List<String>> out = new java.util.TreeMap<>();
        for (String c : refs) {
            String lake = registry.lakeOf(c);
            if (lake != null && (!com.ash.drishti.lakeconfig.Lakes.SHIPPED.contains(lake) || SourceRouter.DEMO_LAKE.equals(lake))) {
                out.computeIfAbsent(lake, k -> new ArrayList<>()).add(c);
            }
        }
        return out;
    }

    /**
     * Demo and real data never mix (docs/guides/DATA_LAKE_GUIDE.md section 6): the warning when a pack's connectors read more than one lake,
     * or null.
     */
    static String lakeWarning(String pack, Map<String, List<String>> lakes) {
        if (lakes.size() < 2) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        lakes.forEach((lake, cs) -> parts.add(lake + " (" + String.join(", ", cs) + ")"));
        return pack + "'s connectors read different lakes: " + String.join("; ", parts) + ". Its screens mix them: point every connector of "
                + pack + " at one lake (lake: in each connector file) and restart";
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
        m.put("rssMb", ProcessResources.rssMb(Path.of("/proc/self/status")));   // resident memory (Linux VmRSS), null elsewhere
        m.putAll(ProcessResources.snapshot());                    // direct and mapped memory, open files: what a long run exhausts
        return m;
    }

    /**
     * The modules row: the code modules (a domain's Java, e.g. banking) and the content modules (packs and functions only) with whether each
     * is on. The thin server jar has no code module: the summary says the kernel runs alone.
     */
    static Map<String, Object> modules(com.ash.drishti.registry.AccessRegistry access) {
        List<Map<String, Object>> code = new ArrayList<>();
        List<Map<String, Object>> content = new ArrayList<>();
        for (com.ash.drishti.registry.ModuleState s : access.modules()) {
            if (!s.installed()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.id());
            row.put("on", s.on());
            if (!s.on()) {
                row.put("reason", s.reason());
            }
            boolean hasCode = access.manifest(s.id()).map(com.ash.drishti.registry.ModuleManifest::code).orElse(false);
            (hasCode ? code : content).add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", code.isEmpty() ? "no modules: the kernel runs alone"
                : code.size() + (code.size() == 1 ? " module: " : " modules: ") + String.join(", ", code.stream().map(r -> (String) r.get("id")).toList()));
        out.put("code", code);
        out.put("content", content);
        return out;
    }
}
