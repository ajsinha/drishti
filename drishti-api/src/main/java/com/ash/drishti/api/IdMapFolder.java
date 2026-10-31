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
package com.ash.drishti.api;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One connector's id-map folder on disk ({@code cache/<connector>/idmaps}, the Delta and Iceberg connectors) and what keeps it bounded
 * (2.9.7, RESOURCES_AND_SCALE.md section 14.25): the cap {@code id-map-disk.max-gb} and the optional age limit {@code id-map-disk.ttl}.
 *
 * <p><b>Idle mappings are released</b> ({@code id-map-disk.idle-release}, 30m, at least 5m): a map not read for that long is unmapped
 * (the file stays; the next read maps it again), never while a read is inside it nor for a date an engine has open. So the connectors'
 * heap caches, which keep a mapped map for 256 bytes, do not hold the folder above its cap for ever.
 *
 * <p><b>The cap</b> trims the least recently used maps, never one that is being read, mapped and read within {@code idle-release},
 * written or mapped in the last five minutes, or of a date an engine has open ({@link DatesInUse}): a date whose map alone is over the
 * cap is never evicted while it is used (and rebuilt in a loop); it is kept, the other dates are trimmed, and a warning (logged once per
 * date and version, and shown on Admin -> Caches) says to raise {@code id-map-disk.max-gb}. Idle maps released, the folder converges
 * back under the cap at the next sweep; only maps in use can keep it above ({@code idMapsInUseOverCap}).
 *
 * <p><b>The TTL</b> (off by default; at least 24 hours) drops a map not used (mapped or read) for that long, give or take 10% per file so
 * maps do not all go together; never the connector's latest and previous dates of a table, today's, a date open in VaR or exposure, a map
 * being read, nor a mapped map when no engine can say which dates it has open. It runs on the connector's scheduler.
 *
 * <p>Every drop unmaps first (once the readers are done) and then deletes; a delete that fails (Windows, a scanner holding the file) is
 * tried again at the next sweep, never an error. The last drops are kept with their reason for Admin -> Caches: {@code expired (ttl)},
 * {@code evicted (max-gb)}, {@code replaced (new version)}. Thread-safe.
 */
public final class IdMapFolder implements AutoCloseable {

    /** The default and the shortest {@code id-map-disk.idle-release}. */
    public static final Duration IDLE_RELEASE = Duration.ofMinutes(30);
    public static final Duration MIN_IDLE_RELEASE = Duration.ofMinutes(5);
    /** The shortest {@code id-map-disk.ttl} accepted. */
    public static final Duration MIN_TTL = Duration.ofHours(24);
    /** A map written or mapped this recently is in use: the cap leaves it. */
    public static final Duration RECENT = Duration.ofMinutes(5);
    /** How often the sweep (the TTL, deletes tried again, the cap) runs. */
    static final Duration SWEEP_EVERY = Duration.ofMinutes(10);
    private static final int KEEP_DROPS = 200;
    private static final System.Logger LOG = System.getLogger(IdMapFolder.class.getName());
    private static final List<IdMapFolder> ALL = new CopyOnWriteArrayList<>();

    /** Why a map left the folder. */
    public enum Drop {
        EXPIRED("expired (ttl)"), EVICTED("evicted (max-gb)"), REPLACED("replaced (new version)");

        private final String text;

