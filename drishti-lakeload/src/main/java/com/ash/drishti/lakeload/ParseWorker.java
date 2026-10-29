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

import com.ash.drishti.lakeload.SourceReader.Chunk;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One parser thread: takes chunks of lines from the readers' queue, turns each line into a row record ({@link RowParser}) and buffers it
 * under its business date; when its share of {@code sort-buffer-mb} is full it sorts its buffers and spills them as runs
 * ({@link Runs}). It counts what it read: documents, rejects by reason (each reject also written, with its line, to a scratch file), the
 * rows of each date and partition, the ids of each date (to count ids seen twice), and which promoted columns hold only numbers.
 */
final class ParseWorker implements Callable<Void> {

    /** A place in the input: split, chunk, line within the chunk (1-based); orders rejects and the sample row as the input orders them. */
    record Where(int split, int seq, int line) implements Comparable<Where> {
        @Override
        public int compareTo(Where o) {
            int c = Integer.compare(split, o.split);
            if (c == 0) {
                c = Integer.compare(seq, o.seq);
            }
            return c != 0 ? c : Integer.compare(line, o.line);
        }
    }

    record Reject(Where where, String reason) {}

    /** One date's rows held by this thread. */
    static final class DateRows {
        Runs.Buffer buffer;
        final int arena;
        final List<Runs.Run> runs = new ArrayList<>();
        long[] hashes = new long[1024];
        int nHashes;
        /** Per row, beside its hash: the second hash, the {@code latest-by} timestamp, the place in the input, the partition (index). */
        long[] hashes2 = new long[1024];
        long[] latest = new long[1024];
        long[] order = new long[1024];
        int[] part = new int[1024];
        final Map<List<String>, Long> partitions = new LinkedHashMap<>();
        final Map<List<String>, Integer> partIndex = new HashMap<>();
        final List<List<String>> partValues = new ArrayList<>();
        long rows;

        DateRows(int arena) {
            this.arena = arena;
            this.buffer = new Runs.Buffer(arena);
        }
    }

    private final int number;
    private final RowParser parser;
    private final RowView view;
    private final BlockingQueue<Chunk> queue;
    private final Semaphore budgetKb;
    private final int maxKb;
    private final long budget;
    private final Path spillDir;
    private final Meter parse;
    private final Meter spill;
    private final LineCounts lines;
    private final AtomicLong documentsSeen;
    private final AtomicLong rejectedSeen;
    private final int keep;

    final Map<Integer, DateRows> dates = new TreeMap<>();
    final Map<String, Long> rejectsByReason = new LinkedHashMap<>();
    final List<Reject> firstRejects = new ArrayList<>();
    long documents;
    long rejected;
    Where sampleAt;
    String sampleId;
    int sampleDay;
    private long buffered;
    private int spills;
    private DataOutputStream rejectsOut;
    final Path rejectsFile;

    ParseWorker(int number, RowPlan plan, BlockingQueue<Chunk> queue, Semaphore budgetKb, int maxKb, long budget, Path spillDir, Meter parse,
            Meter spill, LineCounts lines, AtomicLong documentsSeen, AtomicLong rejectedSeen, int keep) {
        this.number = number;
        this.parser = new RowParser(plan);
        this.view = new RowView(plan);
        this.queue = queue;
        this.budgetKb = budgetKb;
        this.maxKb = maxKb;
        this.budget = budget;
        this.spillDir = spillDir;
        this.parse = parse;
        this.spill = spill;
        this.lines = lines;
        this.documentsSeen = documentsSeen;
        this.rejectedSeen = rejectedSeen;
        this.keep = keep;
        this.rejectsFile = spillDir.resolve("rejects-" + number + ".bin");
    }

    boolean[] notNumeric() {
        return parser.notNumeric;
    }

    boolean[] seen() {
        return parser.seen;
    }

    @Override
    public Void call() throws Exception {
        try {
            while (true) {
                Chunk c = queue.take();
                if (c == Chunk.END) {
                    break;
                }
                try {
                    chunk(c);
                } finally {
                    budgetKb.release(SourceReader.permits(c.len(), maxKb));
                }
            }
        } finally {
            if (rejectsOut != null) {
                rejectsOut.close();
            }
        }
        return null;
    }

    private void chunk(Chunk c) throws IOException {
        long t0 = System.nanoTime();
        byte[] a = c.data();
        int len = c.len();
        int line = 0;
        int start = 0;
        long docs = 0;
        long rows = 0;
        while (start < len) {
            int nl = start;
            while (nl < len && a[nl] != '\n') {
                nl++;
            }
            line++;
            int end = nl;
            int s = start;
            while (s < end && isBlank(a[s])) {
                s++;
            }
            int e = end;
            while (e > s && isBlank(a[e - 1])) {
                e--;
            }
            if (e > s) {
                docs++;
                String reason = parser.parse(a, s, e - s);
                if (reason == null) {
                    rows++;
                    accept(c, line);
                } else {
                    reject(c, line, reason, a, s, e - s);
                }
            }
            start = nl + 1;
        }
        lines.set(c.split().id(), c.seq(), line);
        documents += docs;
        documentsSeen.addAndGet(docs);
        parse.rows.add(rows);
        parse.bytesIn.add(len);
        parse.busy(t0);
        if (buffered > budget) {
            spillAll();
        }
    }

