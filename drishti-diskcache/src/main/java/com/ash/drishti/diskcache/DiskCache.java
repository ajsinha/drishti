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
package com.ash.drishti.diskcache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.rocksdb.CompactionOptionsFIFO;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A disk-backed cache for live data: RocksDB on local disk, between a connector's in-memory cache and its source.
 * Bounded by size (FIFO compaction drops the oldest files once {@code maxBytes} is reached), written without a
 * write-ahead log (it is a cache: after a crash the connector refills it), and cleared every day at a configured
 * time in a configured zone. Clearing swaps in a fresh, empty store and deletes the old one a little later, so
 * readers never wait. Thread-safe.
 */
public final class DiskCache implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DiskCache.class);

    static {
        RocksDB.loadLibrary();
    }

    /** One generation of the store: its directory and handle. */
    private record Store(Path dir, RocksDB db, Options options) {}

    private final Path root;
    private final long maxBytes;
    private final WriteOptions write = new WriteOptions().setDisableWAL(true);
    private final AtomicReference<Store> store = new AtomicReference<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong resets = new AtomicLong();

    /**
     * @param root directory for the cache (one sub-directory per generation)
     * @param maxBytes disk budget; the oldest data goes first beyond it
     * @param resetAt daily clearing time, or {@code null} for never
     * @param zone the zone of {@code resetAt}
     * @param scheduler runs the nightly clearing and deletes old generations
     */
    public DiskCache(Path root, long maxBytes, LocalTime resetAt, ZoneId zone, ScheduledExecutorService scheduler, Clock clock) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.scheduler = scheduler;
        Files.createDirectories(this.root);
        deleteGenerations(null);              // whatever a previous run left is stale by definition
        store.set(open());
        if (resetAt != null) {
            scheduleReset(resetAt, zone, clock);
        }
    }

    private Store open() throws IOException {
        Path dir = root.resolve("gen-" + System.currentTimeMillis() + "-" + resets.get());
        Files.createDirectories(dir);
        Options o = new Options().setCreateIfMissing(true).setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setCompactionStyle(CompactionStyle.FIFO).setWriteBufferSize(32L * 1024 * 1024)
                .setCompactionOptionsFIFO(new CompactionOptionsFIFO().setMaxTableFilesSize(maxBytes));
        try {
            return new Store(dir, RocksDB.open(o, dir.toString()), o);
        } catch (RocksDBException e) {
            o.close();
            throw new IOException("cannot open disk cache at " + dir + ": " + e.getMessage(), e);
        }
    }

    /** The next daily clearing after {@code now}. */
    static ZonedDateTime nextReset(ZonedDateTime now, LocalTime at) {
        ZonedDateTime today = now.with(at);
        return today.isAfter(now) ? today : today.plusDays(1).with(at);
    }

    private void scheduleReset(LocalTime at, ZoneId zone, Clock clock) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        long delay = Duration.between(now, nextReset(now, at)).toMillis();
        scheduler.schedule(() -> {
            clear();
            scheduleReset(at, zone, clock);   // recomputed each day, so daylight saving changes are honoured
        }, delay, TimeUnit.MILLISECONDS);
    }

    public byte[] get(String key) {
        try {
            byte[] v = store.get().db().get(key.getBytes(StandardCharsets.UTF_8));
            (v == null ? misses : hits).incrementAndGet();
            return v;
        } catch (RocksDBException e) {
            misses.incrementAndGet();
            return null;
        }
    }

    public void put(String key, byte[] value) {
        try {
            store.get().db().put(write, key.getBytes(StandardCharsets.UTF_8), value);
        } catch (RocksDBException e) {
            LOG.debug("disk cache write skipped: {}", e.getMessage());
        }
    }

    public void delete(String key) {
        try {
            store.get().db().delete(write, key.getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            LOG.debug("disk cache delete skipped: {}", e.getMessage());
        }
    }

    /** Empties the cache now: a fresh store takes over at once; the old one is closed and deleted shortly after. */
    public void clear() {
        try {
            resets.incrementAndGet();
            Store fresh = open();
            Store old = store.getAndSet(fresh);
            scheduler.schedule(() -> dispose(old), 30, TimeUnit.SECONDS);
            LOG.info("disk cache {} cleared", root);
        } catch (IOException e) {
            LOG.warn("disk cache {} could not be cleared: {}", root, e.getMessage());
        }
    }

    private static void dispose(Store s) {
        s.db().close();
        s.options().close();
        deleteTree(s.dir());
    }

    private void deleteGenerations(Path keep) throws IOException {
        try (Stream<Path> gens = Files.list(root)) {
            gens.filter(p -> p.getFileName().toString().startsWith("gen-") && !p.equals(keep)).forEach(DiskCache::deleteTree);
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            LOG.debug("could not delete {}: {}", dir, e.getMessage());
        }
    }

    /** Bytes on disk, as RocksDB reports its table files. */
    public long sizeOnDisk() {
        try {
            return store.get().db().getLongProperty("rocksdb.total-sst-files-size");
        } catch (RocksDBException e) {
            return -1;
        }
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public long resets() {
        return resets.get();
    }

    @Override
    public void close() {
        Store s = store.getAndSet(null);
        if (s != null) {
            dispose(s);
        }
        write.close();
    }
}
