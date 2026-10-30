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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * A business date's id map in a file, read through a memory map (the Delta and Iceberg connectors' {@code cache/<connector>/idmaps}): the
 * ids sorted by their UTF-8 bytes, an offset per id and the index of the data file holding each. A lookup is a binary search over the
 * map; the heap holds nothing per id.
 *
 * <p><b>Released, not left to the collector (2.9.7, RESOURCES_AND_SCALE.md section 7.3).</b> Before 2.9.7 a mapping was never unmapped:
 * a deleted file kept holding its disk on Linux, the delete failed on Windows, and the address space grew with every table version. Now
 * every read enters a guard; {@link #close} unmaps once no reader is inside (a later read gets {@link Closed}: "ask again"), and
 * {@link #release(Path, boolean)} unmaps a file's mapping before deleting it. One mapping per file at a time ({@link #open} shares it).
 *
 * <p>Layout (little endian): {@code MAGIC, n, files[n], offsets[n + 1], bytes}.
 */
public final class MappedIdFile {

    /** A read after the mapping was released: the caller asks again (the file is mapped again, or read again from the lake). */
    public static final class Closed extends IllegalStateException {
        Closed(Path file) {
            super("the id map " + file.getFileName() + " was released while it was read; ask again");
        }
    }

    private static final int MAGIC = 0x44494d31;                       // "DIM1"
    private static final Map<Path, MappedIdFile> OPEN = new ConcurrentHashMap<>();

    private final Path file;
    private final FileMaps.Mapping mapping;
    private volatile ByteBuffer map;
    private final int n;
    private final int filesAt;
    private final int offsetsAt;
    private final int bytesAt;
    private final long fileBytes;
    private final AtomicInteger readers = new AtomicInteger();
    private final AtomicBoolean unmapped = new AtomicBoolean();
    private volatile boolean closing;
    private final java.util.concurrent.atomic.AtomicReference<Runnable> afterUnmap = new java.util.concurrent.atomic.AtomicReference<>();

    private MappedIdFile(Path file, FileMaps.Mapping mapping, long fileBytes) throws IOException {
        this.file = file;
        this.mapping = mapping;
        this.map = mapping.buffer();
        this.fileBytes = fileBytes;
        ByteBuffer m = map;
        if (m.capacity() < 8 || m.getInt(0) != MAGIC) {
            throw new IOException("not an id map");
        }
        this.n = m.getInt(4);
        this.filesAt = 8;
        this.offsetsAt = filesAt + 4 * n;
        this.bytesAt = offsetsAt + 4 * (n + 1);
        if (n < 0 || (long) bytesAt + m.getInt(offsetsAt + 4 * n) > m.capacity()) {
            throw new IOException("a truncated id map");
        }
    }

    /** The map in {@code file} (the one already mapped, when it is), or null when there is none or it cannot be read (it is written again). */
    public static MappedIdFile open(Path file) {
        Path key = file.toAbsolutePath().normalize();
        MappedIdFile there = OPEN.get(key);
        if (!Files.isRegularFile(file)) {
            if (there != null) {
                there.close();
            }
            return null;
        }
        if (there != null && !there.closing) {
            try {
                if (Files.size(file) == there.fileBytes) {
                    return there;
                }
            } catch (IOException e) {
                // read again below
            }
            there.close();                                             // the file was replaced: its old mapping goes
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            FileMaps.Mapping m = FileMaps.map(ch, 0, size);
            MappedIdFile f;
            try {
                f = new MappedIdFile(key, m, size);
            } catch (IOException | RuntimeException e) {
                m.release();
                return null;
            }
            touch(file);
            MappedIdFile raced = OPEN.putIfAbsent(key, f);
            if (raced != null && !raced.closing) {
                f.close();
                return raced;
            }
            OPEN.put(key, f);
            return f;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes the ids ({@code n}, the {@code i}th by {@code id}, its data file by {@code fileOf}) to {@code file}, atomically, and maps it. */
    public static MappedIdFile write(int n, IntFunction<String> id, ToIntFunction<String> fileOf, Path file) throws IOException {
        byte[][] ids = new byte[n][];
        Integer[] order = new Integer[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            ids[i] = id.apply(i).getBytes(StandardCharsets.UTF_8);
            order[i] = i;
            total += ids[i].length;
        }
        Arrays.sort(order, (a, b) -> Arrays.compareUnsigned(ids[a], ids[b]));
        long size = 8L + 4L * n + 4L * (n + 1) + total;
        if (size > Integer.MAX_VALUE) {
            throw new IOException("an id map over 2 GB");
        }
        ByteBuffer out = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MAGIC).putInt(n);
        for (int i = 0; i < n; i++) {
            out.putInt(fileOf.applyAsInt(id.apply(order[i])));
        }
        int off = 0;
        for (int i = 0; i < n; i++) {
            out.putInt(off);
            off += ids[order[i]].length;
        }
        out.putInt(off);
        for (int i = 0; i < n; i++) {
            out.put(ids[order[i]]);
        }
        Files.createDirectories(file.getParent());
        release(file, false);                                          // a stale mapping of an older file of that name goes first
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, out.array());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        MappedIdFile mapped = open(file);
        if (mapped == null) {
            throw new IOException("the id map just written cannot be read: " + file);
        }
        return mapped;
    }

    // ------------------------------------------------------------------------------------------------------------------ reads

    private ByteBuffer enter() {
        if (closing) {
            throw new Closed(file);
        }
        readers.incrementAndGet();
        ByteBuffer m = map;
        if (closing || m == null) {                                    // closed between the check and the count: leave again
            exit();
            throw new Closed(file);
        }
        return m;
    }

    private void exit() {
        if (readers.decrementAndGet() == 0 && closing) {
            unmap();
        }
    }

    private static byte[] bytesOf(ByteBuffer m, int offsetsAt, int bytesAt, int i) {
        int from = m.getInt(offsetsAt + 4 * i);
        int to = m.getInt(offsetsAt + 4 * (i + 1));
        byte[] b = new byte[to - from];
        m.get(bytesAt + from, b);
        return b;
    }

    public int size() {
        return n;
    }

    /** The {@code i}th id in sorted order. */
    public String id(int i) {
        ByteBuffer m = enter();
        try {
            return new String(bytesOf(m, offsetsAt, bytesAt, i), StandardCharsets.UTF_8);
        } finally {
            exit();
        }
    }

    /** The index of the data file holding {@code id}, or -1. */
    public int file(String id) {
        byte[] key = id.getBytes(StandardCharsets.UTF_8);
        ByteBuffer m = enter();
        try {
            int lo = 0;
            int hi = n - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int c = Arrays.compareUnsigned(bytesOf(m, offsetsAt, bytesAt, mid), key);
                if (c < 0) {
                    lo = mid + 1;
                } else if (c > 0) {
                    hi = mid - 1;
                } else {
                    return m.getInt(filesAt + 4 * mid);
                }
            }
            return -1;
        } finally {
            exit();
        }
    }

    /** The file's size: what the mapping holds of the address space and the page cache (never the heap). */
    public long fileBytes() {
        return fileBytes;
    }

    public Path path() {
        return file;
    }

    /** True once released (or being released): a cache holding it drops it and maps the file again. */
    public boolean closed() {
        return closing;
    }

    // ------------------------------------------------------------------------------------------------------------------ release

    /** Unmaps once no reader is inside (now, or when the last one leaves); later reads get {@link Closed}. */
    public void close() {
        closing = true;
        OPEN.remove(file, this);
        if (readers.get() == 0) {
            unmap();
        }
    }

    /** As {@link #close}, then runs {@code then} once unmapped (a delete: a file still mapped cannot be deleted on Windows). */
    public void closeThen(Runnable then) {
        afterUnmap.set(then);
        close();
        if (unmapped.get()) {
            runAfterUnmap();
        }
    }

    private void unmap() {
        if (!unmapped.compareAndSet(false, true)) {
            return;
        }
        map = null;
        mapping.release();
        runAfterUnmap();
    }

    private void runAfterUnmap() {
        Runnable r = afterUnmap.getAndSet(null);
        if (r != null) {
            r.run();
        }
    }

    /** Unmaps the file's mapping when it has one (once its readers are done), and with {@code delete} deletes the file after that. */
    public static void release(Path file, boolean delete) {
        Path key = file.toAbsolutePath().normalize();
        MappedIdFile m = OPEN.get(key);
        Runnable del = () -> {
            if (delete) {
                try {
                    Files.deleteIfExists(key);
                } catch (IOException e) {
                    // removed by the next sweep
                }
            }
        };
        if (m == null) {
            del.run();
        } else {
            m.closeThen(del);
        }
    }

    /** The mappings open now (Admin -> Caches, the tests): count and bytes. */
    public static long[] mapped() {
        long bytes = 0;
        int count = 0;
        for (MappedIdFile m : OPEN.values()) {
            if (!m.closing) {
                count++;
                bytes += m.fileBytes;
            }
        }
        return new long[] {count, bytes};
    }

    /** The mappings open now of files under {@code dir} (a test's own folder): count and bytes. */
    public static long[] mapped(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        long bytes = 0;
        int count = 0;
        for (MappedIdFile m : OPEN.values()) {
            if (!m.closing && m.file.startsWith(d)) {
                count++;
                bytes += m.fileBytes;
            }
        }
        return new long[] {count, bytes};
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));     // the least recently used go first
        } catch (IOException | RuntimeException e) {
            // read-only folder: the order is the write order
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ the folder

    /**
     * Keeps the folder under {@code maxBytes}: leftover {@code .tmp} files go, then the least recently used maps (by the time each was last
     * mapped) beyond the cap, never {@code keep}; a map still mapped is unmapped first (its readers finish) and then deleted. Returns the
     * files deleted. A cap of 0 or less keeps everything but the leftovers.
     */
    public static int trim(Path dir, long maxBytes, Path keep) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        List<Path> maps = new ArrayList<>();
        int gone = 0;
        try (var s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    try {
                        Files.deleteIfExists(p);
                        gone++;
                    } catch (IOException e) {
                        // being written
                    }
                } else if (name.endsWith(".idmap")) {
                    maps.add(p);
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return gone;
        }
        if (maxBytes <= 0) {
            return gone;
        }
        long total = 0;
        Map<Path, long[]> info = new java.util.HashMap<>();
        for (Path p : maps) {
            try {
                long size = Files.size(p);
                info.put(p, new long[] {size, Files.getLastModifiedTime(p).toMillis()});
                total += size;
            } catch (IOException e) {
                // gone meanwhile
            }
        }
        if (total <= maxBytes) {
            return gone;
        }
        List<Path> oldest = new ArrayList<>(info.keySet());
        oldest.sort(Comparator.comparingLong(p -> info.get(p)[1]));
        Path k = keep == null ? null : keep.toAbsolutePath().normalize();
        for (Path p : oldest) {
            if (total <= maxBytes) {
                break;
            }
            if (p.toAbsolutePath().normalize().equals(k)) {
                continue;
            }
            release(p, true);
            total -= info.get(p)[0];
            gone++;
        }
        return gone;
    }

    /** The bytes of the {@code .idmap} files under {@code dir} (Health -> Disk). */
    public static long folderBytes(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (var s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".idmap")).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }
}
