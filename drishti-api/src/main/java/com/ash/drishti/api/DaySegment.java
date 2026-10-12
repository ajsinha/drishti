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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One closed business day of one kind's ids on disk: entries sorted by lower-cased id, in blocks of {@value #BLOCK}
 * compressed with Deflate, read through a memory-mapped file. A prefix lookup binary-searches the block index (the first
 * key of each block, held in memory) and decodes the one or two blocks that hold the prefix, so it stays sub-millisecond
 * without the day's ids on the heap. A file is written once under a temporary name in the same folder and moved into
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
    private final ByteBuffer map;
    private final long bytes;

    private DaySegment(Path file, String watermark, int count, String[] firstKeys, long[] offsets, int[] compressed, int[] raw, ByteBuffer map, long bytes) {
        this.file = file;
        this.watermark = watermark;
        this.count = count;
        this.firstKeys = firstKeys;
        this.offsets = offsets;
        this.compressed = compressed;
        this.raw = raw;
        this.map = map;
        this.bytes = bytes;
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

    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a mapped file that cannot be removed now is removed with its folder's next clean
        }
    }

    // ---- write ---------------------------------------------------------------------------------------------------

    /**
     * Writes {@code hits} (any order) to {@code target} and opens it. The file appears whole or not at all.
     *
     * @throws UncheckedIOException when the disk refuses
     */
    public static DaySegment write(Path target, String watermark, List<EntityHit> hits) {
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
        return open(target).orElseThrow(() -> new UncheckedIOException(new IOException("segment unreadable after write: " + target)));
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

    /** Maps {@code file}; a file that is truncated, from another version or fails its CRC is deleted and yields empty. */
    public static Optional<DaySegment> open(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size < 32 || size > Integer.MAX_VALUE) {
                throw new IOException("size");
            }
            ByteBuffer map = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
            CRC32 crc = new CRC32();
            ByteBuffer body = map.duplicate();
            body.limit((int) size - 8);
            crc.update(body);
            if (crc.getValue() != map.getLong((int) size - 8)) {
                throw new IOException("crc");
            }
            ByteBuffer in = map.duplicate();
            if (in.getInt() != MAGIC || in.getInt() != VERSION) {
                throw new IOException("magic");
            }
            int count = in.getInt();
            int blocks = in.getInt();
            boolean hasWm = in.get() != 0;
            byte[] wm = new byte[in.getInt()];
            in.get(wm);
            String[] keys = new String[blocks];
            long[] offsets = new long[blocks];
            int[] comp = new int[blocks];
            int[] raw = new int[blocks];
            long[] lens = new long[blocks];
            for (int b = 0; b < blocks; b++) {
                comp[b] = in.getInt();
                raw[b] = in.getInt();
                byte[] k = new byte[in.getInt()];
                in.get(k);
                keys[b] = new String(k, StandardCharsets.UTF_8);
                lens[b] = comp[b];
            }
            long at = in.position();
            for (int b = 0; b < blocks; b++) {
                offsets[b] = at;
                at += lens[b];
            }
            return Optional.of(new DaySegment(file, hasWm ? new String(wm, StandardCharsets.UTF_8) : null, count, keys, offsets, comp, raw, map, size));
        } catch (IOException | RuntimeException e) {
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
        ByteBuffer slice = map.duplicate();
        slice.position((int) offsets[b]).limit((int) offsets[b] + compressed[b]);
        byte[] comp = new byte[compressed[b]];
        slice.get(comp);
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
        for (int b = 0; b < firstKeys.length; b++) {
            out.addAll(block(b));
        }
        return out;
    }

    /** Appends up to {@code room} hits for {@code q} (already lower-cased), ranked as {@link HitIndex}: prefix, id contains, text contains. */
    public void search(String q, int room, List<EntityHit> out) {
        if (room <= 0 || firstKeys.length == 0) {
            return;
        }
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
