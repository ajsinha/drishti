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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * File-channel reads and writes that never hoard direct memory.
 *
 * <p>A channel read into (or write from) a <em>heap</em> buffer makes the JDK copy through a temporary direct buffer as
 * large as the transfer, and keep it cached per carrier thread (up to {@code jdk.nio.maxCachedBufferSize}, unlimited by
 * default). Many threads doing multi-megabyte reads therefore pin many megabytes of direct memory each, until
 * {@code -XX:MaxDirectMemorySize} is exhausted and every read fails with "Cannot reserve ... bytes of direct buffer
 * memory". Here a heap transfer goes through one of a small, bounded pool of reusable direct buffers of
 * {@link #CHUNK_BYTES} each ({@link #POOL_BUFFERS} at most, so at most {@link #POOL_BYTES} in all); when every pooled
 * buffer is busy, the transfer is cut into slices of {@link #CHUNK_BYTES}, so the JDK's own temporary buffer stays that
 * small. A direct buffer is passed to the channel as it is. Thread-safe; never blocks on the pool.
 */
public final class ChannelIo {

    /** One transfer's slice: below the size the JDK caches with {@code -Djdk.nio.maxCachedBufferSize=262144}. */
    public static final int CHUNK_BYTES = 128 << 10;
    /** The pool's buffers at most. */
    public static final int POOL_BUFFERS = 64;
    /** The direct memory the pool can hold at most. */
    public static final long POOL_BYTES = (long) CHUNK_BYTES * POOL_BUFFERS;

    private static final ConcurrentLinkedQueue<ByteBuffer> FREE = new ConcurrentLinkedQueue<>();   // lock-free; never more than MADE
    private static final AtomicInteger MADE = new AtomicInteger();

    private ChannelIo() {
    }

    /** The pool's direct buffers made so far (each {@link #CHUNK_BYTES}); never more than {@link #POOL_BUFFERS}. */
    public static int pooledBuffers() {
        return MADE.get();
    }

    /**
     * Reads up to {@code dst.remaining()} bytes from {@code position}; stops early only at the end of the file.
     *
     * @return the bytes read, or -1 when {@code position} is at or past the end of the file
     */
    public static int read(FileChannel ch, ByteBuffer dst, long position) throws IOException {
        return transferIn(ch, dst, position);
    }

    /** Fills {@code dst} from {@code position}, or throws {@link EOFException} when the file ends first. */
    public static void readFully(FileChannel ch, ByteBuffer dst, long position) throws IOException {
        long at = position;
        while (dst.hasRemaining()) {
            int n = read(ch, dst, at);
            if (n < 0) {
                throw new EOFException("end of file at " + at + " with " + dst.remaining() + " bytes still to read");
            }
            at += n;
        }
    }

    /** A sequential read at the channel's own position: up to {@code dst.remaining()} bytes, -1 at the end. */
    public static int read(ReadableByteChannel ch, ByteBuffer dst) throws IOException {
        return transferIn(ch, dst, -1);
    }

    /** Writes all of {@code src} at {@code position}. */
    public static long write(FileChannel ch, ByteBuffer src, long position) throws IOException {
        return transferOut(ch, src, position);
    }

    /** Writes all of {@code src} at the channel's own position. */
    public static long write(WritableByteChannel ch, ByteBuffer src) throws IOException {
        return transferOut(ch, src, -1);
    }

    /** A stream over {@code ch} from {@code position} (positional reads: the channel's own position is not moved). */
    public static InputStream inputStream(FileChannel ch, long position) {
        return new InputStream() {
            private long pos = position;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) <= 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                int n = ChannelIo.read(ch, ByteBuffer.wrap(b, off, len), pos);
                if (n > 0) {
                    pos += n;
                }
                return n;
            }

            @Override
            public void close() throws IOException {
                ch.close();
            }
        };
    }

    /** A stream writing to {@code ch} at its own position; closing it closes the channel. Wrap it for small writes. */
    public static OutputStream outputStream(WritableByteChannel ch) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                ChannelIo.write(ch, ByteBuffer.wrap(b, off, len));
            }

            @Override
            public void close() throws IOException {
                ch.close();
            }
        };
    }

    /**
     * As {@link java.nio.file.Files#newInputStream}, without a temporary direct buffer as large as a read: on Java 21 a
     * {@code BufferedInputStream} of 1 MB over {@code Files.newInputStream} reads through a 1 MB direct buffer.
     */
    public static InputStream newInputStream(java.nio.file.Path file) throws IOException {
        return inputStream(FileChannel.open(file, java.nio.file.StandardOpenOption.READ), 0);
    }

    /** As {@link java.nio.file.Files#newOutputStream} (no options: create or truncate), writing in slices of {@link #CHUNK_BYTES}. */
    public static OutputStream newOutputStream(java.nio.file.Path file, java.nio.file.OpenOption... options) throws IOException {
        java.nio.file.OpenOption[] opts = options.length == 0
                ? new java.nio.file.OpenOption[] {java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE}
                : options;
        return outputStream(FileChannel.open(file, opts));
    }

    /** All of a file's bytes (as {@link java.nio.file.Files#readAllBytes}, without a temporary direct buffer that large). */
    public static byte[] readAllBytes(java.nio.file.Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            long size = ch.size();
            if (size > Integer.MAX_VALUE - 8) {
                throw new IOException(file + " is over 2 GB: too large to read whole");
            }
            ByteBuffer b = ByteBuffer.allocate((int) size);
            long at = 0;
            while (b.hasRemaining()) {
                int n = read(ch, b, at);
                if (n < 0) {
                    break;                                       // shrank while reading
                }
                at += n;
            }
            byte[] all = b.array();
            if (b.hasRemaining()) {
                return java.util.Arrays.copyOf(all, b.position());
            }
            // grew while reading: take the rest too
            java.io.ByteArrayOutputStream rest = null;
            ByteBuffer more = ByteBuffer.allocate(8192);
            int n;
            while ((n = read(ch, more.clear(), at)) > 0) {
                if (rest == null) {
                    rest = new java.io.ByteArrayOutputStream();
                    rest.write(all);
                }
                rest.write(more.array(), 0, n);
                at += n;
            }
            return rest == null ? all : rest.toByteArray();
        }
    }

    private static int transferIn(ReadableByteChannel ch, ByteBuffer dst, long position) throws IOException {
        if (!dst.hasRemaining()) {
            return 0;
        }
        if (dst.isDirect()) {
            return position < 0 ? ch.read(dst) : ((FileChannel) ch).read(dst, position);
        }
        ByteBuffer pooled = borrow();
        try {
            int total = 0;
            while (dst.hasRemaining()) {
                int want = Math.min(CHUNK_BYTES, dst.remaining());
                ByteBuffer into = pooled != null ? pooled.clear().limit(want) : dst.slice(dst.position(), want);
                int n = position < 0 ? ch.read(into) : ((FileChannel) ch).read(into, position + total);
                if (n < 0) {
                    return total == 0 ? -1 : total;
                }
                if (pooled != null) {
                    dst.put(pooled.flip());
                } else {
                    dst.position(dst.position() + n);
                }
                total += n;
                if (n < want) {
                    break;                                       // a short read: the end of the file, or a pipe's worth
                }
            }
            return total;
        } finally {
            release(pooled);
        }
    }

    private static long transferOut(WritableByteChannel ch, ByteBuffer src, long position) throws IOException {
        long total = 0;
        if (src.isDirect()) {
            while (src.hasRemaining()) {
                total += position < 0 ? ch.write(src) : ((FileChannel) ch).write(src, position + total);
            }
            return total;
        }
        ByteBuffer pooled = borrow();
        try {
            while (src.hasRemaining()) {
                int want = Math.min(CHUNK_BYTES, src.remaining());
                ByteBuffer from;
                if (pooled != null) {
                    pooled.clear();
                    pooled.put(pooled.position(), src, src.position(), want).limit(want);
                    from = pooled;
                } else {
                    from = src.slice(src.position(), want);
                }
                while (from.hasRemaining()) {
                    total += position < 0 ? ch.write(from) : ((FileChannel) ch).write(from, position + total);
                }
                src.position(src.position() + want);
            }
            return total;
        } finally {
            release(pooled);
        }
    }

    private static ByteBuffer borrow() {
        ByteBuffer b = FREE.poll();
        if (b != null) {
            return b;
        }
        if (MADE.incrementAndGet() <= POOL_BUFFERS) {
            return ByteBuffer.allocateDirect(CHUNK_BYTES);
        }
        MADE.decrementAndGet();
        return null;                                             // every pooled buffer is busy: slice the heap buffer
    }

    private static void release(ByteBuffer b) {
        if (b != null) {
            FREE.offer(b);
        }
    }
}
