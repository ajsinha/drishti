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

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.server.caches.CacheBoard;
import com.ash.drishti.server.caches.CacheReport;
import com.ash.drishti.server.caches.MemoryBudget;
import com.ash.drishti.server.disk.DiskBudget;
import com.ash.drishti.server.loads.ExpectationService;
import com.ash.drishti.server.loads.LoadRecord;
import com.ash.drishti.server.loads.LoadService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The platform gauges the CIO lens and the alert rules read (RESOURCES_AND_SCALE.md section 8.3; OPERATIONS.md section 13.6), sampled every
 * {@code drishti.metrics.sample-interval} from what Admin, Caches and Admin, Health already compute, and exported with every other meter on
 * {@code /actuator/prometheus}:
 *
 * <ul>
 *   <li>every cache: {@code drishti.cache.bytes}, {@code drishti.cache.limit.bytes}, {@code drishti.cache.entries} ({@code cache}, {@code owner},
 *       {@code where}), {@code drishti.cache.hits}, {@code drishti.cache.misses} (counters, {@code cache});</li>
 *   <li>the one memory budget: {@code drishti.memory.budget.bytes}, {@code drishti.memory.budget.used.bytes} ({@code pool}, {@code all} for the
 *       whole), {@code drishti.memory.budget.squeezes} (counter);</li>
 *   <li>the disk ({@code drishti.metrics.disk-interval}): {@code drishti.disk.folder.bytes}, {@code drishti.disk.folder.limit.bytes}
 *       ({@code folder}), {@code drishti.disk.free.bytes}, {@code drishti.disk.guard.bytes}; and on Linux {@code drishti.mapped.files},
 *       {@code drishti.mapped.deleted.files} (the lines of {@code /proc/self/maps}), {@code drishti.open.deleted.files};</li>
 *   <li>{@code drishti.direct.max.bytes}, the direct-memory limit the JVM's {@code jvm.buffer.memory.used{id="direct"}} is held against;</li>
 *   <li>freshness: {@code drishti.source.up}, {@code drishti.source.stale}, {@code drishti.source.update.age.seconds} ({@code source}),
 *       {@code drishti.feeds.attention} ({@code state}: late, missing, failed), {@code drishti.loads.ready} (counter, {@code pack}) and
 *       {@code drishti.loads.last.ready.age.seconds} ({@code pack}).</li>
 * </ul>
 * Tags stay bounded: one series per cache, pool, folder, source and pack the server has.
 */
