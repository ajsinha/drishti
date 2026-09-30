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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
 * time in a configured zone. Clearing swaps in a fresh, empty store, so readers never wait.
 *
 * <p>Thread safety: any number of threads may read and write at once (RocksDB handles are thread-safe). Each store
 * generation is reference counted: a call enters the current generation before touching it and leaves after, and
 * a retired generation is closed only when its last caller has left. So a clear or a close never frees native
 * memory under a running call, however long that call is delayed. Clearing and closing are serialised by a lock
 * (never held during reads or writes). After {@link #close()} reads miss and writes are dropped.
 */
public final class DiskCache implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DiskCache.class);

    static {
        RocksDB.loadLibrary();
    }

    /**
     * One generation of the store. {@code users} counts the callers inside it plus one for the cache's own
     * reference; it reaches zero only after the generation is retired and every caller has left.
     */
    private static final class Store {
        final Path dir;
        final RocksDB db;
        final Options options;
        final CompactionOptionsFIFO fifo;
        final WriteOptions write = new WriteOptions().setDisableWAL(true);
        final AtomicInteger users = new AtomicInteger(1);

        Store(Path dir, RocksDB db, Options options, CompactionOptionsFIFO fifo) {
            this.dir = dir;
            this.db = db;
            this.options = options;
            this.fifo = fifo;
        }

        /** Enters unless the generation is already being closed. */
        boolean enter() {
            for (int n = users.get(); n > 0; n = users.get()) {
                if (users.compareAndSet(n, n + 1)) {
                    return true;
                }
            }
            return false;
        }

        /** @return true when this was the last user: the caller must dispose of the generation */
        boolean leave() {
            return users.decrementAndGet() == 0;
        }

        /** Set when a persistent cache closes: the files stay for the next run, whoever disposes of the store. */
        volatile boolean keepFiles;

        void dispose() {
            write.close();
            db.close();
            options.close();
            fifo.close();
            if (!keepFiles) {
                deleteTree(dir);
            }
        }
    }

    private final Path root;
    private final long maxBytes;
    private final AtomicReference<Store> store = new AtomicReference<>();
    private final ReentrantLock lifecycle = new ReentrantLock();   // clear and close; not synchronized, so virtual threads never pin
    private volatile boolean closed;
    private final boolean persistent;       // closing keeps the files, for the next run
    private final ScheduledExecutorService scheduler;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong resets = new AtomicLong();

    /**
     * @param root directory for the cache (one sub-directory per generation)
     * @param maxBytes disk budget; the oldest data goes first beyond it
     * @param resetAt daily clearing time, or {@code null} for never
     * @param zone the zone of {@code resetAt}
     * @param scheduler runs the nightly clearing and disposes of retired generations
     */
    public DiskCache(Path root, long maxBytes, LocalTime resetAt, ZoneId zone, ScheduledExecutorService scheduler, Clock clock) throws IOException {
        this(root, maxBytes, resetAt, zone, scheduler, clock, false);
    }

    /**
     * @param persistent keep what a previous run stored (the newest generation is reopened) instead of starting empty:
     *     for sources that cannot replay their history, such as message queues, whose latest state lives here
     */
    public DiskCache(Path root, long maxBytes, LocalTime resetAt, ZoneId zone, ScheduledExecutorService scheduler, Clock clock,
            boolean persistent) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.scheduler = scheduler;
        this.persistent = persistent;
        Files.createDirectories(this.root);
        Path previous = persistent ? newestGeneration() : null;
        if (previous == null) {
            deleteGenerations();              // a cache: whatever a previous run left is stale by definition
            store.set(open());
        } else {
            try (Stream<Path> gens = Files.list(this.root)) {   // keep the newest, drop older leftovers
                gens.filter(g -> g.getFileName().toString().startsWith("gen-") && !g.equals(previous)).forEach(DiskCache::deleteTree);
            }
            store.set(open(previous));
        }
        if (resetAt != null) {
            scheduleReset(resetAt, zone, clock);
        }
    }

    private Path newestGeneration() throws IOException {
        try (Stream<Path> gens = Files.list(root)) {
            return gens.filter(g -> g.getFileName().toString().startsWith("gen-") && Files.isDirectory(g))
                    .max(Comparator.comparingLong(g -> Long.parseLong(g.getFileName().toString().split("-")[1]))).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Store open() throws IOException {
        return open(root.resolve("gen-" + System.currentTimeMillis() + "-" + generation.incrementAndGet()));
    }

    private Store open(Path dir) throws IOException {
        Files.createDirectories(dir);
        CompactionOptionsFIFO fifo = new CompactionOptionsFIFO().setMaxTableFilesSize(maxBytes);
        Options o = new Options().setCreateIfMissing(true).setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setCompactionStyle(CompactionStyle.FIFO).setWriteBufferSize(32L * 1024 * 1024).setCompactionOptionsFIFO(fifo);
        try {
            return new Store(dir, RocksDB.open(o, dir.toString()), o, fifo);
        } catch (RocksDBException e) {
            o.close();
            fifo.close();
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
        try {
            scheduler.schedule(() -> {
                if (!closed) {
                    clear();
                    scheduleReset(at, zone, clock);   // recomputed each day, so daylight saving changes are honoured
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debug("disk cache {}: scheduler stopped, no further clearing", root);
        }
    }

    /** The current generation, entered; {@code null} once closed. The caller must {@link #leave} it. */
    private Store enter() {
        while (true) {
            Store s = store.get();
            if (s == null) {
                return null;
            }
            if (s.enter()) {
                return s;
            }
            // retired between the read and the enter: the reference already points at its successor, so retry
        }
    }

    private void leave(Store s) {
        if (s.leave()) {
            dispose(s);
        }
    }

    public byte[] get(String key) {
        Store s = enter();
        if (s == null) {
            misses.incrementAndGet();
            return null;
        }
        try {
            byte[] v = s.db.get(key.getBytes(StandardCharsets.UTF_8));
            (v == null ? misses : hits).incrementAndGet();
            return v;
        } catch (RocksDBException e) {
            misses.incrementAndGet();
            return null;
        } finally {
            leave(s);
        }
    }

    public void put(String key, byte[] value) {
        Store s = enter();
        if (s == null) {
            return;
        }
        try {
            s.db.put(s.write, key.getBytes(StandardCharsets.UTF_8), value);
        } catch (RocksDBException e) {
            LOG.debug("disk cache write skipped: {}", e.getMessage());
        } finally {
            leave(s);
        }
    }

    public void delete(String key) {
        Store s = enter();
        if (s == null) {
            return;
        }
        try {
            s.db.delete(s.write, key.getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            LOG.debug("disk cache delete skipped: {}", e.getMessage());
        } finally {
            leave(s);
        }
    }

    /** Empties the cache now: a fresh store takes over at once; the old one closes when its last caller leaves. */
    public void clear() {
        lifecycle.lock();
        try {
            if (closed) {
                return;
            }
            Store old = store.getAndSet(open());
            resets.incrementAndGet();
            leave(old);                       // drop the cache's own reference
            LOG.info("disk cache {} cleared", root);
        } catch (IOException e) {
            LOG.warn("disk cache {} could not be cleared: {}", root, e.getMessage());
        } finally {
            lifecycle.unlock();
        }
    }

    /** Closes and deletes a generation off the caller's thread when the scheduler still runs. */
    private void dispose(Store s) {
        try {
            scheduler.execute(s::dispose);
        } catch (RejectedExecutionException e) {
            s.dispose();
        }
    }

    private void deleteGenerations() throws IOException {
        try (Stream<Path> gens = Files.list(root)) {
            gens.filter(p -> p.getFileName().toString().startsWith("gen-")).forEach(DiskCache::deleteTree);
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            LOG.debug("could not delete {}: {}", dir, e.getMessage());
        }
    }

    /** Calls {@code visit} for every stored entry, in key order (for rebuilding an index after a restart). */
    public void forEach(java.util.function.BiConsumer<String, byte[]> visit) {
        Store s = enter();
        if (s == null) {
            return;
        }
        try (org.rocksdb.RocksIterator it = s.db.newIterator()) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                visit.accept(new String(it.key(), StandardCharsets.UTF_8), it.value());
            }
        } finally {
            leave(s);
        }
    }

    /** Bytes on disk, as RocksDB reports its table files; -1 once closed. */
    public long sizeOnDisk() {
        Store s = enter();
        if (s == null) {
            return -1;
        }
        try {
            return s.db.getLongProperty("rocksdb.total-sst-files-size");
        } catch (RocksDBException e) {
            return -1;
        } finally {
            leave(s);
        }
    }

    /** Callers inside the current generation, for tests and diagnostics (excludes the cache's own reference). */
    int activeCallers() {
        Store s = store.get();
        return s == null ? 0 : Math.max(0, s.users.get() - 1);
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

    public boolean isClosed() {
        return closed;
    }

    /** Stops the cache: later calls miss or are dropped; the store closes once calls in flight have left. */
    @Override
    public void close() {
        lifecycle.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            Store s = store.getAndSet(null);
            if (s != null) {
                s.keepFiles = persistent;
                if (s.leave()) {
                    s.dispose();              // nobody inside: close here (a plain cache's files are gone when close returns)
                }
            }
        } finally {
            lifecycle.unlock();
        }
    }
}
