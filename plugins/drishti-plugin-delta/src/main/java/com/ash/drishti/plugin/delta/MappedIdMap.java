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
package com.ash.drishti.plugin.delta;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * A date's id map in a file, read through a memory map: the ids sorted by their UTF-8 bytes, an offset per id and the file index of
 * each. A lookup is a binary search over the map; the heap holds nothing per id, and the operating system keeps the pages that are
 * used. Written once per table version and date (the folder is named by the version), so it is never stale and outlives a restart.
 *
 * <p>Layout (little endian): {@code MAGIC, n, files[n], offsets[n + 1], bytes}.
 */
final class MappedIdMap implements DeltaTable.IdMap {

    private static final int MAGIC = 0x44494d31;                       // "DIM1"
    private final MappedByteBuffer map;
    private final int n;
    private final int filesAt;
    private final int offsetsAt;
    private final int bytesAt;

    private MappedIdMap(MappedByteBuffer map) throws IOException {
        this.map = map;
        map.order(ByteOrder.LITTLE_ENDIAN);
        if (map.capacity() < 8 || map.getInt(0) != MAGIC) {
            throw new IOException("not an id map");
        }
        this.n = map.getInt(4);
        this.filesAt = 8;
        this.offsetsAt = filesAt + 4 * n;
        this.bytesAt = offsetsAt + 4 * (n + 1);
        if (n < 0 || (long) bytesAt + map.getInt(offsetsAt + 4 * n) > map.capacity()) {
            throw new IOException("a truncated id map");
        }
    }

    /** The map in {@code file}, or null when there is none (or it cannot be read: it is then written again). */
    static MappedIdMap open(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return new MappedIdMap(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes {@code m} to {@code file} (through a temporary file, so a reader never sees half of it) and maps it. */
    static MappedIdMap write(DeltaTable.IdMap m, Path file) throws IOException {
        int n = m.size();
        byte[][] ids = new byte[n][];
        Integer[] order = new Integer[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            ids[i] = m.id(i).getBytes(StandardCharsets.UTF_8);
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
            out.putInt(m.file(m.id(order[i])));
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
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, out.array());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        MappedIdMap mapped = open(file);
        if (mapped == null) {
            throw new IOException("the id map just written cannot be read: " + file);
        }
        return mapped;
    }

    private byte[] bytesOf(int i) {
        int from = map.getInt(offsetsAt + 4 * i);
        int to = map.getInt(offsetsAt + 4 * (i + 1));
        byte[] b = new byte[to - from];
        map.get(bytesAt + from, b);
        return b;
    }

    @Override
    public int size() {
        return n;
    }

    @Override
    public String id(int i) {
        return new String(bytesOf(i), StandardCharsets.UTF_8);
    }

    @Override
    public int file(String id) {
        byte[] key = id.getBytes(StandardCharsets.UTF_8);
        int lo = 0;
        int hi = n - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = Arrays.compareUnsigned(bytesOf(mid), key);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return map.getInt(filesAt + 4 * mid);
            }
        }
        return -1;
    }

    @Override
    public long bytes() {
        return 256;                                                    // the map is outside the heap
    }
}