        Drop(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** Deletes a file; throws when it cannot (the sweep tries again). Tests replace it to act as Windows does with a held file. */
    @FunctionalInterface
    public interface Deleter {
        void delete(Path file) throws IOException;
    }

    private final String connector;
    private final Path dir;
    private final long maxBytes;
    private final Duration ttl;
    private final Duration idleRelease;
    private final Clock clock;
    private final java.util.concurrent.atomic.AtomicLong idleReleased = new java.util.concurrent.atomic.AtomicLong();
    private volatile Supplier<? extends Collection<LocalDate>> latestDates = List::of;
    private volatile Deleter deleter = Files::deleteIfExists;
    private final Set<Path> pending = ConcurrentHashMap.newKeySet();
    private final Map<Path, String> warnings = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> drops = new ArrayDeque<>();
    private final Map<Drop, Long> dropped = new EnumMap<>(Drop.class);
    private volatile int inUseOverCap;
    private final java.util.concurrent.atomic.AtomicInteger warned = new java.util.concurrent.atomic.AtomicInteger();
    private volatile ScheduledFuture<?> sweeping;

    public IdMapFolder(String connector, Path dir, long maxBytes, Duration ttl, Clock clock) {
        this(connector, dir, maxBytes, ttl, IDLE_RELEASE, clock);
    }

    public IdMapFolder(String connector, Path dir, long maxBytes, Duration ttl, Duration idleRelease, Clock clock) {
        this.connector = connector;
        this.dir = dir.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.ttl = ttl;
        this.idleRelease = idleRelease;
        this.clock = clock;
        ALL.add(this);
    }

    /**
     * The connector's folder from its settings ({@code id-map-disk.enabled} true, {@code .dir}, {@code .max-gb} 5, {@code .ttl} off), or
     * null when disabled. A TTL under 24 hours is refused: the start fails, naming the setting and the minimum.
     */
    public static IdMapFolder of(String connector, SourceContext ctx) {
        if (!Boolean.parseBoolean(ctx.setting("id-map-disk.enabled", "true"))) {
            return null;
        }
        Duration ttl = ttl(connector, ctx.setting("id-map-disk.ttl", ""));       // refused before anything is created
        Duration idle = idleRelease(connector, ctx.setting("id-map-disk.idle-release", "30m"));
        Path d = Path.of(ctx.setting("id-map-disk.dir", DataDir.under("cache/" + connector + "/idmaps")));
        long max = (long) (Double.parseDouble(ctx.setting("id-map-disk.max-gb", "5")) * (1L << 30));
        return new IdMapFolder(connector, d, max, ttl, idle, Clock.systemUTC());
    }

    /** {@code id-map-disk.idle-release}: 30m when empty; at least 5m ({@code 45m}, {@code 2h}, {@code PT1H}). */
    public static Duration idleRelease(String connector, String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return IDLE_RELEASE;
        }
        Duration d;
        try {
            d = duration(t);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(connector + ": id-map-disk.idle-release '" + text.trim() + "' is not a duration (use 30m, 2h or PT1H)", e);
        }
        if (d.compareTo(MIN_IDLE_RELEASE) < 0) {
            throw new IllegalArgumentException(connector + ": id-map-disk.idle-release is " + text.trim() + "; the minimum is 5m");
        }
        return d;
    }

    /** {@code id-map-disk.ttl}: empty, {@code 0} or {@code off} is off (null); else at least 24 hours ({@code 36h}, {@code 7d}, {@code P7D}). */
    public static Duration ttl(String connector, String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.equals("0") || t.equals("off")) {
            return null;
        }
        Duration d;
        try {
            d = duration(t);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(connector + ": id-map-disk.ttl '" + text.trim() + "' is not a duration (use 36h, 7d or P7D; empty or 0: off)", e);
        }
        if (d.isZero()) {
            return null;
        }
        if (d.compareTo(MIN_TTL) < 0) {
            throw new IllegalArgumentException(connector + ": id-map-disk.ttl is " + text.trim() + "; the minimum is 24h (empty or 0 turns it off)");
        }
        return d;
    }

