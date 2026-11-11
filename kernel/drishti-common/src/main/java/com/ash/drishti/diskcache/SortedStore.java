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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A sorted key-value store on local disk (RocksDB) for data built from other data and rebuilt at will: one folder, written
 * once by one builder, then read by any number of threads, then deleted whole. Nothing is ever dropped while it is open
 * (unlike {@link DiskCache}, which drops its oldest files past a budget), so a reader sees everything written; there is no
 * write-ahead log (after a crash the folder is deleted and built again). Keys are UTF-8 strings, read back in their sort order.
 *
 * <p>Writes are batched ({@link #put}, then {@link #finish}); reads ({@link #get}, {@link #scan}) are for after {@link #finish}.
 * {@link #close} closes it and deletes its folder once no read is running.
 */
public final class SortedStore implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SortedStore.class);
    private static final int BATCH = 10_000;

    static {
        RocksDB.loadLibrary();
    }

    private final Path dir;
    private final Options options;
    private final RocksDB db;
    private final WriteOptions write = new WriteOptions().setDisableWAL(true);
    private WriteBatch batch = new WriteBatch();
    private int batched;
    private final AtomicInteger readers = new AtomicInteger();
    private volatile boolean closing;
    private boolean closed;

    private SortedStore(Path dir, Options options, RocksDB db) {
        this.dir = dir;
        this.options = options;
        this.db = db;
    }

    /** A new, empty store in {@code dir}, which must not exist or be empty (a left-over folder is removed first). */
    public static SortedStore create(Path dir) throws IOException {
        deleteFolder(dir);
        Files.createDirectories(dir);
        Options o = new Options().setCreateIfMissing(true).setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setWriteBufferSize(64L * 1024 * 1024).setCompactionStyle(CompactionStyle.LEVEL).setLevelCompactionDynamicLevelBytes(true);
        try {
            return new SortedStore(dir, o, RocksDB.open(o, dir.toString()));
        } catch (RocksDBException e) {
            o.close();
            throw new IOException("cannot open a sorted store at " + dir + ": " + e.getMessage(), e);
        }
    }

    /** A store written earlier (kept across a restart), or null when {@code dir} holds none that opens. */
    public static SortedStore open(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Options o = new Options().setCreateIfMissing(false).setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setCompactionStyle(CompactionStyle.LEVEL).setLevelCompactionDynamicLevelBytes(true);
        try {
            return new SortedStore(dir, o, RocksDB.open(o, dir.toString()));
        } catch (RocksDBException e) {
            o.close();
            return null;
        }
    }

    /** Adds one entry (batched: visible to readers after {@link #finish}). */
    public void put(String key, byte[] value) {
        try {
            batch.put(key.getBytes(StandardCharsets.UTF_8), value);
            if (++batched >= BATCH) {
                flushBatch();
            }
        } catch (RocksDBException e) {
            throw new UncheckedIOException(new IOException("sorted store " + dir + ": " + e.getMessage(), e));
        }
    }

    /** Writes what is batched and flushes it to files (there is no write-ahead log): the store is ready to read and outlives a restart. */
    public void finish() {
        try {
            flushBatch();
            try (org.rocksdb.FlushOptions f = new org.rocksdb.FlushOptions().setWaitForFlush(true)) {
                db.flush(f);
            }
        } catch (RocksDBException e) {
            throw new UncheckedIOException(new IOException("sorted store " + dir + ": " + e.getMessage(), e));
        }
    }

    private void flushBatch() throws RocksDBException {
        if (batched > 0) {
            db.write(write, batch);
            batch.close();
            batch = new WriteBatch();
            batched = 0;
        }
    }

    /** The value of {@code key}, or null. */
    public byte[] get(String key) {
        if (!enter()) {
            return null;
        }
        try {
            return db.get(key.getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new UncheckedIOException(new IOException("sorted store " + dir + ": " + e.getMessage(), e));
        } finally {
            leave();
        }
    }

    /**
     * Every entry whose key starts with {@code prefix}, in key order, until {@code visit} answers false. Returns how many
     * entries were visited.
     */
    public int scan(String prefix, BiPredicate<String, byte[]> visit) {
        if (!enter()) {
            return 0;
        }
        int n = 0;
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        try (ReadOptions ro = new ReadOptions().setPrefixSameAsStart(false); RocksIterator it = db.newIterator(ro)) {
            for (it.seek(p); it.isValid(); it.next()) {
                byte[] k = it.key();
                if (!startsWith(k, p)) {
                    break;
                }
                n++;
                if (!visit.test(new String(k, StandardCharsets.UTF_8), it.value())) {
                    break;
                }
            }
            return n;
        } finally {
            leave();
        }
    }

    private static boolean startsWith(byte[] k, byte[] p) {
        if (k.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (k[i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    /** The bytes its files take on disk. */
    public long sizeOnDisk() {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(f -> {
                try {
                    return Files.size(f);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    public Path dir() {
        return dir;
    }

    private boolean enter() {
        readers.incrementAndGet();
        if (closing) {
            leave();
            return false;
        }
        return true;
    }

    private void leave() {
        if (readers.decrementAndGet() == 0 && closing) {
            release();
        }
    }

    /** Closes the store and keeps its folder (a server stopping: the next start may serve it again). */
    public void closeKeepingFiles() {
        keep = true;
        close();
    }

    private volatile boolean keep;

    /** Closes the store and deletes its folder, now or when the last running read finishes. */
    @Override
    public void close() {
        closing = true;
        if (readers.get() == 0) {
            release();
        }
    }

    private synchronized void release() {
        if (closed || readers.get() > 0) {
            return;
        }
        closed = true;
        batch.close();
        write.close();
        db.close();
        options.close();
        if (keep) {
            return;
        }
        try {
            deleteFolder(dir);
        } catch (IOException e) {
            LOG.warn("sorted store {}: its folder could not be deleted: {}", dir, e.toString());
        }
    }

    /**
     * Whether another process has the store in {@code dir} open: RocksDB holds a lock on its {@code LOCK} file while open (an fcntl lock,
     * which {@link java.nio.channels.FileChannel#tryLock} sees on Linux and macOS). A folder without one, or whose lock is free, is not in use.
     */
    public static boolean inUse(Path dir) {
        Path lock = dir.resolve("LOCK");
        if (!Files.isRegularFile(lock)) {
            return false;
        }
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock l = ch.tryLock();
            if (l == null) {
                return true;
            }
            l.release();
            return false;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Deletes a store's folder (all of it); nothing when it does not exist. */
    public static void deleteFolder(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        }
    }
}
