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
package com.ash.drishti.lakeio.mem;

import com.ash.drishti.lakeio.FileEntry;
import com.ash.drishti.lakeio.RangeReader;
import com.ash.drishti.lakeio.Storage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A lake held in memory, never on a disk: the paths of one store, {@code mem://<store>/<path>}, answered from a {@link ChunkPool} of
 * direct buffers under a hard cap (docs/architecture/IN_MEMORY_LAKE.md, section 3). The {@link Storage} contract is met exactly: sorted
 * listings, status, positional reads over the chunks, whole-file creates visible when closed, a <b>truly exclusive</b>
 * {@link #createIfAbsent} (of two commits of one Delta version exactly one wins) and deletes.
 *
 * <p>Only the listing is on the heap (a sorted map of names to chunk arrays, about 200 bytes a file). Files are reference-counted: a
 * reader that opened a file keeps its chunks until it closes, however soon the file is deleted or replaced, so readers on an old version
 * finish on it. A write that needs a chunk past the cap first asks the store's {@link RoomMaker} (the eviction ladder, section 6); if that
 * frees too little the write fails with {@link MemoryStoreFullException} and its chunks go back: since a Delta commit is the last file
 * written, the table stays whole at its previous version.
 *
 * <p>Thread-safe. A restart empties it, by design.
 */
public final class MemoryStorage implements Storage {

    /** The scheme of every path a memory store answers. */
    public static final String SCHEME = "mem";

    /** What frees memory when a write needs it: the eviction ladder. */
    @FunctionalInterface
    public interface RoomMaker {
        /**
         * Frees at least {@code bytes} if it can, never touching the table {@code writing} is in.
         *
         * @return true when something was freed (the write tries again)
         */
        boolean makeRoom(MemoryStorage store, long bytes, String writing);
    }

    private final String name;
    private final ChunkPool pool;
    private final ConcurrentSkipListMap<String, MemFile> files = new ConcurrentSkipListMap<>();
    private volatile String contract = "";
    private volatile RoomMaker roomMaker;
    private final AtomicLong refusals = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();
    private volatile String lastRefusal;

    MemoryStorage(String name, long capBytes, int chunkBytes) {
        this.name = name;
        this.pool = new ChunkPool(name, capBytes, chunkBytes);
    }

    /** The store's name (the authority of its paths). */
    public String name() {
        return name;
    }

    /** The chunk pool: cap, use, allocation. */
    public ChunkPool pool() {
        return pool;
    }

    /** The contract the store's data is held under (shown on every refusal), or blank. */
    public String contract() {
        return contract;
    }

    void contract(String c) {
        this.contract = c == null ? "" : c.trim();
    }

    /** Sets what frees memory when a write needs it (the server's eviction ladder); null: a full store refuses at once. */
    public void roomMaker(RoomMaker r) {
        this.roomMaker = r;
    }

    // ------------------------------------------------------------------------------------------------------------ paths

    /** {@code mem://<store>/a/b} → {@code a/b} (no slashes at either end, none doubled). */
    String key(String path) throws IOException {
        String p = path.trim();
        String prefix = SCHEME + "://";
        if (!p.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new IOException("not a memory path: " + path);
        }
        String rest = p.substring(prefix.length());
        int slash = rest.indexOf('/');
        String store = slash < 0 ? rest : rest.substring(0, slash);
        if (!store.equals(name)) {
            throw new IOException(path + " is not in memory store " + name);
        }
        String rel = slash < 0 ? "" : rest.substring(slash + 1);
        StringBuilder b = new StringBuilder(rel.length());
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                throw new IOException("a memory path may not climb out of its folder: " + path);
            }
            if (b.length() > 0) {
                b.append('/');
            }
            b.append(seg);
        }
        return b.toString();
    }

    private String full(String key) {
        return key.isEmpty() ? SCHEME + "://" + name : SCHEME + "://" + name + "/" + key;
    }

    @Override
    public String qualify(String path) throws IOException {
        return full(key(path));
    }

    // ------------------------------------------------------------------------------------------------------------ reads

    @Override
    public List<FileEntry> listFrom(String path) throws IOException {
        String k = key(path);
        int slash = k.lastIndexOf('/');
        String parent = slash < 0 ? "" : k.substring(0, slash);
        String from = slash < 0 ? k : k.substring(slash + 1);
        String prefix = parent.isEmpty() ? "" : parent + "/";
        if (!parent.isEmpty() && !exists(prefix)) {
            throw new FileNotFoundException("no such file or directory: " + full(parent));
        }
        List<FileEntry> out = new ArrayList<>();
        for (Map.Entry<String, MemFile> e : files.tailMap(prefix + from, true).entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(prefix)) {
                break;
            }
            if (key.indexOf('/', prefix.length()) >= 0) {
                continue;                                        // a file deeper down, not of this folder
            }
            MemFile f = e.getValue();
            out.add(new FileEntry(full(key), f.length, f.modified));
        }
        return out;
    }

    private boolean exists(String prefix) {
        String next = files.ceilingKey(prefix);
        return next != null && next.startsWith(prefix);
    }

    @Override
    public FileEntry status(String path) throws IOException {
        String k = key(path);
        MemFile f = files.get(k);
        if (f != null) {
            return new FileEntry(full(k), f.length, f.modified);
        }
        if (k.isEmpty() || exists(k + "/")) {
            return new FileEntry(full(k), 0, System.currentTimeMillis());       // a folder, as local disk answers one
        }
        throw new FileNotFoundException("no such file or directory: " + path);
    }

    @Override
    public RangeReader open(String path, long knownLength) throws IOException {
        String k = key(path);
        for (int attempt = 0; attempt < 3; attempt++) {
            MemFile f = files.get(k);
            if (f == null) {
                throw new FileNotFoundException("no such file in memory store " + name + ": " + path);
            }
            if (f.retain()) {
                f.lastRead = System.currentTimeMillis();
                return new ChunkReader(f, knownLength);
            }
            // replaced between the lookup and the retain: look again
        }
        throw new FileNotFoundException("the file was replaced while it was being opened: " + path);
    }

    @Override
    public List<String> directories(String path) throws IOException {
        String k = key(path);
        String prefix = k.isEmpty() ? "" : k + "/";
        TreeSet<String> out = new TreeSet<>();
        for (String key : files.tailMap(prefix, true).keySet()) {
            if (!key.startsWith(prefix)) {
                break;
            }
            int slash = key.indexOf('/', prefix.length());
            if (slash > 0) {
                out.add(key.substring(prefix.length(), slash));
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        String k = key(path);
        return k.isEmpty() ? !files.isEmpty() : exists(k + "/");
    }

    // ------------------------------------------------------------------------------------------------------------ writes

    @Override
    public OutputStream create(String path) throws IOException {
        return new ChunkOut(key(path), false);
    }

    @Override
    public void put(String path, byte[] content) throws IOException {
        try (OutputStream out = create(path)) {
            out.write(content);
        }
    }

    @Override
    public boolean createIfAbsent(String path, byte[] content) throws IOException {
        String k = key(path);
        if (files.containsKey(k)) {
            return false;
        }
        ChunkOut out = new ChunkOut(k, true);
        out.write(content, 0, content.length);
        out.close();
        return out.won;
    }

    /** Always: the claim on a name is one atomic {@code putIfAbsent} of the listing. */
    @Override
    public boolean exclusiveCreates() {
        return true;
    }

    @Override
    public boolean delete(String path) throws IOException {
        MemFile f = files.remove(key(path));
        if (f == null) {
            return false;
        }
        f.release(pool);
        return true;
    }

    /** Deletes every file under {@code path} (a whole table, evicted); the bytes they held. */
    public long deleteUnder(String path) throws IOException {
        String k = key(path);
        String prefix = k.isEmpty() ? "" : k + "/";
        long bytes = 0;
        ConcurrentNavigableMap<String, MemFile> under = files.subMap(prefix, true, prefix + Character.MAX_VALUE, true);
        for (String key : new ArrayList<>(under.keySet())) {
            MemFile f = files.remove(key);
            if (f != null) {
                bytes += f.chunkBytes();
                f.release(pool);
            }
        }
        return bytes;
    }

    // ------------------------------------------------------------------------------------------------------------ what it holds

    /** The chunk bytes held by the files under {@code path}. */
    public long bytesUnder(String path) throws IOException {
        long n = 0;
        for (MemFile f : under(path)) {
            n += f.chunkBytes();
        }
        return n;
    }

    /** The files' own lengths under {@code path} (what the same files weigh on a disk). */
    public long lengthUnder(String path) throws IOException {
        long n = 0;
        for (MemFile f : under(path)) {
            n += f.length;
        }
        return n;
    }

    /** The files under {@code path}. */
    public int filesUnder(String path) throws IOException {
        return under(path).size();
    }

    /** When a file under {@code path} was last opened for reading (0: never). */
    public long lastReadUnder(String path) throws IOException {
        long t = 0;
        for (MemFile f : under(path)) {
            t = Math.max(t, f.lastRead);
        }
        return t;
    }

    private List<MemFile> under(String path) throws IOException {
        String k = key(path);
        String prefix = k.isEmpty() ? "" : k + "/";
        return new ArrayList<>(files.subMap(prefix, true, prefix + Character.MAX_VALUE, true).values());
    }

    /** Files in the store. */
    public int fileCount() {
        return files.size();
    }

    /** Writes refused because the store was full. */
    public long refusals() {
        return refusals.get();
    }

    /** Why the last write was refused, or null. */
    public String lastRefusal() {
        return lastRefusal;
    }

    /** Bytes written since the start. */
    public long bytesWritten() {
        return bytesWritten.get();
    }

    /** Empties the store (tests; a server stop). */
    public void clear() {
        for (String k : new ArrayList<>(files.keySet())) {
            MemFile f = files.remove(k);
            if (f != null) {
                f.release(pool);
            }
        }
    }

    private ByteBuffer chunk(String writing) throws IOException {
        ByteBuffer b = pool.take();
        RoomMaker r = roomMaker;
        for (int i = 0; b == null && r != null && i < 8; i++) {
            if (!r.makeRoom(this, Math.max(pool.chunkBytes(), 8L * pool.chunkBytes()), full(writing))) {
                break;
            }
            b = pool.take();
        }
        if (b == null) {
            refusals.incrementAndGet();
            String why = pool.directLimitProblem();
            String msg = "memory store " + name + " is full (" + (pool.inUseBytes() >> 20) + " of " + (pool.capBytes() >> 20) + " MB in use)"
                    + (contract.isEmpty() ? "" : "; its data is held in memory only (contract " + contract + ")")
                    + (why != null && pool.allocatedBytes() + pool.chunkBytes() <= pool.capBytes() ? "; " + why : "")
                    + ": the write was refused and the table stays at its previous version";
            lastRefusal = msg;
            throw new MemoryStoreFullException(msg);
        }
        return b;
    }

    /** One file: its chunks, its length, its readers. */
    static final class MemFile {
        final ByteBuffer[] chunks;
        final long length;
        final long modified;
        final int chunkSize;
        final AtomicInteger refs = new AtomicInteger(1);          // the listing's own reference, plus one per open reader
        volatile long lastRead;

        MemFile(ByteBuffer[] chunks, long length, int chunkSize) {
            this.chunks = chunks;
            this.length = length;
            this.chunkSize = chunkSize;
            this.modified = System.currentTimeMillis();
        }

        long chunkBytes() {
            return (long) chunks.length * chunkSize;
        }

        boolean retain() {
            while (true) {
                int r = refs.get();
                if (r <= 0) {
                    return false;
                }
                if (refs.compareAndSet(r, r + 1)) {
                    return true;
                }
            }
        }

        void release(ChunkPool pool) {
            if (refs.decrementAndGet() == 0) {
                for (int i = 0; i < chunks.length; i++) {
                    pool.give(chunks[i]);
                    chunks[i] = null;
                }
            }
        }
    }

    /** Positional reads over a file's chunks; a read that spans two chunks is the only copy across them. */
    private final class ChunkReader implements RangeReader {
        private final MemFile f;
        private final long knownLength;
        private boolean closed;

        ChunkReader(MemFile f, long knownLength) {
            this.f = f;
            this.knownLength = knownLength;
        }

        @Override
        public long length() {
            return knownLength >= 0 ? Math.min(knownLength, f.length) : f.length;
        }

        @Override
        public int read(long position, ByteBuffer dst) throws IOException {
            if (closed) {
                throw new IOException("the reader is closed");
            }
            if (!dst.hasRemaining()) {
                return 0;
            }
            if (position >= f.length) {
                return -1;
            }
            int n = 0;
            long at = position;
            while (dst.hasRemaining() && at < f.length) {
                int c = (int) (at / f.chunkSize);
                int off = (int) (at % f.chunkSize);
                int len = (int) Math.min(Math.min(f.chunkSize - off, f.length - at), dst.remaining());
                ByteBuffer src = f.chunks[c].duplicate();
                src.limit(off + len).position(off);
                dst.put(src);
                at += len;
                n += len;
            }
            return n;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                f.release(pool);
            }
        }
    }

    /** A file being written: chunks taken as it grows, published under its name when closed. */
    private final class ChunkOut extends OutputStream {
        private final String key;
        private final boolean exclusive;
        private final List<ByteBuffer> chunks = new ArrayList<>();
        private ByteBuffer current;
        private long length;
        private boolean closed;
        boolean won;

        ChunkOut(String key, boolean exclusive) {
            this.key = key;
            this.exclusive = exclusive;
        }

        private ByteBuffer room() throws IOException {
            if (current == null || !current.hasRemaining()) {
                try {
                    current = chunk(key);
                } catch (IOException e) {
                    abort();
                    throw e;
                }
                chunks.add(current);
            }
            return current;
        }

        @Override
        public void write(int b) throws IOException {
            check();
            room().put((byte) b);
            length++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check();
            int at = off;
            int left = len;
            while (left > 0) {
                ByteBuffer c = room();
                int n = Math.min(left, c.remaining());
                c.put(b, at, n);
                at += n;
                left -= n;
                length += n;
            }
        }

        private void check() throws IOException {
            if (closed) {
                throw new IOException("the file is closed: " + full(key));
            }
        }

        private void abort() {
            closed = true;
            chunks.forEach(pool::give);
            chunks.clear();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            MemFile f = new MemFile(chunks.toArray(new ByteBuffer[0]), length, pool.chunkBytes());
            if (exclusive) {
                won = files.putIfAbsent(key, f) == null;
                if (!won) {
                    f.release(pool);
                    return;
                }
            } else {
                MemFile old = files.put(key, f);
                if (old != null) {
                    old.release(pool);
                }
            }
            bytesWritten.addAndGet(length);
        }
    }
}
