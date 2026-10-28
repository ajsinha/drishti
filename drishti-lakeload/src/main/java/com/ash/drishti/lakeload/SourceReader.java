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

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.lakeio.RangeReader;
import com.ash.drishti.lakeload.Inputs.Compression;
import com.ash.drishti.lakeload.Inputs.Split;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * The first stage: one split read into chunks of whole lines that the parser threads take from a shared queue. A plain file's range
 * is read with positional reads (ranged GETs on S3) and owns the lines that start inside it; a {@code .gz} or {@code .zst} file is
 * decompressed as a stream ({@link GzipMembers}, zstd-jni) by this one reader, so even one large compressed file is parsed by every
 * parser thread. Nothing is staged on disk and no input is concatenated first. The queue is bounded by bytes ({@code queue-mb}): a reader
 * waits while the parsers are behind (back-pressure), so memory stays flat.
 */
final class SourceReader {

    /** Lines of one split, in order ({@code seq}); {@code data[0, len)} holds whole lines (the last line of a file may lack its newline). */
    record Chunk(Split split, int seq, byte[] data, int len) {
        static final Chunk END = new Chunk(null, -1, new byte[0], 0);
    }

    static final int CHUNK = 4 << 20;

    private final FileAccess io;
    private final BlockingQueue<Chunk> queue;
    private final Semaphore budgetKb;
    private final Meter read;
    private final Meter decompress;
    private final int chunkBytes;
    private final int maxKb;
    private final int blockBytes;

    SourceReader(FileAccess io, BlockingQueue<Chunk> queue, Semaphore budgetKb, int maxKb, Meter read, Meter decompress, int chunkBytes, int blockBytes) {
        this.blockBytes = blockBytes;
        this.io = io;
        this.maxKb = maxKb;
        this.queue = queue;
        this.budgetKb = budgetKb;
        this.read = read;
        this.decompress = decompress;
        this.chunkBytes = chunkBytes;
    }

    /** Reads one split to its end; returns its chunk count. */
    int read(Split s) throws IOException, InterruptedException {
        String path = io.resolve(Inputs.uri(s.file().path()));
        try (RangeReader r = io.storage(path).open(path, s.file().size())) {
            boolean plain = s.file().compression() == Compression.NONE;
            long from = plain && s.start() > 0 ? s.start() - 1 : 0;
            RangeStream raw = new RangeStream(r, from, r.length(), read);
            InputStream in = switch (s.file().compression()) {
                case NONE -> raw;
                case GZIP -> new GzipMembers(raw, blockBytes);
                case ZSTD -> new com.github.luben.zstd.ZstdInputStream(raw);
            };
            try (in) {
                return chunks(s, in, raw, plain ? s.end() : Long.MAX_VALUE, plain && s.start() > 0, from);
            } finally {
                if (!plain) {
                    decompress.bytesIn.add(raw.bytes);
                }
            }
        }
    }

    /**
     * Cuts the stream into chunks of whole lines. {@code limit}: a line starting at or after this offset belongs to the next range;
     * {@code skipFirst}: the stream starts one byte before the range, so the partial line there (the previous range's) is dropped.
     */
    private int chunks(Split s, InputStream in, RangeStream raw, long limit, boolean skipFirst, long offset) throws IOException, InterruptedException {
        boolean compressed = s.file().compression() != Compression.NONE;
        byte[] buf = new byte[chunkBytes];
        int fill = 0;
        long bufAt = offset;                                    // the stream offset of buf[0]
        boolean eof = false;
        boolean bom = s.start() == 0;
        int seq = 0;
        while (true) {
            while (!eof && fill < buf.length) {
                long t0 = System.nanoTime();
                long rawBefore = raw.nanos;
                int n = in.read(buf, fill, buf.length - fill);
                if (compressed) {
                    decompress.busy(t0 + (raw.nanos - rawBefore));
                    if (n > 0) {
                        decompress.bytesOut.add(n);
                    }
                }
                if (n < 0) {
                    eof = true;
                } else {
                    fill += n;
                }
            }
            if (bom) {
                bom = false;
                if (fill >= 3 && (buf[0] & 0xff) == 0xef && (buf[1] & 0xff) == 0xbb && (buf[2] & 0xff) == 0xbf) {
                    System.arraycopy(buf, 3, buf, 0, fill - 3);
                    fill -= 3;
                    bufAt += 3;
                }
            }
            if (skipFirst) {
                int nl = indexOf(buf, 0, fill);
                if (nl < 0) {
                    if (eof) {
                        return seq;                                     // the range holds no line start
                    }
                    bufAt += fill;                                      // the partial line goes on: drop what was read of it
                    fill = 0;
                    continue;
                }
                System.arraycopy(buf, nl + 1, buf, 0, fill - nl - 1);
                fill -= nl + 1;
                bufAt += nl + 1;
                skipFirst = false;
            }
            if (bufAt >= limit) {
                return seq;
            }
            int last = lastIndexOf(buf, fill);
            int cut;
            boolean done = false;
            if (last < 0 && !eof) {
                buf = Arrays.copyOf(buf, buf.length * 2);               // one line longer than a chunk: read on
                continue;
            }
            cut = eof ? fill : last + 1;
            if (bufAt + cut > limit) {                                  // the range ends inside: keep the lines that start before it
                long k = limit - bufAt;
                int s0 = (int) k;
                if (k > 0 && buf[s0 - 1] != '\n') {
                    int j = indexOf(buf, s0, fill);
                    s0 = j < 0 ? fill : j + 1;
                }
                cut = s0;
                done = true;
            }
            if (cut > 0) {
                emit(s, seq++, buf, cut);
            }
            if (done || eof) {
                return seq;
            }
            byte[] next = new byte[Math.max(chunkBytes, fill - cut + 1024)];
            System.arraycopy(buf, cut, next, 0, fill - cut);
            fill -= cut;
            bufAt += cut;
            buf = next;
        }
    }

    private void emit(Split s, int seq, byte[] buf, int len) throws InterruptedException {
        budgetKb.acquire(permits(len, maxKb));
        queue.put(new Chunk(s, seq, buf, len));
    }

    /** The queue permits (KB, at most the whole budget) a chunk of {@code len} bytes holds while it waits to be parsed. */
    static int permits(int len, int maxKb) {
        return Math.min(maxKb, Math.max(1, len >> 10));
    }

    private static int indexOf(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] b, int to) {
        for (int i = to - 1; i >= 0; i--) {
            if (b[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** A file's bytes from {@code from} as a stream of large positional reads; counts the bytes and the time spent reading. */
    static final class RangeStream extends InputStream {
        private final RangeReader r;
        private final long end;
        private long pos;
        private final Meter meter;
        long nanos;
        long bytes;

        RangeStream(RangeReader r, long from, long end, Meter meter) {
            this.r = r;
            this.pos = from;
            this.end = end;
            this.meter = meter;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= end) {
                return -1;
            }
            long t0 = System.nanoTime();
            int want = (int) Math.min(len, end - pos);
            ByteBuffer dst = ByteBuffer.wrap(b, off, want);
            int n = r.read(pos, dst);
            long t = System.nanoTime() - t0;
            nanos += t;
            meter.busy(t0);
            if (n > 0) {
                pos += n;
                bytes += n;
                meter.bytesIn.add(n);
            }
            return n;
        }
    }
}
