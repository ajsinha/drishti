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
package com.ash.drishti.plugin.iceberg;

import com.ash.drishti.plugin.iceberg.IcebergLayout.Row;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An external sort of one business day's rows by id, for writers that receive rows in any order (a generator's
 * stream interleaves days and ids): rows are buffered in memory; {@link #spill} sorts the buffer and writes it to a run
 * file; {@link #merged} merges the runs and the rest of the buffer back in id order. A day of any size is sorted with
 * memory bounded by what the owner lets buffer. When an id appears more than once, the row added last wins.
 *
 * <p>{@link #add} is called by one thread; {@link #spill} may run on another while adds continue (the buffer is swapped
 * under a lock and written outside it). {@link #merged} is called once every spill has finished.
 */
final class SortedRuns implements Closeable {

    private static final Comparator<Row> BY_ID = Comparator.comparing(Row::id);

    private record Run(Path file, int seq) {}

    private final Path dir;
    private final String name;
    private final ReentrantLock lock = new ReentrantLock();
    private List<Row> buffer = new ArrayList<>();
    private final AtomicLong bufferedBytes = new AtomicLong();
    private final List<Run> runs = java.util.Collections.synchronizedList(new ArrayList<>());
    private int nextSeq;
    private long rows;

    SortedRuns(Path dir, String name) {
        this.dir = dir;
        this.name = name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** About what a row takes in memory: its text (one byte a character for ASCII), objects and promoted values. */
    static long weight(Row r) {
        return 96L + r.id().length() + (r.doc() == null ? 0 : r.doc().length()) + 64L * r.columns().size();
    }

    /** Buffers a row; returns its weight. */
    long add(Row r) {
        long w = weight(r);
        lock.lock();
        try {
            buffer.add(r);
            rows++;
            bufferedBytes.addAndGet(w);
        } finally {
            lock.unlock();
        }
        return w;
    }

    long bufferedBytes() {
        return bufferedBytes.get();
    }

    long rows() {
        lock.lock();
        try {
            return rows;
        } finally {
            lock.unlock();
        }
    }

    int runCount() {
        return runs.size();
    }

    /** Takes the buffer for a spill: the caller sorts and writes it with {@link #write} (outside any lock). */
    Taken take() {
        lock.lock();
        try {
            Taken t = new Taken(buffer, nextSeq++, bufferedBytes.get());
            buffer = new ArrayList<>();
            bufferedBytes.addAndGet(-t.bytes());
            return t;
        } finally {
            lock.unlock();
        }
    }

    /** A buffer taken for spilling, with its place in the input order. */
    record Taken(List<Row> rows, int seq, long bytes) {}

    /** Sorts a taken buffer and writes it as a run file. */
    void write(Taken t) {
        if (t.rows().isEmpty()) {
            return;
        }
        List<Row> sorted = dedupe(t.rows());
        try {
            Files.createDirectories(dir);
            Path file = Files.createTempFile(dir, name + "-", ".run");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 20))) {
                for (Row r : sorted) {
                    writeRow(out, r);
                }
            }
            runs.add(new Run(file, t.seq()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@link #take} then {@link #write}, on the calling thread. */
    void spill() {
        write(take());
    }

    /** Sorted by id, the last added of equal ids kept (a stable sort keeps the input order among equals). */
    private static List<Row> dedupe(List<Row> rows) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(BY_ID);
        List<Row> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            if (i + 1 < sorted.size() && sorted.get(i + 1).id().equals(sorted.get(i).id())) {
                continue;
            }
            out.add(sorted.get(i));
        }
        return out;
    }

    /** Every row in id order: the run files merged with the remaining buffer. Call once, after the last spill. */
    Iterator<Row> merged() {
        List<Cursor> cursors = new ArrayList<>();
        List<Run> all;
        synchronized (runs) {
            all = new ArrayList<>(runs);
        }
        try {
            for (Run r : all) {
                cursors.add(new FileCursor(r.file(), r.seq()));
            }
        } catch (IOException e) {
            cursors.forEach(Cursor::close);
            throw new UncheckedIOException(e);
        }
        Taken rest = take();
        cursors.add(new ListCursor(dedupe(rest.rows()).iterator(), Integer.MAX_VALUE));   // the newest input
        PriorityQueue<Cursor> queue = new PriorityQueue<>(Comparator.comparing((Cursor c) -> c.current().id())
                .thenComparing(Comparator.comparingInt(Cursor::seq).reversed()));
        for (Cursor c : cursors) {
            if (c.advance()) {
                queue.add(c);
            } else {
                c.close();
            }
        }
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return !queue.isEmpty();
            }

            @Override
            public Row next() {
                if (queue.isEmpty()) {
                    throw new NoSuchElementException();
                }
                Cursor top = queue.poll();
                Row row = top.current();
                requeue(top);
                while (!queue.isEmpty() && queue.peek().current().id().equals(row.id())) {
                    requeue(queue.poll());                      // an older copy of the same id: dropped
                }
                return row;
            }

            private void requeue(Cursor c) {
                if (c.advance()) {
                    queue.add(c);
                } else {
                    c.close();
                }
            }
        };
    }

    @Override
    public void close() {
        synchronized (runs) {
            for (Run r : runs) {
                try {
                    Files.deleteIfExists(r.file());
                } catch (IOException e) {
                    // a temporary file left behind; the spill directory is the operator's to clean
                }
            }
            runs.clear();
        }
    }

    private interface Cursor {
        boolean advance();

        Row current();

        int seq();

        void close();
    }

    private static final class ListCursor implements Cursor {
        private final Iterator<Row> it;
        private final int seq;
        private Row current;

        ListCursor(Iterator<Row> it, int seq) {
            this.it = it;
            this.seq = seq;
        }

        @Override
        public boolean advance() {
            current = it.hasNext() ? it.next() : null;
            return current != null;
        }

        @Override
        public Row current() {
            return current;
        }

        @Override
        public int seq() {
            return seq;
        }

        @Override
        public void close() {
        }
    }

    private static final class FileCursor implements Cursor {
        private final DataInputStream in;
        private final int seq;
        private Row current;

        FileCursor(Path file, int seq) throws IOException {
            this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 20));
            this.seq = seq;
        }

        @Override
        public boolean advance() {
            try {
                current = readRow(in);
            } catch (EOFException e) {
                current = null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return current != null;
        }

        @Override
        public Row current() {
            return current;
        }

        @Override
        public int seq() {
            return seq;
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException e) {
                // nothing to do
            }
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0) {
            return null;
        }
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeRow(DataOutputStream out, Row r) throws IOException {
        writeString(out, r.id());
        writeString(out, r.doc());
        out.writeShort(r.columns().size());
        for (Map.Entry<String, Object> e : r.columns().entrySet()) {
            writeString(out, e.getKey());
            Object v = e.getValue();
            if (v == null) {
                out.writeByte(0);
            } else if (v instanceof Number n) {
                out.writeByte(1);
                out.writeDouble(n.doubleValue());
            } else {
                out.writeByte(2);
                writeString(out, String.valueOf(v));
            }
        }
    }

    private static Row readRow(DataInputStream in) throws IOException {
        String id = readString(in);                             // EOFException at the end of the run
        String doc = readString(in);
        int n = in.readShort();
        Map<String, Object> cols = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String path = readString(in);
            byte type = in.readByte();
            cols.put(path, type == 0 ? null : type == 1 ? (Object) in.readDouble() : readString(in));
        }
        return new Row(id, doc, cols);
    }
}
