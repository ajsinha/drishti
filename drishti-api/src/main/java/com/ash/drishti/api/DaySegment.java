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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One closed business day of one kind's ids on disk: entries sorted by lower-cased id, in blocks of {@value #BLOCK}
 * compressed with Deflate, read with positional reads of an open file channel (no memory map, so the operating system can
 * free a deleted file's blocks the moment the channel closes). A prefix lookup binary-searches the block index (the first
 * key of each block, held in memory) and decodes the one or two blocks that hold the prefix, with a small bounded cache of
 * decoded blocks, so it stays sub-millisecond without the day's ids on the heap.
 *
 * <p>Release is deterministic: every lookup holds the segment (a reader count) while it reads; {@link #retire} marks it
 * retired, removes the file if asked, and closes the channel when the last reader lets go. A lookup that meets a retired
 * segment answers {@code false} so the caller looks again at the index's current segment. A file is written once under a temporary name in the same folder and moved into
 * place atomically, so a reader never sees a half-written segment; it ends in a CRC, and a file that fails the check is
 * deleted and treated as absent. It records the {@code watermark} of the source it was built from (a load time, a table
 * version), so a restart reuses it only while the source is unchanged.
 */
public final class DaySegment {

    static final int BLOCK = 128;
    private static final int MAGIC = 0x44535347;
    private static final int VERSION = 1;

    private final Path file;
    private final String watermark;
    private final int count;
    private final String[] firstKeys;
    private final long[] offsets;
    private final int[] compressed;
    private final int[] raw;
    private final FileChannel channel;
    private final long bytes;
    private final int cacheBlocks;
    private final Map<Integer, List<EntityHit>> cache;
    private final AtomicInteger readers = new AtomicInteger();
    private volatile boolean retired;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DaySegment(Path file, String watermark, int count, String[] firstKeys, long[] offsets, int[] compressed, int[] raw, FileChannel channel, long bytes, int cacheBlocks) {
        this.file = file;
        this.watermark = watermark;
        this.count = count;
        this.firstKeys = firstKeys;
        this.offsets = offsets;
        this.compressed = compressed;
        this.raw = raw;
        this.channel = channel;
        this.bytes = bytes;
        this.cacheBlocks = Math.max(0, cacheBlocks);
        this.cache = new LinkedHashMap<>(16, 0.75f, true); // access order: the eldest is the least recently used
    }

    /** The decoded blocks a segment keeps unless told otherwise ({@code index.block-cache}). */
    public static final int DEFAULT_CACHE_BLOCKS = 16;

    private boolean acquire() {
        readers.incrementAndGet();
        if (retired) {
            release();
            return false;
        }
        return true;
    }

    private void release() {
        if (readers.decrementAndGet() == 0 && retired) {
            close();
        }
    }

    private void close() {
        if (readers.get() == 0 && closed.compareAndSet(false, true)) {
            try {
                channel.close();
            } catch (IOException e) {
                // nothing more to release
            }
            synchronized (cache) {
                cache.clear();
            }
        }
    }

    /** True once {@link #retire} was called (the segment answers no more lookups). */
    public boolean retired() {
        return retired;
    }

    /** True once the file channel is closed (every reader let go after retirement). */
    public boolean released() {
        return closed.get();
    }

    /**
     * Takes the segment out of service: later lookups answer false, the file is removed when {@code deleteFile} (not when a
     * newer file now holds the same name), and the channel closes as soon as the last running lookup finishes.
     */
    public void retire(boolean deleteFile) {
        retired = true;
        if (deleteFile) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // removed with the folder's next clean
            }
        }
        close();
    }

    public Path file() {
        return file;
    }

    /** The source version this segment was built from, or null when the source gave none. */
    public String watermark() {
        return watermark;
    }

    public int count() {
        return count;
    }

    public long bytes() {
        return bytes;
    }

    /** Retires the segment and removes its file. */
    public void delete() {
        retire(true);
    }

    // ---- write ---------------------------------------------------------------------------------------------------

    /**
     * Writes {@code hits} (any order) to {@code target} and opens it. The file appears whole or not at all.
     *
     * @throws UncheckedIOException when the disk refuses
     */
    public static DaySegment write(Path target, String watermark, List<EntityHit> hits) {
        return write(target, watermark, hits, DEFAULT_CACHE_BLOCKS);
    }

    /** As {@link #write(Path, String, List)} with a decoded-block cache of {@code cacheBlocks} blocks. */
    public static DaySegment write(Path target, String watermark, List<EntityHit> hits, int cacheBlocks) {
        List<EntityHit> sorted = new ArrayList<>(hits);
        sorted.sort((a, b) -> {
            int c = a.ref().id().toLowerCase(Locale.ROOT).compareTo(b.ref().id().toLowerCase(Locale.ROOT));
            return c != 0 ? c : a.ref().id().compareTo(b.ref().id());
        });
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
        try {
            Files.createDirectories(target.getParent());
            int blocks = (sorted.size() + BLOCK - 1) / BLOCK;
            List<byte[]> data = new ArrayList<>(blocks);
            String[] keys = new String[blocks];
            int[] rawLen = new int[blocks];
            for (int b = 0; b < blocks; b++) {
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(raw)) {
                    List<EntityHit> part = sorted.subList(b * BLOCK, Math.min(sorted.size(), (b + 1) * BLOCK));
                    keys[b] = part.get(0).ref().id().toLowerCase(Locale.ROOT);
                    out.writeInt(part.size());
                    for (EntityHit h : part) {
                        out.writeUTF(h.ref().kind());
                        out.writeUTF(h.ref().id());
                        out.writeUTF(h.title() == null ? "" : h.title());
                        out.writeUTF(h.subtitle() == null ? "" : h.subtitle());
                    }
                }
                byte[] r = raw.toByteArray();
                rawLen[b] = r.length;
                data.add(deflate(r));
            }
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(head)) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(sorted.size());
                out.writeInt(blocks);
                byte[] wm = (watermark == null ? "" : watermark).getBytes(StandardCharsets.UTF_8);
                out.writeBoolean(watermark != null);
                out.writeInt(wm.length);
                out.write(wm);
                for (int b = 0; b < blocks; b++) {
                    byte[] k = keys[b].getBytes(StandardCharsets.UTF_8);
                    out.writeInt(data.get(b).length);
                    out.writeInt(rawLen[b]);
                    out.writeInt(k.length);
                    out.write(k);
                }
            }
            byte[] header = head.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(header);
            data.forEach(crc::update);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(header));
                for (byte[] d : data) {
                    ch.write(ByteBuffer.wrap(d));
                }
                ByteBuffer tail = ByteBuffer.allocate(8).putLong(crc.getValue());
                tail.flip();
                ch.write(tail);
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // cleaned with the folder
            }
            throw new UncheckedIOException(e);
        }
        return open(target, cacheBlocks).orElseThrow(() -> new UncheckedIOException(new IOException("segment unreadable after write: " + target)));
    }

    private static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 16);
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }

    // ---- open ----------------------------------------------------------------------------------------------------

    /** Opens {@code file}; a file that is truncated, from another version or fails its CRC is deleted and yields empty. */
    public static Optional<DaySegment> open(Path file) {
        return open(file, DEFAULT_CACHE_BLOCKS);
    }

    /** As {@link #open(Path)} with a decoded-block cache of {@code cacheBlocks} blocks. */
    public static Optional<DaySegment> open(Path file, int cacheBlocks) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        FileChannel ch = null;
        try {
            ch = FileChannel.open(file, StandardOpenOption.READ);
            long size = ch.size();
            if (size < 32) {
                throw new IOException("size");
            }
            CRC32 crc = new CRC32();
            ByteBuffer buf = ByteBuffer.allocate(65536);
            long pos = 0;
            while (pos < size - 8) {
                buf.clear().limit((int) Math.min(buf.capacity(), size - 8 - pos));
                int n = ch.read(buf, pos);
                if (n <= 0) {
                    throw new IOException("short");
                }
                crc.update(buf.array(), 0, n);
                pos += n;
            }
            ByteBuffer tail = ByteBuffer.allocate(8);
            if (ch.read(tail, size - 8) != 8 || crc.getValue() != tail.flip().getLong()) {
                throw new IOException("crc");
            }
            ch.position(0);
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Channels.newInputStream(ch), 65536));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("magic");
            }
            int count = in.readInt();
            int blocks = in.readInt();
            boolean hasWm = in.readBoolean();
            byte[] wm = new byte[in.readInt()];
            in.readFully(wm);
            long at = 21 + wm.length;
            String[] keys = new String[blocks];
            long[] offsets = new long[blocks];
            int[] comp = new int[blocks];
            int[] raw = new int[blocks];
            for (int b = 0; b < blocks; b++) {
                comp[b] = in.readInt();
                raw[b] = in.readInt();
                byte[] k = new byte[in.readInt()];
                in.readFully(k);
                keys[b] = new String(k, StandardCharsets.UTF_8);
                at += 12 + k.length;
            }
            for (int b = 0; b < blocks; b++) {
                offsets[b] = at;
                at += comp[b];
            }
            if (at + 8 != size) {
                throw new IOException("length");
            }
            return Optional.of(new DaySegment(file, hasWm ? new String(wm, StandardCharsets.UTF_8) : null, count, keys, offsets, comp, raw, ch, size, cacheBlocks));
        } catch (IOException | RuntimeException e) {
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException ignored) {
                    // already failing
                }
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // left for the next clean
            }
            return Optional.empty();
        }
    }

    // ---- read ----------------------------------------------------------------------------------------------------

    private List<EntityHit> block(int b) {
        synchronized (cache) {
            List<EntityHit> hit = cache.get(b);
            if (hit != null) {
                return hit;
            }
        }
        List<EntityHit> decoded = decode(b);
        if (cacheBlocks > 0) {
            synchronized (cache) {
                cache.put(b, decoded);
                var it = cache.keySet().iterator(); // bounded: drop the least recently used blocks (no Serializable subclass)
                while (cache.size() > cacheBlocks && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
        return decoded;
    }

    private List<EntityHit> decode(int b) {
        ByteBuffer slice = ByteBuffer.allocate(compressed[b]);
        try {
            long at = offsets[b];
            while (slice.hasRemaining()) {
                int n = channel.read(slice, at + slice.position());
                if (n < 0) {
                    throw new IOException("end of file");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(new IOException("segment block unreadable: " + file, e));
        }
        byte[] comp = slice.array();
        Inflater inf = new Inflater();
        try {
            inf.setInput(comp);
            byte[] out = new byte[raw[b]];
            int n = 0;
            while (n < out.length && !inf.finished()) {
                int got = inf.inflate(out, n, out.length - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                n += got;
            }
            try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(out))) {
                int cnt = in.readInt();
                List<EntityHit> hits = new ArrayList<>(cnt);
                for (int i = 0; i < cnt; i++) {
                    String kind = in.readUTF();
                    String id = in.readUTF();
                    String title = in.readUTF();
                    String sub = in.readUTF();
                    hits.add(new EntityHit(EntityRef.of(kind, id), title, sub));
                }
                return hits;
            }
        } catch (DataFormatException | IOException e) {
            throw new UncheckedIOException(new IOException("segment block unreadable: " + file, e));
        } finally {
            inf.end();
        }
    }

    /** All ids, in order (restart checks, tests, rebuilds). */
    public List<EntityHit> all() {
        List<EntityHit> out = new ArrayList<>(count);
        if (!acquire()) {
            return out;
        }
        try {
            for (int b = 0; b < firstKeys.length; b++) {
                out.addAll(block(b));
            }
        } finally {
            release();
        }
        return out;
    }

    /** Appends up to {@code room} hits for {@code q} (already lower-cased), ranked as {@link HitIndex}: prefix, id contains, text contains. */
    public boolean search(String q, int room, List<EntityHit> out) {
        if (room <= 0 || firstKeys.length == 0) {
            return true;
        }
        if (!acquire()) {
            return false;
        }
        try {
            find(q, room, out);
            return true;
        } finally {
            release();
        }
    }

    private void find(String q, int room, List<EntityHit> out) {
        int start = out.size();
        int b = Math.max(0, lastBefore(q));
        outer:
        for (; b < firstKeys.length; b++) {
            for (EntityHit h : block(b)) {
                String lo = h.ref().id().toLowerCase(Locale.ROOT);
                if (lo.compareTo(q) < 0) {
                    continue;
                }
                if (!lo.startsWith(q)) {
                    break outer;
                }
                out.add(h);
                if (out.size() - start >= room) {
                    return;
                }
            }
        }
        if (q.isEmpty()) {
            return;
        }
        for (int rank = 1; rank <= 2; rank++) {
            for (int blk = 0; blk < firstKeys.length; blk++) {
                for (EntityHit h : block(blk)) {
                    String lo = h.ref().id().toLowerCase(Locale.ROOT);
                    boolean hit = rank == 1 ? !lo.startsWith(q) && lo.contains(q)
                            : !lo.contains(q) && ((h.title() + " " + h.subtitle()).toLowerCase(Locale.ROOT)).contains(q);
                    if (hit) {
                        out.add(h);
                        if (out.size() - start >= room) {
                            return;
                        }
                    }
                }
            }
        }
    }

    /** The last block whose first key is below {@code q} (the prefix can start inside it), or -1. */
    private int lastBefore(String q) {
        int lo = 0;
        int hi = firstKeys.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (firstKeys[mid].compareTo(q) < 0) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }
}
