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
package com.ash.drishti.plugin.iceberg;

import com.ash.drishti.api.DataDir;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.diskcache.DiskCache;
import com.ash.drishti.plugin.iceberg.KindTable.Day;
import com.ash.drishti.plugin.iceberg.KindTable.IdMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Iceberg connector's caches on disk, kept across restarts (the Delta connector's design, ICEBERG_CONNECTOR.md section "Disk
 * caches"): each business date's id map written once and memory-mapped ({@link MappedIdMap}, under {@code cache/<connector>/idmaps},
 * {@code id-map-disk.enabled}, default true), and documents already read in a RocksDB store ({@link DiskCache}, under
 * {@code cache/<connector>/docs}, {@code doc-disk-cache.enabled}, default true; {@code doc-disk-cache.max-gb} 1, {@code .ttl} 12h,
 * {@code .reset-at} 02:00 in {@code .zone}). Both are keyed by the day's fingerprint (its data and delete files), so they are never
 * stale and a commit of another date keeps them valid. A document cache that cannot open (another process still holds it) is tried
 * again every 30 s for 10 minutes while reads go on without it. Thread-safe.
 */
final class IcebergDiskCaches implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IcebergDiskCaches.class);
    /** Seconds between tries to open a locked document cache (tests shorten it). */
    static long retrySeconds = 30;

    private final String sourceName;
    private final Path idMapDir;
    /**
     * The id-map folder's cap ({@code id-map-disk.max-gb}, 5: never a map in use), its TTL ({@code id-map-disk.ttl}, off) and the reasons
     * maps were dropped (2.9.7, {@link com.ash.drishti.api.IdMapFolder}).
     */
    private final com.ash.drishti.api.IdMapFolder idMapFolder;
    private volatile DiskCache docs;
    private volatile ScheduledFuture<?> retry;
    private final LongAdder idMapsMapped = new LongAdder();
    private final LongAdder idMapsRead = new LongAdder();

    IcebergDiskCaches(String sourceName, SourceContext ctx) {
        this.sourceName = sourceName;
        this.idMapFolder = com.ash.drishti.api.IdMapFolder.of(sourceName, ctx);
        this.idMapDir = idMapFolder == null ? null : idMapFolder.dir();
        sweepDays(idMapDir);
        if (idMapFolder != null) {
            idMapFolder.trim(null);
        }
        if (!Boolean.parseBoolean(ctx.setting("doc-disk-cache.enabled", "true"))) {
            return;
        }
        String failed = open(ctx);
        if (failed == null) {
            return;
        }
        LOG.warn("{}: documents are not cached on disk ({}): reads go on without it{}", sourceName, failed,
                ctx.scheduler() == null ? "" : "; trying again every " + retrySeconds + " s for 10 minutes");
        if (ctx.scheduler() != null) {
            long until = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
            retry = ctx.scheduler().scheduleWithFixedDelay(() -> {
                String again = open(ctx);
                if (again == null) {
                    LOG.info("{}: documents are cached on disk again", sourceName);
                } else if (System.nanoTime() < until) {
                    return;
                } else {
                    LOG.warn("{}: documents are not cached on disk until the next start ({})", sourceName, again);
                }
                retry.cancel(false);
            }, retrySeconds, retrySeconds, TimeUnit.SECONDS);
        }
    }

    /** Opens the RocksDB document store; null when it opened, else why not (it is a cache: reads go on without it). */
    private String open(SourceContext ctx) {
        Path dir = Path.of(ctx.setting("doc-disk-cache.dir", DataDir.under("cache/" + sourceName + "/docs")));
        try {
            this.docs = DiskCache.bounded(dir, (long) (Double.parseDouble(ctx.setting("doc-disk-cache.max-gb", "1")) * 1024 * 1024 * 1024),
                    duration(ctx.setting("doc-disk-cache.ttl", "12h")), true,
                    ctx.scheduler() == null ? null : LocalTime.parse(ctx.setting("doc-disk-cache.reset-at", "02:00")),
                    ZoneId.of(ctx.setting("doc-disk-cache.zone", "America/New_York")), ctx.scheduler(), java.time.Clock.systemUTC());
            return null;
        } catch (IOException | RuntimeException | LinkageError e) {
            this.docs = null;
            return e.toString();
        }
    }

    /** {@code 90s}, {@code 30m}, {@code 12h}, {@code 7d}, or an ISO-8601 duration ({@code PT12H}). */
    static Duration duration(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("p")) {
            return Duration.parse(t.toUpperCase(Locale.ROOT));
        }
        long n = Long.parseLong(t.substring(0, t.length() - 1));
        return switch (t.charAt(t.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("not a duration: " + text + " (use 90s, 30m, 12h or 7d)");
        };
    }

    /**
     * The day's id map: mapped from its file when one was written before (by this run or an earlier one), else {@code read} and
     * written (the same date's maps of other files go), else kept in the heap when it cannot be written.
     */
    IdMap idMap(String kind, Day d, Supplier<IdMap> read) {
        Path file = idMapDir == null ? null : idMapDir.resolve(safeName(kind)).resolve(dayName(d.date()) + "@" + Long.toHexString(d.fingerprint()) + ".idmap");
        IdMap m = file == null ? null : MappedIdMap.open(file);
        if (m != null) {
            idMapsMapped.increment();
            return m;
        }
        m = read.get();
        idMapsRead.increment();
        if (file != null) {
            try {
                m = MappedIdMap.write(m, file);
                pruneOtherFiles(file, idMapFolder);
                idMapFolder.trim(file);                                 // never this map, nor another in use
            } catch (IOException | RuntimeException e) {
                LOG.debug("{}: id map of {} {} kept in the heap: {}", sourceName, kind, d.date(), e.toString());
            }
        }
        return m;
    }

    private static String dayName(LocalDate date) {
        return LocalDate.MIN.equals(date) ? "undated" : date.toString();
    }

    /** A kind's name as a folder name. */
    static String safeName(String kind) {
        return kind.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * The same date's maps of earlier files go (a new commit of that date made them unused): a mapped one is unmapped once its readers are
     * done, then deleted (2.9.7; before, it was deleted while mapped).
     */
    static void pruneOtherFiles(Path file) {
        pruneOtherFiles(file, null);
    }

    /** As {@link #pruneOtherFiles(Path)}, each map dropped through {@code folder} ("replaced (new version)" on Admin -> Caches). */
    static void pruneOtherFiles(Path file, com.ash.drishti.api.IdMapFolder folder) {
        String prefix = file.getFileName().toString();
        prefix = prefix.substring(0, prefix.indexOf('@') + 1);
        try (var files = Files.list(file.getParent())) {
            for (Path other : files.filter(p -> !p.equals(file)).toList()) {
                String name = other.getFileName().toString();
                if (name.startsWith(prefix) && name.endsWith(".idmap")) {
                    if (folder != null) {
                        folder.drop(other, com.ash.drishti.api.IdMapFolder.Drop.REPLACED);
                    } else {
                        com.ash.drishti.api.MappedIdFile.release(other, true);
                    }
                }
            }
        } catch (IOException e) {
            // nothing to prune
        }
    }

    /**
     * At start (nothing mapped yet): per kind and date only the newest map stays (by the time it was last mapped), half-written files go,
     * and the folder is brought under its cap.
     */
    static void sweep(Path root, long maxBytes) {
        sweepDays(root);
        if (root != null) {
            com.ash.drishti.api.MappedIdFile.trim(root, maxBytes, null);
        }
    }

    /** At start: per kind and date only the newest map stays, by the time it was last mapped (nothing is mapped yet). */
    static void sweepDays(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (var kinds = Files.list(root)) {
            for (Path kind : kinds.filter(Files::isDirectory).toList()) {
                Map<String, Path> newest = new java.util.HashMap<>();
                try (var fs = Files.list(kind)) {
                    for (Path f : fs.filter(p -> p.getFileName().toString().endsWith(".idmap")).toList()) {
                        String n = f.getFileName().toString();
                        String day = n.contains("@") ? n.substring(0, n.indexOf('@')) : n;
                        Path other = newest.get(day);
                        if (other == null) {
                            newest.put(day, f);
                        } else {
                            boolean newer = Files.getLastModifiedTime(f).compareTo(Files.getLastModifiedTime(other)) > 0;
                            Files.deleteIfExists(newer ? other : f);
                            if (newer) {
                                newest.put(day, f);
                            }
                        }
                    }
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // nothing to sweep
        }
    }

    /** The id-map folder (its cap, TTL and drops), or null when id maps are not kept on disk. */
    com.ash.drishti.api.IdMapFolder idMapFolder() {
        return idMapFolder;
    }

    /** A document read before (keyed by the day's fingerprint, so never stale), or null. */
    String doc(String key) {
        DiskCache d = docs;
        if (d == null) {
            return null;
        }
        byte[] b = d.get(key);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    void putDoc(String key, String json) {
        DiskCache d = docs;
        if (d != null) {
            d.put(key, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** One date's id-map files go (unmapped first): rebuilt at its next read (2.9.7). */
    void purgeDate(LocalDate date) {
        if (idMapDir == null || !Files.isDirectory(idMapDir)) {
            return;
        }
        try (var files = Files.walk(idMapDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().startsWith(date + "@")).toList()) {
                com.ash.drishti.api.MappedIdFile.release(f, true);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // nothing to remove
        }
    }

    /** Everything on disk goes: rebuilt from the lake at the next read. */
    void purge() {
        if (idMapDir != null && Files.isDirectory(idMapDir)) {
            try (var files = Files.walk(idMapDir)) {
                for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!Files.isDirectory(f)) {
                        com.ash.drishti.api.MappedIdFile.release(f, true);   // unmapped once its readers are done, then deleted
                        continue;
                    }
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException e) {
                        // a file inside still waits for its readers: removed at the next sweep
                    }
                }
            } catch (IOException e) {
                // nothing to remove
            }
        }
        DiskCache d = docs;
        if (d != null) {
            d.clear();
        }
    }

    Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idMapsOnDisk", idMapDir != null);
        m.put("idMapsMappedFromDisk", idMapsMapped.sum());
        m.put("idMapsReadFromLake", idMapsRead.sum());
        if (idMapFolder != null) {
            m.putAll(idMapFolder.stats());
        }
        DiskCache d = docs;
        if (d != null) {
            m.put("docsOnDiskMB", d.sizeOnDisk() / (1024 * 1024));
            m.put("docsOnDiskHits", d.hits());
            m.put("docsOnDiskMisses", d.misses());
        }
        return m;
    }

    @Override
    public void close() {
        if (retry != null) {
            retry.cancel(false);
        }
        if (idMapFolder != null) {
            idMapFolder.close();
        }
        DiskCache d = docs;
        if (d != null) {
            d.close();
        }
    }
}