public final class PlatformMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformMetrics.class);
    static final Path MAPS = Path.of("/proc/self/maps");
    static final Path FDS = Path.of("/proc/self/fd");
    private static final int MAX_MAP_LINES = 2_000_000;

    private final MeterRegistry registry;
    private final Supplier<CacheBoard> caches;
    private final Supplier<MemoryBudget> memory;
    private final Supplier<DiskBudget> disk;
    private final SourceRegistry sources;
    private final Supplier<ExpectationService> expectations;
    private final Supplier<LoadService> loads;
    private final Clock clock;

    private final MultiGauge cacheBytes;
    private final MultiGauge cacheLimit;
    private final MultiGauge cacheEntries;
    private final MultiGauge poolBytes;
    private final MultiGauge poolUsed;
    private final MultiGauge folderBytes;
    private final MultiGauge folderLimit;
    private final MultiGauge sourceUp;
    private final MultiGauge sourceStale;
    private final MultiGauge sourceAge;
    private final MultiGauge feeds;
    private final MultiGauge loadAge;
    private final Map<String, long[]> cacheCounts = new ConcurrentHashMap<>();     // cache -> {hits, misses}, the last sample
    private volatile double diskFree = Double.NaN;
    private volatile double diskGuard = Double.NaN;
    private volatile double mapped = Double.NaN;
    private volatile double mappedDeleted = Double.NaN;
    private volatile double openDeleted = Double.NaN;
    private volatile long squeezes;
    private volatile long diskSampledAt;

    public PlatformMetrics(MeterRegistry registry, Supplier<CacheBoard> caches, Supplier<MemoryBudget> memory, Supplier<DiskBudget> disk,
            SourceRegistry sources, Supplier<ExpectationService> expectations, Supplier<LoadService> loads, Clock clock) {
        this.registry = registry;
        this.caches = caches;
        this.memory = memory;
        this.disk = disk;
        this.sources = sources;
        this.expectations = expectations;
        this.loads = loads;
        this.clock = clock;
        cacheBytes = MultiGauge.builder("drishti.cache.bytes").description("a cache's size").baseUnit("bytes").register(registry);
        cacheLimit = MultiGauge.builder("drishti.cache.limit.bytes").description("a cache's limit").baseUnit("bytes").register(registry);
        cacheEntries = MultiGauge.builder("drishti.cache.entries").description("a cache's entries").register(registry);
        poolBytes = MultiGauge.builder("drishti.memory.budget.bytes").description("the memory budget of a pool (all: the whole budget)")
                .baseUnit("bytes").register(registry);
        poolUsed = MultiGauge.builder("drishti.memory.budget.used.bytes").description("what a pool's caches hold (all: every pool)")
                .baseUnit("bytes").register(registry);
        folderBytes = MultiGauge.builder("drishti.disk.folder.bytes").description("a folder's size under the data folder").baseUnit("bytes")
                .register(registry);
        folderLimit = MultiGauge.builder("drishti.disk.folder.limit.bytes").description("a folder's cap (0: none)").baseUnit("bytes")
                .register(registry);
        sourceUp = MultiGauge.builder("drishti.source.up").description("1 when a source serves (UP or DEGRADED), 0 when it is down").register(registry);
        sourceStale = MultiGauge.builder("drishti.source.stale").description("1 when nothing new arrived for longer than its stale-after")
                .register(registry);
        sourceAge = MultiGauge.builder("drishti.source.update.age.seconds").description("seconds since a source last received new data")
                .register(registry);
        feeds = MultiGauge.builder("drishti.feeds.attention").description("expected loads needing attention now, by state").register(registry);
        loadAge = MultiGauge.builder("drishti.loads.last.ready.age.seconds").description("seconds since a pack's last ready load")
                .register(registry);
        Gauge.builder("drishti.disk.free.bytes", this, m -> m.diskFree).description("free space on the data folder's disk").baseUnit("bytes")
                .register(registry);
        Gauge.builder("drishti.disk.guard.bytes", this, m -> m.diskGuard).description("the free space a write must leave").baseUnit("bytes")
                .register(registry);
        Gauge.builder("drishti.mapped.files", this, m -> m.mapped).description("memory mappings of files (/proc/self/maps)").register(registry);
        Gauge.builder("drishti.mapped.deleted.files", this, m -> m.mappedDeleted).description("mappings of files already deleted")
                .register(registry);
        Gauge.builder("drishti.open.deleted.files", this, m -> m.openDeleted).description("descriptors open on deleted files").register(registry);
        Gauge.builder("drishti.direct.max.bytes", PlatformMetrics::maxDirectMemory).description("the direct-memory limit").baseUnit("bytes")
                .register(registry);
        FunctionCounter.builder("drishti.memory.budget.squeezes", this, m -> m.squeezes)
                .description("times the heap after a collection passed squeeze-at and the low-priority pools were halved").register(registry);
    }

    /** Counts a ready load of {@code pack} ({@link LoadService#onReady}). */
    public void loadReady(LoadRecord r) {
        Counter.builder("drishti.loads.ready").description("loads that finished ready").tag("pack", r.pack()).register(registry).increment();
    }

    /** One sample of every gauge; the disk too when {@code drishti.metrics.disk-interval} has passed. A part that fails keeps its last values. */
    public void sample(long diskIntervalMillis) {
        part("caches", this::sampleCaches);
        part("memory", this::sampleMemory);
        part("sources", this::sampleSources);
        part("feeds", this::sampleFeeds);
        long now = clock.millis();
        if (now - diskSampledAt >= diskIntervalMillis) {
            diskSampledAt = now;
            part("disk", this::sampleDisk);
            part("loads", this::sampleLoads);
            part("files", this::sampleFiles);
        }
    }

    private static void part(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            LOG.debug("platform metrics: the {} sample failed: {}", what, e.toString());
        }
    }

    void sampleCaches() {
        CacheBoard board = caches.get();
        if (board == null) {
            return;
        }
        List<MultiGauge.Row<?>> bytes = new ArrayList<>();
        List<MultiGauge.Row<?>> limits = new ArrayList<>();
        List<MultiGauge.Row<?>> entries = new ArrayList<>();
        for (CacheReport r : board.reports()) {
            Tags t = Tags.of("cache", r.name(), "owner", nz(r.owner()), "where", nz(r.where()));
            if (r.sizeBytes() != null) {
                bytes.add(MultiGauge.Row.of(t, r.sizeBytes()));
            }
            if (r.limitBytes() != null && r.limitBytes() > 0) {
                limits.add(MultiGauge.Row.of(t, r.limitBytes()));
            }
            if (r.entries() != null) {
                entries.add(MultiGauge.Row.of(t, r.entries()));
            }
            if (r.hits() != null || r.misses() != null) {
                long[] prev = cacheCounts.putIfAbsent(r.name(), new long[] {nz(r.hits()), nz(r.misses())});
                if (prev == null) {
                    String name = r.name();
                    FunctionCounter.builder("drishti.cache.hits", cacheCounts, m -> m.getOrDefault(name, new long[2])[0]).tag("cache", name)
                            .description("a cache's hits since it was made or purged").register(registry);
                    FunctionCounter.builder("drishti.cache.misses", cacheCounts, m -> m.getOrDefault(name, new long[2])[1]).tag("cache", name)
                            .description("a cache's misses since it was made or purged").register(registry);
                } else {
                    cacheCounts.put(r.name(), new long[] {nz(r.hits()), nz(r.misses())});
                }
            }
        }
        cacheBytes.register(bytes, true);
        cacheLimit.register(limits, true);
        cacheEntries.register(entries, true);
    }

    @SuppressWarnings("unchecked")
    void sampleMemory() {
        MemoryBudget b = memory.get();
        if (b == null) {
            return;
        }
        Map<String, Object> d = b.describe();
        List<MultiGauge.Row<?>> limits = new ArrayList<>();
        List<MultiGauge.Row<?>> used = new ArrayList<>();
        limits.add(MultiGauge.Row.of(Tags.of("pool", "all"), num(d.get("budgetBytes"))));
        used.add(MultiGauge.Row.of(Tags.of("pool", "all"), num(d.get("usedBytes"))));
        for (Map<String, Object> p : (List<Map<String, Object>>) d.getOrDefault("pools", List.of())) {
            Tags t = Tags.of("pool", String.valueOf(p.get("pool")));
            limits.add(MultiGauge.Row.of(t, num(p.get("limitBytes"))));
            used.add(MultiGauge.Row.of(t, num(p.get("usedBytes"))));
        }
        poolBytes.register(limits, true);
        poolUsed.register(used, true);
        squeezes = (long) num(d.get("squeezes"));
    }

    @SuppressWarnings("unchecked")
    void sampleDisk() {
        DiskBudget b = disk.get();
        if (b == null) {
            return;
        }
        Map<String, Object> d = b.describe();
        diskFree = num(d.get("freeBytes"));
        diskGuard = num(d.get("guardBytes"));
        List<MultiGauge.Row<?>> used = new ArrayList<>();
        List<MultiGauge.Row<?>> caps = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) d.getOrDefault("folders", List.of())) {
            Tags t = Tags.of("folder", String.valueOf(f.get("name")));
            used.add(MultiGauge.Row.of(t, num(f.get("usedBytes"))));
            if (num(f.get("limitBytes")) > 0) {                      // a folder without a cap has no limit series (nothing to divide by)
                caps.add(MultiGauge.Row.of(t, num(f.get("limitBytes"))));
            }
        }
        folderBytes.register(used, true);
        folderLimit.register(caps, true);
    }

    void sampleSources() {
        Instant now = clock.instant();
        List<MultiGauge.Row<?>> up = new ArrayList<>();
        List<MultiGauge.Row<?>> stale = new ArrayList<>();
        List<MultiGauge.Row<?>> age = new ArrayList<>();
        for (SourcePlugin s : sources.plugins()) {
            String name = s.manifest().name();
            Tags t = Tags.of("source", name);
            String status = com.ash.drishti.engine.source.SourceHealth.of(health(s)).name();
            up.add(MultiGauge.Row.of(t, "DOWN".equals(status) ? 0 : 1));
            var fresh = sources.freshness(name);
            stale.add(MultiGauge.Row.of(t, fresh.stale() ? 1 : 0));
            if (fresh.lastUpdate() != null) {
                age.add(MultiGauge.Row.of(t, Math.max(0, now.getEpochSecond() - fresh.lastUpdate().getEpochSecond())));
            }
        }
        sourceUp.register(up, true);
        sourceStale.register(stale, true);
        sourceAge.register(age, true);
    }

    void sampleFeeds() {
        ExpectationService x = expectations.get();
        if (x == null) {
            return;
        }
        Map<String, Integer> byState = new java.util.TreeMap<>(Map.of(ExpectationService.LATE, 0, ExpectationService.MISSING, 0,
                ExpectationService.FAILED, 0));
        for (ExpectationService.State s : x.attention(clock.instant())) {
            byState.merge(s.state(), 1, Integer::sum);
        }
        feeds.register(byState.entrySet().stream().<MultiGauge.Row<?>>map(e -> MultiGauge.Row.of(Tags.of("state", e.getKey()), e.getValue())).toList(),
                true);
    }

    void sampleLoads() {
        LoadService l = loads.get();
        if (l == null) {
            return;
        }
        Instant now = clock.instant();
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        for (com.ash.drishti.packs.Pack p : l.loadedPacks()) {
            l.store().list(p.name(), null, null, LoadRecord.READY, 1).stream().findFirst().map(LoadRecord::receivedAt).ifPresent(at -> {
                rows.add(MultiGauge.Row.of(Tags.of("pack", p.name()), Math.max(0, now.getEpochSecond() - at.getEpochSecond())));
            });
        }
        loadAge.register(rows, true);
    }

    void sampleFiles() {
        long[] m = maps(MAPS);
        mapped = m == null ? Double.NaN : m[0];
        mappedDeleted = m == null ? Double.NaN : m[1];
        Long d = deletedOpenFiles(FDS);
        openDeleted = d == null ? Double.NaN : d;
    }

    /** The lines of a {@code /proc/<pid>/maps} that map a file, and those whose file was deleted; null where there is no such file. */
    static long[] maps(Path maps) {
        if (!Files.isReadable(maps)) {
            return null;
        }
        long files = 0;
        long deleted = 0;
        int seen = 0;
        try (BufferedReader r = Files.newBufferedReader(maps)) {
            for (String line = r.readLine(); line != null && seen++ < MAX_MAP_LINES; line = r.readLine()) {
                int slash = line.indexOf(" /");
                if (slash > 0) {
                    files++;
                    deleted += line.endsWith(" (deleted)") ? 1 : 0;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return new long[] {files, deleted};
    }

    /** Descriptors open on deleted files (Linux, {@code /proc/self/fd}); null elsewhere. */
    static Long deletedOpenFiles(Path fds) {
        if (!Files.isDirectory(fds)) {
            return null;
        }
        long n = 0;
        int seen = 0;
        try (var s = Files.newDirectoryStream(fds)) {
            for (Path fd : s) {
                if (++seen > 20_000) {
                    break;
                }
                try {
                    n += Files.readSymbolicLink(fd).toString().endsWith(" (deleted)") ? 1 : 0;
                } catch (IOException | UnsupportedOperationException e) {
                    // closed while listing
                }
            }
        } catch (IOException e) {
            return null;
        }
        return n;
    }

    /** {@code -XX:MaxDirectMemorySize}, or the JVM's default when it is not set (the heap's maximum). */
    static double maxDirectMemory() {
        try {
            var hs = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            long set = Long.parseLong(hs.getVMOption("MaxDirectMemorySize").getValue());
            return set > 0 ? set : Runtime.getRuntime().maxMemory();
        } catch (RuntimeException e) {
            return Runtime.getRuntime().maxMemory();
        }
    }

    private static String health(SourcePlugin s) {
        try {
            String h = s.health();
            return h == null ? "UP" : h;
        } catch (RuntimeException e) {
            return "DOWN: " + e.getMessage();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static long nz(Long l) {
        return l == null ? 0 : l;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
