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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import org.h2.mvstore.Cursor;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;
import org.h2.mvstore.type.ByteArrayDataType;

/**
 * The pure-Java disk store (DRISHTI_NATIVE.md decision N4): H2's MVStore, one file ({@value #FILE}) in the folder, keys in unsigned byte
 * order as RocksDB keeps them, pages compressed. What differs from RocksDB, by design of a fallback:
 *
 * <ul>
 *   <li>a cache past its budget is emptied whole (RocksDB drops its oldest files): a cache refills from its source;
 *   <li>a cache's age limit is kept per entry (each value carries the time it was written) and checked on every read; {@link #compact}
 *       removes the expired entries, as RocksDB's compaction drops whole files;
 *   <li>writes without a write-ahead log are committed by MVStore's background writer within about a second; with one ({@code wal}),
 *       each write is committed at once, and with {@code sync} the file is synced too.
 * </ul>
 */
final class MvStore implements DiskStore {

    /** The store's file in its folder; RocksDB never writes a file of that name. */
    static final String FILE = "drishti-store.mv.db";

    private static final String MAP = "kv";
    private static final int STAMP = Long.BYTES;

    /** Byte arrays compared unsigned, as RocksDB's default comparator does, so scans visit keys in the same order. */
    static final class UnsignedBytes extends BasicDataType<byte[]> {
        static final UnsignedBytes INSTANCE = new UnsignedBytes();

        @Override
        public int compare(byte[] a, byte[] b) {
            return Arrays.compareUnsigned(a, b);
        }

        @Override
        public int getMemory(byte[] v) {
            return ByteArrayDataType.INSTANCE.getMemory(v);
        }

        @Override
        public void write(WriteBuffer buff, byte[] v) {
            ByteArrayDataType.INSTANCE.write(buff, v);
        }

        @Override
        public byte[] read(ByteBuffer buff) {
            return ByteArrayDataType.INSTANCE.read(buff);
        }

        @Override
        public byte[][] createStorage(int size) {
            return new byte[size][];
        }
    }

    private final Path file;
    private final MVStore store;
    private final MVMap<byte[], byte[]> map;
    private final DiskStore.Options how;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final AtomicLong bytes = new AtomicLong();       // keys and values held, for a cache's budget

    private MvStore(Path file, MVStore store, DiskStore.Options how, LongSupplier clock) {
        this.file = file;
        this.store = store;
        this.how = how;
        this.clock = clock;
        this.ttlMillis = how.kind() == Kind.CACHE && how.ttl() != null && !how.ttl().isZero() ? how.ttl().toMillis() : 0;
        this.map = store.openMap(MAP, new MVMap.Builder<byte[], byte[]>().keyType(UnsignedBytes.INSTANCE).valueType(ByteArrayDataType.INSTANCE));
        if (how.kind() == Kind.CACHE) {
            for (Cursor<byte[], byte[]> c = map.cursor(null); c.hasNext(); ) {
                byte[] k = c.next();
                bytes.addAndGet(k.length + c.getValue().length);
            }
        }
    }

    /** Opens (or creates) the store in {@code dir}; null when {@code create} is false and it holds none that opens. */
    static MvStore open(Path dir, DiskStore.Options how, LongSupplier clock) throws IOException {
        Path file = dir.resolve(FILE);
        if (!how.create() && !Files.isRegularFile(file)) {
            return null;
        }
        try {
            Files.createDirectories(dir);
            MVStore.Builder b = new MVStore.Builder().fileName(file.toString()).compress()
                    .autoCommitBufferSize((int) Math.max(1024, how.writeBufferBytes() >> 10));      // in KB
            return new MvStore(file, b.open(), how, clock);
        } catch (RuntimeException | IOException e) {    // MVStoreException: a corrupt file, or another process holds its lock
            if (!how.create()) {
                return null;
            }
            throw new IOException(e.getMessage(), e);           // MVStore's words; the caller says which store
        }
    }

    @Override
    public byte[] get(byte[] key) throws IOException {
        byte[] v = guard(() -> map.get(key));
        if (v == null || ttlMillis == 0) {
            return v;
        }
        if (expired(v)) {
            remove(key);
            return null;
        }
        return Arrays.copyOfRange(v, STAMP, v.length);
    }

    @Override
    public void put(byte[] key, byte[] value) throws IOException {
        byte[] v = stamped(value);
        byte[] old = guard(() -> map.put(key, v));
        account(key, v, old);
        committed();
    }

    @Override
    public void putAll(List<byte[][]> entries) throws IOException {
        for (byte[][] e : entries) {
            byte[] v = stamped(e[1]);
            byte[] old = guard(() -> map.put(e[0], v));
            account(e[0], v, old);
        }
        committed();
    }

    @Override
    public void delete(byte[] key) throws IOException {
        remove(key);
        committed();
    }

    @Override
    public void scan(byte[] from, BiPredicate<byte[], byte[]> visit) throws IOException {
        Cursor<byte[], byte[]> c = guard(() -> map.cursor(from));
        while (c.hasNext()) {
            byte[] k = c.next();
            byte[] v = c.getValue();
            if (ttlMillis > 0) {
                if (expired(v)) {
                    continue;
                }
                v = Arrays.copyOfRange(v, STAMP, v.length);
            }
            if (!visit.test(k, v)) {
                return;
            }
        }
    }

    @Override
    public void flush() throws IOException {
        guard(() -> {
            store.commit();
            store.sync();
            return null;
        });
    }

    @Override
    public void compact() throws IOException {
        if (ttlMillis > 0) {
            for (Cursor<byte[], byte[]> c = map.cursor(null); c.hasNext(); ) {
                byte[] k = c.next();
                if (expired(c.getValue())) {
                    remove(k);
                }
            }
        }
        guard(() -> {
            store.commit();
            store.compactFile(1_000);
            return null;
        });
    }

    @Override
    public long sizeBytes() {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public String impl() {
        return "mvstore";
    }

    @Override
    public void close() {
        if (!store.isClosed()) {
            store.close();
        }
    }

    private void remove(byte[] key) throws IOException {
        byte[] old = guard(() -> map.remove(key));
        if (old != null && how.kind() == Kind.CACHE) {
            bytes.addAndGet(-(key.length + old.length));
        }
    }

    /** A cache past its budget is emptied whole: it refills from its source. */
    private void account(byte[] key, byte[] value, byte[] old) {
        if (how.kind() != Kind.CACHE) {
            return;
        }
        long n = bytes.addAndGet(key.length + value.length - (old == null ? 0 : key.length + old.length));
        if (how.maxBytes() > 0 && n > how.maxBytes()) {
            map.clear();
            bytes.set(0);
        }
    }

    private void committed() throws IOException {
        if (how.wal() || how.sync()) {
            guard(() -> {
                store.commit();
                if (how.sync()) {
                    store.sync();
                }
                return null;
            });
        }
    }

    private byte[] stamped(byte[] value) {
        if (ttlMillis == 0) {
            return value;
        }
        byte[] v = new byte[STAMP + value.length];
        ByteBuffer.wrap(v).putLong(clock.getAsLong());
        System.arraycopy(value, 0, v, STAMP, value.length);
        return v;
    }

    private boolean expired(byte[] stamped) {
        return stamped.length < STAMP || clock.getAsLong() - ByteBuffer.wrap(stamped).getLong() > ttlMillis;
    }

    private interface Call<T> {
        T call();
    }

    /** MVStore fails with unchecked exceptions; the store's contract is IOException. */
    private <T> T guard(Call<T> c) throws IOException {
        try {
            return c.call();
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);       // MVStore's words; the caller says which store
        }
    }
}
