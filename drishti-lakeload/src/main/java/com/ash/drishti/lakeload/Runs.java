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
package com.ash.drishti.lakeload;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The external sort of one business date's rows: each parser thread buffers its rows ({@link Buffer}: records packed in 8 MB arenas,
 * no object per row), sorts them by key when its share of {@code sort-buffer-mb} is full and spills them to a run file (or keeps the
 * last ones in memory as a run), so the sort is parallel and memory is bounded however large the input. The write stage then merges
 * every run of a date within one key range ({@link #merge}): ranges are cut from the runs' key samples, so writer threads merge
 * disjoint ranges at once.
 */
final class Runs {

    /** A key sample is kept every this many bytes of rows (the range cuts and the file index). */
    static final long SAMPLE_BYTES = 1L << 20;
    private static final int ARENA = 8 << 20;

    private Runs() {}

    /** Compares two keys (unsigned bytes). */
    static int compare(byte[] a, int aOff, int aLen, byte[] b, int bOff, int bLen) {
        return Arrays.compareUnsigned(a, aOff, aOff + aLen, b, bOff, bOff + bLen);
    }

    /** A record's key compared with {@code bound} (a whole key). */
    static int compareKey(byte[] rec, int off, byte[] bound) {
        int kl = Bytes.getInt(rec, off);
        return compare(rec, off + 4, kl, bound, 0, bound.length);
    }

    static byte[] keyOf(byte[] rec, int off) {
        int kl = Bytes.getInt(rec, off);
        return Arrays.copyOfRange(rec, off + 4, off + 4 + kl);
    }

    /** Rows of one date held by one parser thread, unsorted. */
    static final class Buffer {
        final List<byte[]> arenas = new ArrayList<>();
        private final int arena;
        private byte[] cur;
        private int pos;
        long[] refs = new long[1024];
        int n;
        long bytes;

        Buffer() {
            this(ARENA);
        }

        /** A buffer packing its rows in arenas of {@code arenaBytes} (smaller under a small memory budget). */
        Buffer(int arenaBytes) {
            this.arena = Math.max(64 * 1024, Math.min(ARENA, arenaBytes));
        }

        /** Copies one record ({@code rec[0, len)}) in. */
        void add(byte[] rec, int len) {
            int need = 4 + len;
            if (cur == null || pos + need > cur.length) {
                cur = new byte[Math.max(arena, need)];
                arenas.add(cur);
                pos = 0;
            }
            Bytes.INT.set(cur, pos, len);
            System.arraycopy(rec, 0, cur, pos + 4, len);
            if (n == refs.length) {
                refs = Arrays.copyOf(refs, n * 2);
            }
            refs[n++] = (long) (arenas.size() - 1) << 32 | pos;
            pos += need;
            bytes += need + 8;
        }

        /** Sorts the records by key (a merge sort over their references; the 8-byte key prefixes decide most comparisons). */
        long[] sorted() {
            long[] pre = new long[n];
            for (int i = 0; i < n; i++) {
                byte[] a = arenas.get((int) (refs[i] >>> 32));
                int off = (int) refs[i] + 4;
                int kl = Bytes.getInt(a, off);
                long p = 0;
                for (int b = 0; b < 8; b++) {
                    p = p << 8 | (b < kl ? a[off + 4 + b] & 0xff : 0);
                }
                pre[i] = p;
            }
            int[] idx = new int[n];
            for (int i = 0; i < n; i++) {
                idx[i] = i;
            }
            int[] tmp = new int[n];
            mergeSort(idx, tmp, 0, n, pre);
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                out[i] = refs[idx[i]];
            }
            return out;
        }

        private int cmp(int x, int y, long[] pre) {
            int c = Long.compareUnsigned(pre[x], pre[y]);
            if (c != 0) {
                return c;
            }
            byte[] a = arenas.get((int) (refs[x] >>> 32));
            int ao = (int) refs[x] + 4;
            byte[] b = arenas.get((int) (refs[y] >>> 32));
            int bo = (int) refs[y] + 4;
            return compare(a, ao + 4, Bytes.getInt(a, ao), b, bo + 4, Bytes.getInt(b, bo));
        }

        private void mergeSort(int[] a, int[] tmp, int from, int to, long[] pre) {
            if (to - from <= 24) {
                for (int i = from + 1; i < to; i++) {
                    int v = a[i];
                    int j = i - 1;
                    while (j >= from && cmp(a[j], v, pre) > 0) {
                        a[j + 1] = a[j];
                        j--;
                    }
                    a[j + 1] = v;
                }
                return;
            }
            int mid = (from + to) >>> 1;
            mergeSort(a, tmp, from, mid, pre);
            mergeSort(a, tmp, mid, to, pre);
            if (cmp(a[mid - 1], a[mid], pre) <= 0) {
                return;
            }
            System.arraycopy(a, from, tmp, from, to - from);
            int i = from;
            int j = mid;
            for (int k = from; k < to; k++) {
                a[k] = j >= to || i < mid && cmp(tmp[i], tmp[j], pre) <= 0 ? tmp[i++] : tmp[j++];
            }
        }

        /** The sorted rows as a run in memory (the buffer is handed over). */
        Run toMemory() {
            long[] order = sorted();
            Run r = new MemoryRun(arenas, order, bytes);
            refs = null;
            return r;
        }

        /** The sorted rows written to {@code file} as a run (the buffer's memory is freed). */
        Run spill(Path file) throws IOException {
            long[] order = sorted();
            List<byte[]> keys = new ArrayList<>();
            List<Long> offsets = new ArrayList<>();
            long written = 0;
            long nextSample = 0;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)) {
                for (long ref : order) {
                    byte[] a = arenas.get((int) (ref >>> 32));
                    int off = (int) ref;
                    int len = Bytes.getInt(a, off);
                    if (written >= nextSample) {
                        keys.add(keyOf(a, off + 4));
                        offsets.add(written);
                        nextSample = written + SAMPLE_BYTES;
                    }
                    out.write(a, off, 4 + len);
                    written += 4 + len;
                }
            }
            long rows = n;
            arenas.clear();
            refs = null;
            return new FileRun(file, rows, written, keys, offsets);
        }
    }

    /** A sorted run of one date's rows: its size, its key samples (one every {@link #SAMPLE_BYTES}) and cursors over it. */
    abstract static class Run implements Closeable {
        final long rows;
        final long bytes;

        Run(long rows, long bytes) {
            this.rows = rows;
            this.bytes = bytes;
        }

        abstract List<byte[]> samples();

        /** A cursor at the first row whose key is at or after {@code from} (null: the first row), reading through {@code bufferBytes}. */
        abstract Cursor open(byte[] from, int bufferBytes) throws IOException;

        @Override
        public void close() throws IOException {}
    }

    /** Rows in key order. */
    interface Cursor extends Closeable {
        /** Moves to the next row; false at the end. */
        boolean next() throws IOException;

        byte[] buf();

        /** Where the current record starts in {@link #buf()} (after its length). */
        int off();

        int len();
    }

    static final class MemoryRun extends Run {
        private final List<byte[]> arenas;
        private final long[] order;

        MemoryRun(List<byte[]> arenas, long[] order, long bytes) {
            super(order.length, bytes);
            this.arenas = arenas;
            this.order = order;
        }

        @Override
        List<byte[]> samples() {
            List<byte[]> out = new ArrayList<>();
            long acc = 0;
            long next = 0;
            for (long ref : order) {
                byte[] a = arenas.get((int) (ref >>> 32));
                int off = (int) ref;
                if (acc >= next) {
                    out.add(keyOf(a, off + 4));
                    next = acc + SAMPLE_BYTES;
                }
                acc += 4 + Bytes.getInt(a, off);
            }
            return out;
        }

        @Override
        Cursor open(byte[] from, int bufferBytes) {
            int lo = 0;
            int hi = order.length;
            if (from != null) {
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    long ref = order[mid];
                    if (compareKey(arenas.get((int) (ref >>> 32)), (int) ref + 4, from) < 0) {
                        lo = mid + 1;
                    } else {
                        hi = mid;
                    }
                }
            }
            int start = lo;
            return new Cursor() {
                int i = start - 1;
                byte[] a;
                int off;
                int len;

                @Override
                public boolean next() {
                    if (++i >= order.length) {
                        return false;
                    }
                    long ref = order[i];
                    a = arenas.get((int) (ref >>> 32));
                    len = Bytes.getInt(a, (int) ref);
                    off = (int) ref + 4;
                    return true;
                }

                @Override
                public byte[] buf() {
                    return a;
                }

                @Override
                public int off() {
                    return off;
                }

                @Override
                public int len() {
                    return len;
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public void close() {
            arenas.clear();
        }
    }

    static final class FileRun extends Run {
        private final Path file;
        private final List<byte[]> keys;
        private final List<Long> offsets;

        FileRun(Path file, long rows, long bytes, List<byte[]> keys, List<Long> offsets) {
            super(rows, bytes);
            this.file = file;
            this.keys = keys;
            this.offsets = offsets;
        }

        @Override
        List<byte[]> samples() {
            return keys;
        }

        @Override
        Cursor open(byte[] from, int bufferBytes) throws IOException {
            long start = 0;
            if (from != null) {
                for (int i = 0; i < keys.size(); i++) {
                    byte[] k = keys.get(i);
                    if (compare(k, 0, k.length, from, 0, from.length) < 0) {
                        start = offsets.get(i);
                    } else {
                        break;
                    }
                }
            }
            FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
            ch.position(start);
            InputStream in = new java.io.BufferedInputStream(Channels.newInputStream(ch), Math.max(8192, bufferBytes));
            return new Cursor() {
                byte[] a = new byte[1 << 12];
                int len;
                final byte[] head = new byte[4];
                boolean skipping = from != null;

                @Override
                public boolean next() throws IOException {
                    while (true) {
                        if (!fill(head, 4)) {
                            return false;
                        }
                        len = Bytes.getInt(head, 0);
                        if (a.length < len) {
                            a = new byte[Math.max(len, a.length * 2)];
                        }
                        if (!fill(a, len)) {
                            throw new EOFException("a spill file ends inside a row: " + file);
                        }
                        if (skipping && compareKey(a, 0, from) < 0) {
                            continue;
                        }
                        skipping = false;
                        return true;
                    }
                }

                private boolean fill(byte[] b, int n) throws IOException {
                    int got = 0;
                    while (got < n) {
                        int r = in.read(b, got, n - got);
                        if (r < 0) {
                            if (got == 0) {
                                return false;
                            }
                            throw new EOFException("a spill file ends early: " + file);
                        }
                        got += r;
                    }
                    return true;
                }

                @Override
                public byte[] buf() {
                    return a;
                }

                @Override
                public int off() {
                    return 0;
                }

                @Override
                public int len() {
                    return len;
                }

                @Override
                public void close() throws IOException {
                    in.close();
                }
            };
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(file);
        }
    }

    /** Receives merged rows in key order. */
    interface Sink {
        void row(byte[] buf, int off, int len) throws IOException;
    }

    /** Every row of {@code runs} with a key in {@code [lo, hi)} (null: unbounded), in key order, to {@code sink}. */
    static void merge(List<Run> runs, byte[] lo, byte[] hi, Sink sink) throws IOException {
        merge(runs, lo, hi, sink, 1 << 20);
    }

    /** {@link #merge} reading each run through {@code bufferBytes} (the writers' share of the memory budget over the runs). */
    static void merge(List<Run> runs, byte[] lo, byte[] hi, Sink sink, int bufferBytes) throws IOException {
        PriorityQueue<Cursor> heap = new PriorityQueue<>((x, y) -> {
            int xl = Bytes.getInt(x.buf(), x.off());
            int yl = Bytes.getInt(y.buf(), y.off());
            return compare(x.buf(), x.off() + 4, xl, y.buf(), y.off() + 4, yl);
        });
        List<Cursor> open = new ArrayList<>();
        try {
            for (Run r : runs) {
                Cursor c = r.open(lo, bufferBytes);
                open.add(c);
                if (c.next() && (hi == null || compareKey(c.buf(), c.off(), hi) < 0)) {
                    heap.add(c);
                }
            }
            while (!heap.isEmpty()) {
                Cursor c = heap.poll();
                sink.row(c.buf(), c.off(), c.len());
                if (c.next() && (hi == null || compareKey(c.buf(), c.off(), hi) < 0)) {
                    heap.add(c);
                }
            }
        } finally {
            for (Cursor c : open) {
                c.close();
            }
        }
    }

    /**
     * Merges runs into fewer, larger run files until at most {@code maxRuns} are left (the passes of an external merge sort), so a date
     * that spilled many runs is merged with bounded memory: each pass reads {@code maxRuns} runs at once through {@code bufferBytes}
     * each. The merged runs are in {@code dir}; the inputs are closed (their files deleted).
     */
    static List<Run> reduce(List<Run> runs, int maxRuns, Path dir, String name, int bufferBytes) throws IOException {
        List<Run> cur = new ArrayList<>(runs);
        int pass = 0;
        while (cur.size() > maxRuns) {
            List<Run> next = new ArrayList<>();
            for (int i = 0; i < cur.size(); i += maxRuns) {
                List<Run> group = new ArrayList<>(cur.subList(i, Math.min(cur.size(), i + maxRuns)));
                if (group.size() == 1) {
                    next.add(group.get(0));
                    continue;
                }
                Path file = dir.resolve(name + "-pass" + pass + "-" + i + ".bin");
                List<byte[]> keys = new ArrayList<>();
                List<Long> offsets = new ArrayList<>();
                long[] written = {0, 0, 0};                            // bytes, rows, next sample
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
                    byte[] len = new byte[4];
                    merge(group, null, null, (buf, off, n) -> {
                        if (written[0] >= written[2]) {
                            keys.add(keyOf(buf, off));
                            offsets.add(written[0]);
                            written[2] = written[0] + SAMPLE_BYTES;
                        }
                        Bytes.INT.set(len, 0, n);
                        out.write(len);
                        out.write(buf, off, n);
                        written[0] += 4 + n;
                        written[1]++;
                    }, bufferBytes);
                }
                for (Run r : group) {
                    r.close();
                }
                next.add(new FileRun(file, written[1], written[0], keys, offsets));
            }
            cur = next;
            pass++;
        }
        return cur;
    }

    /**
     * Range bounds for a date: about {@code ranges} ranges of equal bytes, cut at the runs' key samples; {@code null} first and last
     * stand for unbounded. Ranges of rows with equal keys never split (a bound is a whole key).
     */
    static List<byte[]> bounds(List<Run> runs, int ranges) {
        List<byte[]> samples = new ArrayList<>();
        for (Run r : runs) {
            samples.addAll(r.samples());
        }
        samples.sort((x, y) -> compare(x, 0, x.length, y, 0, y.length));
        List<byte[]> out = new ArrayList<>();
        out.add(null);
        if (ranges > 1 && samples.size() > 1) {
            double step = samples.size() / (double) ranges;
            byte[] prev = null;
            for (int i = 1; i < ranges; i++) {
                byte[] b = samples.get((int) Math.min(samples.size() - 1, Math.round(i * step)));
                if (prev == null || compare(prev, 0, prev.length, b, 0, b.length) < 0) {
                    out.add(b);
                    prev = b;
                }
            }
        }
        out.add(null);
        return out;
    }
}