    private static Duration duration(String t) {
        if (t.startsWith("p")) {
            return Duration.parse(t.toUpperCase(Locale.ROOT));
        }
        long n = Long.parseLong(t.substring(0, t.length() - 1));
        return switch (t.charAt(t.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalArgumentException("not a duration: " + t);
        };
    }

    /** Every folder of this process (Admin -> Caches). */
    public static List<IdMapFolder> all() {
        return List.copyOf(ALL);
    }

    public Path dir() {
        return dir;
    }

    public String connector() {
        return connector;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** The TTL, or null when off. */
    public Duration ttl() {
        return ttl;
    }

    /** The connector's own latest and previous dates (of each table): the TTL never drops them. */
    public void latestDates(Supplier<? extends Collection<LocalDate>> dates) {
        this.latestDates = dates;
    }

    /** Replaces how files are deleted (tests: a delete that fails, as Windows does while another process holds the file). */
    public void deleter(Deleter d) {
        this.deleter = d;
    }

    /** Runs {@link #sweep} every ten minutes on {@code scheduler}, the work itself on {@code run} (the scheduler's tasks stay short). */
    public void schedule(ScheduledExecutorService scheduler, Executor run) {
        if (scheduler == null) {
            return;
        }
        long every = SWEEP_EVERY.toSeconds();
        sweeping = scheduler.scheduleWithFixedDelay(() -> {
            try {
                run.execute(this::sweep);
            } catch (RuntimeException e) {
                // the connector is closing
            }
        }, every, every, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------------------------------------------------------ the files

    /** A file's business date, from its name ({@code 2026-10-21.idmap}, {@code 2026-10-21@<fingerprint>.idmap}); null when undated. */
    static LocalDate dateOf(Path file) {
        String n = file.getFileName().toString();
        n = n.endsWith(".idmap") ? n.substring(0, n.length() - 6) : n;
        int at = n.indexOf('@');
        try {
            return LocalDate.parse(at < 0 ? n : n.substring(0, at));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String kindOf(Path file) {
        Path rel = dir.relativize(file.toAbsolutePath().normalize());
        return rel.getNameCount() > 1 ? rel.getName(0).toString() : "";
    }

    /** The table version the map was read at: Delta's version folder, Iceberg's day fingerprint. */
    private String versionOf(Path file) {
        Path rel = dir.relativize(file.toAbsolutePath().normalize());
        if (rel.getNameCount() > 2) {
            return rel.getName(rel.getNameCount() - 2).toString();
        }
        String n = file.getFileName().toString();
        n = n.endsWith(".idmap") ? n.substring(0, n.length() - 6) : n;
        int at = n.indexOf('@');
        return at < 0 ? "" : n.substring(at + 1);
    }

    private List<Path> maps() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".idmap") && Files.isRegularFile(p)).map(p -> p.toAbsolutePath().normalize())
                    .toList();
        } catch (IOException | java.io.UncheckedIOException e) {
            return List.of();
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** When the map was last used: mapped (the file's time, set at each mapping) or read (its mapping's last read). */
    private static long lastUse(Path p) {
        return Math.max(mtime(p), MappedIdFile.lastUsed(p));
    }

    private boolean recent(long millis) {
        return clock.millis() - millis < RECENT.toMillis();
    }

    /** The TTL of one file: the setting, give or take up to 10% by the file's name (the same at every sweep). */
    static long jittered(Path file, Duration ttl) {
        long h = Integer.toUnsignedLong(file.getFileName().toString().hashCode() * 0x9E3779B9);
        double f = 0.9 + 0.2 * (h % 10_001) / 10_000.0;
        return (long) (ttl.toMillis() * f);
    }

    // ------------------------------------------------------------------------------------------------------------------ the cap

    /**
     * Brings the folder under {@code id-map-disk.max-gb}: half-written files older than five minutes go, then the least recently used maps
     * that are not in use (mapped, being read, written or mapped in the last five minutes, of a date open in an engine, or {@code keep}).
     * A map alone over the cap is warned about once. Returns the files removed.
     */
    public int trim(Path keep) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int gone = leftovers();
        if (maxBytes <= 0) {
            return gone;
        }
        Path k = keep == null ? null : keep.toAbsolutePath().normalize();
        Set<LocalDate> open = DatesInUse.now();
        Map<Path, long[]> info = new HashMap<>();
        long total = 0;
        for (Path p : maps()) {
            try {
                long size = Files.size(p);
                info.put(p, new long[] {size, lastUse(p)});
                total += size;
                if (size > maxBytes) {
                    warnOversized(p, size);
                }
            } catch (IOException e) {
                // gone meanwhile
            }
        }
        if (total <= maxBytes) {
            inUseOverCap = 0;
            return gone;
        }
        List<Path> oldest = new ArrayList<>(info.keySet());
        oldest.sort(Comparator.comparingLong(p -> info.get(p)[1]));
        int held = 0;
        for (Path p : oldest) {
            if (total <= maxBytes) {
                break;
            }
            LocalDate d = dateOf(p);
            boolean inUse = p.equals(k) || MappedIdFile.isReading(p) || recent(info.get(p)[1]) || (open != null && d != null && open.contains(d))
                    || (MappedIdFile.isMapped(p) && (open == null || !idle(info.get(p)[1])));   // mapped and read lately
            if (inUse) {
                held++;
                continue;
            }
            drop(p, Drop.EVICTED);
            total -= info.get(p)[0];
            gone++;
        }
        inUseOverCap = total > maxBytes ? held : 0;
        return gone;
    }

    private int leftovers() {
        int gone = 0;
        try (var s = Files.walk(dir)) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".tmp") && Files.isRegularFile(f)).toList()) {
                if (!recent(mtime(p))) {                               // a write in progress stays
                    try {
                        Files.deleteIfExists(p);
                        gone++;
                    } catch (IOException e) {
                        // the next sweep
                    }
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // nothing to remove
        }
        return gone;
    }

    private void warnOversized(Path p, long size) {
        warnings.computeIfAbsent(p, f -> {
            String text = String.format(Locale.ROOT, "the id map of %s %s (version %s) is %s alone, more than id-map-disk.max-gb (%s): it is kept while"
                    + " in use and the other dates are trimmed; raise id-map-disk.max-gb of %s to at least %d GB", kindOf(p), dateText(p), versionOf(p),
                    gb(size), gb(maxBytes), connector, Math.max(1, (long) Math.ceil(2.0 * size / (1L << 30))));
            LOG.log(Level.WARNING, "{0}: {1}", connector, text);       // once per date and version
            warned.incrementAndGet();
            return text;
        });
    }

    private static String dateText(Path p) {
        LocalDate d = dateOf(p);
        return d == null ? "(no dates)" : d.toString();
    }

    private static String gb(long bytes) {
        return bytes >= (1L << 30) ? String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30))
                : String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
    }

    private boolean idle(long lastUse) {
        return clock.millis() - lastUse >= idleRelease.toMillis();
    }

    // ------------------------------------------------------------------------------------------------------------------ idle release

    /**
     * Unmaps the maps not read for {@code id-map-disk.idle-release}: a release, not a delete (the file stays and the next read maps it
     * again); never while a read is inside one, nor of a date an engine has open; nothing when an engine cannot say which dates it has
     * open. Returns the mappings released.
     */
    public int releaseIdle() {
        Set<LocalDate> open = DatesInUse.now();
        if (open == null) {
            return 0;
        }
        int released = 0;
        for (Path p : maps()) {
            LocalDate d = dateOf(p);
            if (!MappedIdFile.isMapped(p) || MappedIdFile.isReading(p) || (d != null && open.contains(d)) || !idle(lastUse(p))) {
                continue;
            }
            MappedIdFile.releaseThen(p, () -> { });                   // unmapped once a read that just entered has left
            idleReleased.incrementAndGet();
            released++;
        }
        return released;
    }

    // ------------------------------------------------------------------------------------------------------------------ the TTL

    /**
     * Drops the maps not used for the TTL (each file's, give or take 10%); never the connector's latest and previous dates, today's, a
     * date open in an engine, a map being read, nor a mapped map when no engine says which dates it has open. Returns the maps dropped.
     */
    public int expire() {
        if (ttl == null) {
            return 0;
        }
        Set<LocalDate> open = DatesInUse.now();
        if (open == null) {
            return 0;                                                  // an engine could not say: nothing goes this round
        }
        boolean enginesKnown = DatesInUse.known();
        Set<LocalDate> keep = new HashSet<>(open);
        try {
            keep.addAll(latestDates.get());
        } catch (RuntimeException e) {
            return 0;                                                  // the connector cannot say its latest dates now: nothing goes
        }
        keep.add(LocalDate.now(clock));
        long now = clock.millis();
        int gone = 0;
        for (Path p : maps()) {
            LocalDate d = dateOf(p);
            if (d == null || keep.contains(d) || MappedIdFile.isReading(p) || (MappedIdFile.isMapped(p) && !enginesKnown)) {
                continue;
            }
            if (now - lastUse(p) > jittered(p, ttl)) {
                drop(p, Drop.EXPIRED);
                gone++;
            }
        }
        return gone;
    }

    /** The scheduled sweep: deletes that failed are tried again, the last reads are kept in the files' times, then the TTL and the cap. */
    public void sweep() {
        try {
            retryDeletes();
            for (Path p : maps()) {
                long used = MappedIdFile.lastUsed(p);
                if (used > mtime(p) + 60_000) {
                    try {
                        Files.setLastModifiedTime(p, FileTime.fromMillis(used));   // kept across a restart
                    } catch (IOException e) {
                        // read-only: the mapping's time only
                    }
                }
            }
            releaseIdle();                                             // first: the idle mappings let the cap and the TTL work
            expire();
            trim(null);
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "{0}: id-map sweep: {1}", connector, e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ drops

    /** Drops one map for {@code why}: unmapped once its readers are done, then deleted (tried again at the next sweep when that fails). */
    public void drop(Path file, Drop why) {
        Path p = file.toAbsolutePath().normalize();
        long size = 0;
        try {
            size = Files.size(p);
        } catch (IOException e) {
            // gone already
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connector", connector);
        m.put("kind", kindOf(p));
        m.put("date", dateText(p));
        m.put("version", versionOf(p));
        m.put("state", "dropped");
        m.put("reason", why.text());
        m.put("sizeBytes", size);
        m.put("at", Instant.ofEpochMilli(clock.millis()).toString());
        synchronized (drops) {
            drops.addFirst(m);
            while (drops.size() > KEEP_DROPS) {
                drops.removeLast();
            }
            dropped.merge(why, 1L, Long::sum);
        }
        warnings.remove(p);
        MappedIdFile.doom(p);                                          // not mapped again meanwhile
        MappedIdFile.releaseThen(p, () -> delete(p));
    }

    private void delete(Path p) {
        if (!MappedIdFile.doomed(p)) {
            pending.remove(p);                                         // written again meanwhile: that file stays
            return;
        }
        try {
            deleter.delete(p);
            MappedIdFile.deleted(p);
            pending.remove(p);
        } catch (IOException | RuntimeException e) {
            pending.add(p);                                            // Windows: still held; the next sweep tries again
            LOG.log(Level.DEBUG, "{0}: {1} not deleted yet ({2}): tried again at the next sweep", connector, p.getFileName(), e.toString());
        }
    }

    /** The deletes that failed, tried again (a file mapped again meanwhile is released first). Returns how many still wait. */
    public int retryDeletes() {
        for (Path p : List.copyOf(pending)) {
            if (!Files.exists(p)) {
                pending.remove(p);
            } else {
                MappedIdFile.releaseThen(p, () -> delete(p));
            }
        }
        return pending.size();
    }

    /** The files whose delete failed and wait for the next sweep. */
    public Set<Path> pendingDeletes() {
        return Set.copyOf(pending);
    }

    /** The warnings now: a map alone over the cap, per file. */
    public Map<Path, String> warnings() {
        return Map.copyOf(warnings);
    }

    /** How many oversized warnings were logged (each once per date and version). */
    int warnedCount() {
        return warned.get();
    }

    /** The last drops, newest first, with their reasons. */
    public List<Map<String, Object>> drops() {
        synchronized (drops) {
            return List.copyOf(drops);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ Admin -> Caches

    /** One row per map on disk (mapped or not, its size, last use, a warning when it alone is over the cap), then the last drops. */
    public List<Map<String, Object>> dates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path p : maps()) {
            long size;
            try {
                size = Files.size(p);
            } catch (IOException e) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("connector", connector);
            m.put("kind", kindOf(p));
            m.put("date", dateText(p));
            m.put("version", versionOf(p));
            m.put("state", MappedIdFile.isMapped(p) ? "mapped" : "disk");
            m.put("sizeBytes", size);
            m.put("lastUsedAt", Instant.ofEpochMilli(lastUse(p)).toString());
            String w = warnings.get(p);
            m.put("warning", w != null);
            m.put("warningText", w);
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("date"))).reversed());
        out.addAll(drops());
        return out;
    }

    /** The folder's figures for the connector's statistics and Admin -> Caches. */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idMapDiskMB", MappedIdFile.folderBytes(dir) / (1024 * 1024));
        m.put("idMapDiskMaxGb", maxBytes / (double) (1L << 30));
        m.put("idMapTtl", ttl == null ? "off" : ttl.toString());
        m.put("idMapIdleRelease", idleRelease.toString());
        m.put("idMapsIdleReleased", idleReleased.get());
        synchronized (drops) {
            m.put("idMapsExpired", dropped.getOrDefault(Drop.EXPIRED, 0L));
            m.put("idMapsEvicted", dropped.getOrDefault(Drop.EVICTED, 0L));
            m.put("idMapsReplaced", dropped.getOrDefault(Drop.REPLACED, 0L));
        }
        if (!warnings.isEmpty()) {
            m.put("idMapWarnings", warnings.size());
        }
        if (inUseOverCap > 0) {
            m.put("idMapsInUseOverCap", inUseOverCap);
        }
        if (!pending.isEmpty()) {
            m.put("idMapDeletesPending", pending.size());
        }
        return m;
    }

    @Override
    public void close() {
        ScheduledFuture<?> s = sweeping;
        if (s != null) {
            s.cancel(false);
        }
        ALL.remove(this);
    }
}