    private static boolean isBlank(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }

    private void accept(Chunk c, int line) {
        DateRows d = dates.computeIfAbsent(parser.epochDay, k -> new DateRows((int) Math.min(8 << 20, Math.max(64 << 10, budget / 4))));
        long order = Latest.order(c.split().id(), c.seq(), line);
        parser.record.putLong(order);                                  // the record's place in the input, last: the write stage drops duplicates by it
        long before = d.buffer.bytes;
        d.buffer.add(parser.record.a, parser.record.n);
        buffered += d.buffer.bytes - before;
        if (d.nHashes == d.hashes.length) {
            int n = d.nHashes * 2;
            d.hashes = Arrays.copyOf(d.hashes, n);
            d.hashes2 = Arrays.copyOf(d.hashes2, n);
            d.latest = Arrays.copyOf(d.latest, n);
            d.order = Arrays.copyOf(d.order, n);
            d.part = Arrays.copyOf(d.part, n);
        }
        List<String> values;
        if (view.plan().partition.isEmpty()) {
            values = List.of();
        } else {
            view.at(parser.record.a, 0);
            String[] v = new String[view.partitions()];
            for (int j = 0; j < v.length; j++) {
                v[j] = view.partition(j);
            }
            values = Arrays.asList(v);
        }
        d.partitions.merge(values, 1L, Long::sum);
        Integer pi = d.partIndex.get(values);
        if (pi == null) {
            pi = d.partValues.size();
            d.partIndex.put(values, pi);
            d.partValues.add(values);
        }
        int i = d.nHashes++;
        d.hashes[i] = parser.idHash;
        d.hashes2[i] = parser.idHash2;
        d.latest[i] = parser.latest;
        d.order[i] = order;
        d.part[i] = pi;
        d.rows++;
        Where w = new Where(c.split().id(), c.seq(), line);
        if (sampleAt == null || w.compareTo(sampleAt) < 0) {
            sampleAt = w;
            sampleId = parser.id;
            sampleDay = parser.epochDay;
        }
    }

    private void reject(Chunk c, int line, String reason, byte[] a, int off, int len) throws IOException {
        rejected++;
        rejectedSeen.incrementAndGet();
        rejectsByReason.merge(reason, 1L, Long::sum);
        Where w = new Where(c.split().id(), c.seq(), line);
        firstRejects.add(new Reject(w, reason));
        if (firstRejects.size() > keep * 2) {
            firstRejects.sort((x, y) -> x.where().compareTo(y.where()));
            firstRejects.subList(keep, firstRejects.size()).clear();
        }
        if (rejectsOut == null) {
            rejectsOut = new DataOutputStream(new BufferedOutputStream(com.ash.drishti.api.ChannelIo.newOutputStream(rejectsFile), 1 << 16));
        }
        rejectsOut.writeInt(w.split());
        rejectsOut.writeInt(w.seq());
        rejectsOut.writeInt(w.line());
        byte[] r = reason.getBytes(StandardCharsets.UTF_8);
        rejectsOut.writeInt(r.length);
        rejectsOut.write(r);
        rejectsOut.writeInt(len);
        rejectsOut.write(a, off, len);
    }

    /** Sorts every date's buffer and writes it as a run file; the memory is freed. */
    private void spillAll() throws IOException {
        long t0 = System.nanoTime();
        for (Map.Entry<Integer, DateRows> e : dates.entrySet()) {
            DateRows d = e.getValue();
            if (d.buffer.n == 0) {
                continue;
            }
            long bytes = d.buffer.bytes;
            Path f = spillDir.resolve("run-" + number + "-" + e.getKey() + "-" + spills++ + ".bin");
            d.runs.add(d.buffer.spill(f));
            spill.bytesOut.add(bytes);
            spill.rows.add(d.runs.get(d.runs.size() - 1).rows);
            d.buffer = new Runs.Buffer(d.arena);
        }
        buffered = 0;
        spill.busy(t0);
    }

    /** After the input: each date's remaining rows sorted into a run in memory. */
    void finish() {
        for (DateRows d : dates.values()) {
            if (d.buffer.n > 0) {
                d.runs.add(d.buffer.toMemory());
            }
            d.buffer = null;
        }
    }

    /** The lines of each chunk of each split, as the parsers count them, to name a reject's line in its file. */
    static final class LineCounts {
        private final Map<Integer, int[]> bySplit = new HashMap<>();

        synchronized void set(int split, int seq, int count) {
            int[] a = bySplit.computeIfAbsent(split, k -> new int[16]);
            if (seq >= a.length) {
                a = Arrays.copyOf(a, Math.max(seq + 1, a.length * 2));
                bySplit.put(split, a);
            }
            a[seq] = count;
        }

        synchronized long total(int split) {
            long n = 0;
            for (int x : bySplit.getOrDefault(split, new int[0])) {
                n += x;
            }
            return n;
        }

        synchronized long before(int split, int seq) {
            int[] a = bySplit.getOrDefault(split, new int[0]);
            long n = 0;
            for (int i = 0; i < seq && i < a.length; i++) {
                n += a[i];
            }
            return n;
        }
    }
}
