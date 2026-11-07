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
package com.ash.drishti.nativ.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiPredicate;
import org.rocksdb.CompactionOptionsFIFO;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.FlushOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/**
 * The native disk store: RocksDB in the folder itself (its files beside nothing else), LZ4-compressed. A cache uses FIFO compaction (the
 * oldest files go past the budget, files past the age limit at the next compaction); state and a sorted store use level compaction (the
 * latest value of each key; overwrites and deletes compacted away). The only class besides this package's tests that names RocksDB.
 */
final class RocksDbStore implements DiskStore {

    private static volatile String loadFailure;
    private static volatile boolean loaded;

    /** Loads the native library once; the reason it failed, or null. */
    static synchronized String load() {
        if (loaded) {
            return null;
        }
        if (loadFailure != null) {
            return loadFailure;
        }
        try {
            RocksDB.loadLibrary();
            loaded = true;
            return null;
        } catch (RuntimeException | LinkageError e) {      // UnsatisfiedLinkError, a platform it does not ship for, a corrupt copy
            loadFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            return loadFailure;
        }
    }

    /** The bundled RocksDB version, or null when it is not loaded. */
    static String version() {
        if (!loaded) {
            return null;
        }
        try {
            RocksDB.Version v = RocksDB.rocksdbVersion();
            return v.getMajor() + "." + v.getMinor() + "." + v.getPatch();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private final Path dir;
    private final RocksDB db;
    private final org.rocksdb.Options options;   // named in full: DiskStore.Options is in scope
    private final CompactionOptionsFIFO fifo;
    private final WriteOptions write;

    private RocksDbStore(Path dir, RocksDB db, org.rocksdb.Options options, CompactionOptionsFIFO fifo, WriteOptions write) {
        this.dir = dir;
        this.db = db;
        this.options = options;
        this.fifo = fifo;
        this.write = write;
    }

    /** Opens (or creates) the store in {@code dir}; null when {@code create} is false and it holds none that opens. */
    static RocksDbStore open(Path dir, DiskStore.Options how) throws IOException {
        CompactionOptionsFIFO fifo = how.kind() == Kind.CACHE ? new CompactionOptionsFIFO().setMaxTableFilesSize(how.maxBytes()) : null;
        org.rocksdb.Options o = new org.rocksdb.Options().setCreateIfMissing(how.create()).setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setWriteBufferSize(how.writeBufferBytes());
        if (fifo != null) {
            o.setCompactionStyle(CompactionStyle.FIFO).setCompactionOptionsFIFO(fifo);
            if (how.ttl() != null && !how.ttl().isZero()) {
                o.setTtl(how.ttl().toSeconds());              // files whose every entry is older go at the next compaction
            }
        } else {
            o.setCompactionStyle(CompactionStyle.LEVEL).setLevelCompactionDynamicLevelBytes(true);
            if (how.kind() == Kind.STATE) {
                o.setMaxTotalWalSize(64L * 1024 * 1024);       // the write-ahead log never grows past 64 MB
            }
        }
        WriteOptions w = new WriteOptions().setDisableWAL(!how.wal()).setSync(how.sync());
        try {
            if (how.create()) {
                Files.createDirectories(dir);
            }
            return new RocksDbStore(dir, RocksDB.open(o, dir.toString()), o, fifo, w);
        } catch (RocksDBException | IOException e) {
            w.close();
            o.close();
            if (fifo != null) {
                fifo.close();
            }
            if (!how.create()) {
                return null;
            }
            throw new IOException(e.getMessage(), e);           // RocksDB's words; the caller says which store
        }
    }

    @Override
    public byte[] get(byte[] key) throws IOException {
        try {
            return db.get(key);
        } catch (RocksDBException e) {
            throw failed(e);
        }
    }

    @Override
    public void put(byte[] key, byte[] value) throws IOException {
        try {
            db.put(write, key, value);
        } catch (RocksDBException e) {
            throw failed(e);
        }
    }

    @Override
    public void putAll(List<byte[][]> entries) throws IOException {
        try (WriteBatch b = new WriteBatch()) {
            for (byte[][] e : entries) {
                b.put(e[0], e[1]);
            }
            db.write(write, b);
        } catch (RocksDBException e) {
            throw failed(e);
        }
    }

    @Override
    public void delete(byte[] key) throws IOException {
        try {
            db.delete(write, key);
        } catch (RocksDBException e) {
            throw failed(e);
        }
    }

    @Override
    public void scan(byte[] from, BiPredicate<byte[], byte[]> visit) {
        try (RocksIterator it = db.newIterator()) {
            if (from == null) {
                it.seekToFirst();
            } else {
                it.seek(from);
            }
            for (; it.isValid(); it.next()) {
                if (!visit.test(it.key(), it.value())) {
                    return;
                }
            }
        }
    }

    @Override
    public void flush() throws IOException {
        try (FlushOptions f = new FlushOptions().setWaitForFlush(true)) {
            db.flush(f);
        } catch (RocksDBException e) {
            throw failed(e);
        }
    }

    @Override
    public void compact() throws IOException {
        try {
            db.compactRange();
        } catch (RocksDBException e) {
            throw failed(e);
        }
    }

    @Override
    public long sizeBytes() {
        try {
            // the table files, and what is still in memory (its write-ahead log is on disk too)
            return db.getLongProperty("rocksdb.total-sst-files-size") + db.getLongProperty("rocksdb.cur-size-all-mem-tables");
        } catch (RocksDBException e) {
            return -1;
        }
    }

    @Override
    public String impl() {
        return "rocksdb";
    }

    @Override
    public void close() {
        write.close();
        db.close();
        options.close();
        if (fifo != null) {
            fifo.close();
        }
    }

    private IOException failed(RocksDBException e) {
        return new IOException(e.getMessage(), e);              // RocksDB's words; the caller says which store
    }
}
