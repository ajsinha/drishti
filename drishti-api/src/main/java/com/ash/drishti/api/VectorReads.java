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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A date's vector files decoded several at once and handed to the reader in file order (VAR_GUIDE.md section 27a.6): the same rows in
 * the same order, so the same date, whether one thread decodes or several.
 *
 * <p>A reader that wants it wraps its sink with {@link #parallel}; a connector reads the date's files through {@link #inFileOrder}, which
 * reads them one after the other on the caller's thread for any other sink. With a parallel sink, up to {@code threads} files decode at
 * once, each on its own worker into chunks of copied rows (ids, texts and values, a value copied as decoded, so bit for bit), and the
 * caller's thread replays the chunks to the sink: the whole first file, then the second, and so on. The sink is only ever called on the
 * caller's thread. The decoded rows waiting are bounded: at most {@code threads} files in flight, each with at most a few chunks queued,
 * about {@code bufferBytes} in all; a worker whose queue is full waits for the reader. A file that fails fails the read with its error, the
 * other workers stop at their next chunk, and nothing after the failing file reaches the sink.
 */
public final class VectorReads {

    private VectorReads() {
    }

    /**
     * How a read may decode: {@code threads} files at once (1: one after the other on the caller's thread), about {@code bufferBytes} of
     * decoded rows waiting at most.
     */
    public record Options(int threads, long bufferBytes) {
        public Options {
            threads = Math.max(1, threads);
            bufferBytes = Math.max(1L << 20, bufferBytes);
        }

        /** One file after the other, on the caller's thread. */
        public static Options serial() {
            return new Options(1, 1L << 20);
        }
    }

    /** One file of a date read into a sink (a connector's per-file decode); its buffers are its own, so files may decode at once. */
    @FunctionalInterface
    public interface FileRead {
        void read(int file, VectorSink to) throws Exception;
    }

    /** A sink a connector may fill from several decoding threads, delivered to {@code to} in file order on the reading thread. */
    public static final class Parallel implements VectorSink {
        private final VectorSink to;
        private final Options options;

        Parallel(VectorSink to, Options options) {
            this.to = to;
            this.options = options;
        }

        @Override
        public void row(String id, double[] values, int length, String[] texts) {
            to.row(id, values, length, texts);
        }

        public Options options() {
            return options;
        }
    }

    /** {@code to}, marked so a connector may decode several files at once (rows still reach {@code to} in file order, on this thread). */
    public static VectorSink parallel(VectorSink to, Options options) {
        return options == null || options.threads() <= 1 ? to : new Parallel(to, options);
    }

    /** Chunks queued per file in flight. */
    static final int DEPTH = 3;

    /**
     * A chunk's bytes: at most {@code bufferBytes} in all, {@code threads} files in flight, each a queue of {@link #DEPTH} chunks (its
     * end marker beside them) and the one it fills, and the chunk the reader replays.
     */
    static long chunkBytes(long bufferBytes, int threads) {
        return Math.max(64L << 10, Math.min(8L << 20, bufferBytes / ((long) threads * (DEPTH + 2))));
    }

    /**
     * The most decoded bytes one file (or part of one) should hold for its decode never to wait for the reader: a connector that can
     * cut a file (by row groups) cuts it into parts of about this size, so each decodes whole while the reader takes the ones before.
     * {@code Long.MAX_VALUE} for a sink that reads on one thread.
     */
    public static long partBytes(VectorSink sink) {
        if (!(sink instanceof Parallel p)) {
            return Long.MAX_VALUE;
        }
        return chunkBytes(p.options.bufferBytes(), p.options.threads()) * (DEPTH + 1);
    }

    /** The decoding threads a reader allowed {@code sink}: 1 unless it came from {@link #parallel}. */
    public static int threads(VectorSink sink) {
        return sink instanceof Parallel p ? p.options.threads() : 1;
    }

    /**
     * Reads the {@code files} (a date's file indexes, in file order) into {@code sink}: one after the other on this thread, or, when
     * {@code sink} came from {@link #parallel} and there is more than one file, several at once as the class says.
     */
    public static void inFileOrder(int[] files, VectorSink sink, FileRead read) {
        if (!(sink instanceof Parallel p) || files.length <= 1) {
            for (int f : files) {
                run(read, f, sink);
            }
            return;
        }
        new Ordered(files, p.to, p.options, read).run();
    }

    private static void run(FileRead read, int file, VectorSink sink) {
        try {
            read.read(file, sink);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Rows of one file copied as they decode: ids, lengths, the values back to back, the texts row by row. */
    static final class Chunk {
        static final Chunk END = new Chunk(0);

        String[] ids;
        int[] lengths;
        double[] values;
        String[] texts;
        int rows;
        int used;
        int textCount = -1;
        long bytes;

        Chunk(int rows) {
            ids = new String[rows];
            lengths = new int[rows];
            values = new double[rows == 0 ? 0 : 1024];
        }

        void clear() {
            java.util.Arrays.fill(ids, 0, rows, null);
            if (texts != null) {
                java.util.Arrays.fill(texts, null);
            }
            rows = 0;
            used = 0;
            bytes = 0;
        }

        boolean full(long limit) {
            return rows == ids.length || bytes >= limit;
        }

        void add(String id, double[] v, int len, String[] t) {
            if (textCount < 0 || texts == null) {
                textCount = t == null ? 0 : t.length;
                texts = new String[ids.length * textCount];
            }
            int at = rows++;
            ids[at] = id;
            lengths[at] = len;
            if (len > 0) {
                if (used + len > values.length) {
                    values = java.util.Arrays.copyOf(values, Math.max(used + len, values.length * 2));
                }
                System.arraycopy(v, 0, values, used, len);
                used += len;
            }
            int n = Math.min(textCount, t == null ? 0 : t.length);
            for (int c = 0; c < n; c++) {
                texts[at * textCount + c] = t[c];
            }
            bytes += 56L + 8L * Math.max(0, len) + 40L * textCount;
        }
    }

    /** The parallel read: a window of {@code threads} files decoding, delivered in order. */
    private static final class Ordered {
        private static final AtomicInteger POOLS = new AtomicInteger();
        private static final int ROWS = 1024;

        private final int[] files;
        private final VectorSink to;
        private final FileRead read;
        private final int threads;
        private final int depth;
        private final long chunkBytes;
        private final Slot[] slots;
        private final java.util.concurrent.ConcurrentLinkedQueue<Chunk> free = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private volatile boolean stopped;

        Ordered(int[] files, VectorSink to, Options o, FileRead read) {
            this.files = files;
            this.to = to;
            this.read = read;
            this.threads = Math.min(o.threads(), files.length);
            this.depth = DEPTH;
            this.chunkBytes = chunkBytes(o.bufferBytes(), threads);
            this.slots = new Slot[files.length];
        }

        /** One file's chunks on their way to the reader, and the file's failure if it failed. */
        private final class Slot {
            final ArrayBlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(depth);
            volatile Throwable failed;

            void put(Chunk c) throws InterruptedException {
                while (!stopped) {
                    if (queue.offer(c, 50, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                }
                throw new Stopped();
            }
        }

        /** Thrown in a worker once the read has stopped (another file failed, or the sink threw). */
        private static final class Stopped extends RuntimeException {
            private static final long serialVersionUID = 1L;

            Stopped() {
                super(null, null, false, false);
            }
        }

        void run() {
            int n = POOLS.incrementAndGet();
            AtomicInteger t = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                Thread th = new Thread(r, "drishti-vector-read-" + n + "-" + t.incrementAndGet());
                th.setDaemon(true);
                return th;
            });
            try {
                int next = 0;
                for (; next < threads; next++) {
                    submit(pool, next);
                }
                double[] buf = new double[512];
                String[] texts = null;
                for (int i = 0; i < files.length; i++) {
                    Slot s = slots[i];
                    while (true) {
                        Chunk c = s.queue.take();
                        if (c == Chunk.END) {
                            break;
                        }
                        if (texts == null || texts.length != c.textCount) {
                            texts = new String[Math.max(0, c.textCount)];
                        }
                        int at = 0;
                        for (int r = 0; r < c.rows; r++) {
                            int len = c.lengths[r];
                            if (len > 0) {
                                if (buf.length < len) {
                                    buf = new double[Math.max(len, buf.length * 2)];
                                }
                                System.arraycopy(c.values, at, buf, 0, len);
                                at += len;
                            }
                            for (int x = 0; x < texts.length; x++) {
                                texts[x] = c.texts[r * c.textCount + x];
                            }
                            to.row(c.ids[r], buf, len, texts);
                        }
                        c.clear();
                        free.offer(c);
                    }
                    slots[i] = null;
                    if (s.failed != null) {
                        throw rethrow(s.failed);
                    }
                    if (next < files.length) {
                        submit(pool, next++);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the read was interrupted", e);
            } finally {
                stopped = true;
                pool.shutdown();
            }
        }

        private void submit(ExecutorService pool, int i) {
            Slot s = new Slot();
            slots[i] = s;
            int file = files[i];
            pool.execute(() -> {
                Chunk[] cur = {chunk()};
                try {
                    read.read(file, (id, values, len, texts) -> {
                        if (stopped) {
                            throw new Stopped();
                        }
                        cur[0].add(id, values, len, texts);
                        if (cur[0].full(chunkBytes)) {
                            try {
                                s.put(cur[0]);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new Stopped();
                            }
                            cur[0] = chunk();
                        }
                    });
                    if (cur[0].rows > 0) {
                        s.put(cur[0]);
                    }
                } catch (Stopped e) {
                    return;                                        // the read has stopped: nobody takes this file's rows
                } catch (Throwable e) {
                    s.failed = e;
                    if (cur[0].rows > 0) {
                        try {
                            s.put(cur[0]);                         // the rows before the failure, as one thread would have given them
                        } catch (InterruptedException | Stopped x) {
                            return;
                        }
                    }
                } finally {
                    if (!stopped) {
                        endOf(s);
                    }
                }
            });
        }

        /** The file's end: after its last chunk, or after its failure (then the reader throws it). */
        private void endOf(Slot s) {
            try {
                s.put(Chunk.END);
            } catch (InterruptedException | Stopped e) {
                // the read has stopped: nobody waits for this file
            }
        }

        private Chunk chunk() {
            Chunk c = free.poll();
            return c != null ? c : new Chunk(ROWS);
        }

        private static RuntimeException rethrow(Throwable e) {
            if (e instanceof RuntimeException re) {
                return re;
            }
            if (e instanceof Error er) {
                throw er;
            }
            return new IllegalStateException(e.getMessage(), e);
        }
    }
}
