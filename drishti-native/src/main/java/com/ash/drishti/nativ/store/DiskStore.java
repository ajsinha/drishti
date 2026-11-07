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
import java.time.Duration;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * A key-value store on local disk, keys in unsigned byte order (DRISHTI_NATIVE.md section 2, the disk store). Two implementations:
 * {@link RocksDbStore} (native) and {@link MvStore} (H2's MVStore, pure Java), chosen by {@link DiskStores#open}; callers never see which
 * beyond {@link #impl()}. Each keeps its own files in the folder it is given, so a folder written by one is never read by the other
 * (a store whose implementation changed starts empty: the disk store holds caches and state that is rebuilt).
 *
 * <p>Thread safety: any number of threads may read and write at once. {@link #close} must not run while a call is inside; the callers
 * (the disk cache and the sorted store) count their callers for that.
 */
public interface DiskStore extends AutoCloseable {

    /** What the store is for; it decides how it compacts, buffers and keeps its data. */
    enum Kind {
        /** A cache bounded by size (the oldest data goes past {@link Options#maxBytes}) and optionally by age ({@link Options#ttl}). */
        CACHE,
        /** The latest value of every key, kept whatever its size (state that cannot be replayed). */
        STATE,
        /** Written once in batches by one builder, then read; no write-ahead log. */
        SORTED
    }

    /**
     * How a store is opened.
     *
     * @param kind what it is for
     * @param maxBytes a cache's disk budget (ignored by the other kinds)
     * @param ttl a cache's age limit, or null for none
     * @param wal writes go through a write-ahead log (survive a crash of the process)
     * @param sync each write is synced to disk (survives a power loss)
     * @param writeBufferBytes the in-memory write buffer before a flush to files
     * @param create create the store when the folder holds none (false: {@link DiskStores#open} answers null instead)
     */
    record Options(Kind kind, long maxBytes, Duration ttl, boolean wal, boolean sync, long writeBufferBytes, boolean create) {

        /** A cache: bounded by size and age, no write-ahead log unless asked. */
        public static Options cache(long maxBytes, Duration ttl, boolean wal, boolean sync) {
            return new Options(Kind.CACHE, maxBytes, ttl, wal, sync, ttl != null ? 8L << 20 : 32L << 20, true);
        }

        /** State: the latest value of every key, kept. */
        public static Options state(long maxBytes, boolean wal, boolean sync) {
            return new Options(Kind.STATE, maxBytes, null, wal, sync, 32L << 20, true);
        }

        /** A sorted store written in batches; {@code create} false opens one written earlier, or nothing. */
        public static Options sorted(boolean create) {
            return new Options(Kind.SORTED, 0, null, false, false, 64L << 20, create);
        }
    }

    /** The value of {@code key}, or null. */
    byte[] get(byte[] key) throws IOException;

    /** Writes one entry. */
    void put(byte[] key, byte[] value) throws IOException;

    /** Writes the entries as one batch. */
    void putAll(List<byte[][]> entries) throws IOException;

    /** Deletes one entry (nothing when absent). */
    void delete(byte[] key) throws IOException;

    /** Every entry whose key is at or after {@code from} (null: the first), in key order, until {@code visit} answers false. */
    void scan(byte[] from, BiPredicate<byte[], byte[]> visit) throws IOException;

    /** What is written is on disk in files (a store closed after it reopens with it). */
    void flush() throws IOException;

    /** Gives back the space of deleted, overwritten and expired entries now. */
    void compact() throws IOException;

    /** The bytes the store holds on disk and in its write buffers; -1 when it cannot say. */
    long sizeBytes();

    /** {@code rocksdb} or {@code mvstore}. */
    String impl();

    /** Closes it; its files stay (the caller deletes the folder when it wants). */
    @Override
    void close();
}
