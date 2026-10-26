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
package com.ash.drishti.compute.vector;

import java.io.IOException;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A row-major matrix of float32 or float64 values in a file, memory-mapped read-only (VAR_GUIDE.md section 26: the hot file's position and
 * trade matrices; the load's spill of float64 rows). Only the pages a sum touches become resident; the operating system keeps or drops
 * them like any file cache, so a date's vectors do not take the Java heap. Rows are mapped in pieces of at most 2 GB (a mapping's limit);
 * a row never straddles two pieces. Every sum accumulates in {@code double}.
 *
 * <p><b>Closing safely.</b> Reading unmapped memory would crash the JVM, so every read enters a guard: {@link #close} marks the matrix
 * closing and unmaps it only when no reader is inside; a reader that arrives after that gets {@link Closed} (a request that outlived its
 * date's eviction), never a crash. On Java 22 and later the mapping is an arena's, and a stray read after the unmap fails with an
 * exception too ({@link FileMaps}).
 */
public final class MappedMatrix implements Vectors, AutoCloseable {

    /** How each value is stored. */
    public enum Type {
        FLOAT32(4), FLOAT64(8);

        final int bytes;

        Type(int bytes) {
            this.bytes = bytes;
        }

        public int bytes() {
            return bytes;
        }
    }

    /** A read of a matrix that was closed (its date evicted while a request still used it). */
    public static final class Closed extends IllegalStateException {
        public Closed(Path file) {
            super("the vectors of " + file.getFileName() + " were unloaded while this answer was computed; ask again");
        }
    }

    private static final ThreadLocal<float[]> FLOATS = ThreadLocal.withInitial(() -> new float[0]);
    private static final ThreadLocal<double[]> DOUBLES = ThreadLocal.withInitial(() -> new double[0]);

    private final Path file;
    private final Type type;
    private final int width;
    private final int rows;
    private final int rowsPerMap;
    private final FileMaps.Mapping[] maps;
    private final FloatBuffer[] floats;
    private final DoubleBuffer[] doubles;
    private final AtomicInteger readers = new AtomicInteger();
    private final AtomicBoolean unmapped = new AtomicBoolean();
    private volatile boolean closing;
    private final java.util.concurrent.atomic.AtomicReference<Runnable> afterUnmap = new java.util.concurrent.atomic.AtomicReference<>();

    private MappedMatrix(Path file, Type type, int width, int rows, int rowsPerMap, FileMaps.Mapping[] maps) {
        this.file = file;
        this.type = type;
        this.width = width;
        this.rows = rows;
        this.rowsPerMap = rowsPerMap;
        this.maps = maps;
        this.floats = new FloatBuffer[maps.length];
        this.doubles = new DoubleBuffer[maps.length];
        for (int i = 0; i < maps.length; i++) {
            if (type == Type.FLOAT32) {
                floats[i] = maps[i].buffer().asFloatBuffer();
            } else {
                doubles[i] = maps[i].buffer().asDoubleBuffer();
            }
        }
    }

    /**
     * Maps {@code rows} rows of {@code width} values of {@code type} starting at byte {@code offset} of {@code file}.
     *
     * @param rowsPerMap the most rows in one mapping (0: as many as fit in 2 GB)
     */
    public static MappedMatrix open(Path file, long offset, int rows, int width, Type type, int rowsPerMap) throws IOException {
        if (width <= 0 || rows < 0) {
            throw new IllegalArgumentException("a matrix has a positive width and no negative rows");
        }
        long rowBytes = (long) width * type.bytes;
        int fit = (int) Math.max(1, (Integer.MAX_VALUE - 4096L) / rowBytes);
        int per = rowsPerMap <= 0 ? fit : Math.min(fit, rowsPerMap);
        int pieces = rows == 0 ? 0 : (rows + per - 1) / per;
        FileMaps.Mapping[] maps = new FileMaps.Mapping[pieces];
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            for (int p = 0; p < pieces; p++) {
                int n = Math.min(per, rows - p * per);
                maps[p] = FileMaps.map(ch, offset + (long) p * per * rowBytes, n * rowBytes);
            }
        } catch (IOException | RuntimeException e) {
            for (FileMaps.Mapping m : maps) {
                if (m != null) {
                    m.release();
                }
            }
            throw e;
        }
        return new MappedMatrix(file, type, width, rows, per, maps);
    }

    /** How this JVM maps and releases files (Admin, the status answer). */
    public static String mappingMethod() {
        return FileMaps.method();
    }

    public Path file() {
        return file;
    }

    public Type type() {
        return type;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int rows() {
        return rows;
    }

    @Override
    public int chunkCount() {
        return Math.max(1, maps.length);
    }

    @Override
    public long bytes() {
        return (long) rows * width * type.bytes;
    }

    public boolean closed() {
        return closing;
    }

    // --------------------------------------------------------------------------------------------------------------------- reads

    private void enter() {
        if (closing) {
            throw new Closed(file);
        }
        readers.incrementAndGet();
        if (closing) {                                                 // closed between the check and the count: leave again
            exit();
            throw new Closed(file);
        }
    }

    private void exit() {
        if (readers.decrementAndGet() == 0 && closing) {
            unmap();
        }
    }

    @Override
    public double value(int row, int j) {
        enter();
        try {
            int p = row / rowsPerMap;
            int at = (row % rowsPerMap) * width + j;
            return type == Type.FLOAT32 ? floats[p].get(at) : doubles[p].get(at);
        } finally {
            exit();
        }
    }

    @Override
    public void copy(int row, double[] out) {
        java.util.Arrays.fill(out, 0, width, 0.0);
        addInto(out, row);
    }

    @Override
    public void addInto(double[] acc, int row) {
        addRangeInto(acc, row, row + 1);
    }

    @Override
    public void addRangeInto(double[] acc, int from, int to) {
        if (from >= to) {
            return;
        }
        enter();
        try {
            if (type == Type.FLOAT32) {
                float[] s = floatScratch();
                for (int r = from; r < to; r++) {
                    floats[r / rowsPerMap].get((r % rowsPerMap) * width, s, 0, width);
                    for (int j = 0; j < width; j++) {
                        acc[j] += s[j];
                    }
                }
            } else {
                double[] s = doubleScratch();
                for (int r = from; r < to; r++) {
                    doubles[r / rowsPerMap].get((r % rowsPerMap) * width, s, 0, width);
                    for (int j = 0; j < width; j++) {
                        acc[j] += s[j];
                    }
                }
            }
        } finally {
            exit();
        }
    }

    private float[] floatScratch() {
        float[] s = FLOATS.get();
        if (s.length < width) {
            s = new float[width];
            FLOATS.set(s);
        }
        return s;
    }

    private double[] doubleScratch() {
        double[] s = DOUBLES.get();
        if (s.length < width) {
            s = new double[width];
            DOUBLES.set(s);
        }
        return s;
    }

    // --------------------------------------------------------------------------------------------------------------------- closing

    /** Unmaps the file once no reader is inside (now, or when the last one leaves); later reads get {@link Closed}. */
    @Override
    public void close() {
        closing = true;
        if (readers.get() == 0) {
            unmap();
        }
    }

    /** As {@link #close}, then runs {@code then} once the file is unmapped (deleting it: a file still mapped cannot be on Windows). */
    public void closeThen(Runnable then) {
        afterUnmap.set(then);
        close();
        if (unmapped.get()) {
            runAfterUnmap();                                           // unmapped before (closed earlier): run it now
        }
    }

    /** True once the mappings are released. */
    public boolean unmapped() {
        return unmapped.get();
    }

    private void unmap() {
        if (!unmapped.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < maps.length; i++) {
            floats[i] = null;                                          // a read past the guard fails on null, never on freed memory
            doubles[i] = null;
            maps[i].release();
        }
        runAfterUnmap();
    }

    private void runAfterUnmap() {
        Runnable r = afterUnmap.getAndSet(null);                       // at most once, whoever gets here first
        if (r != null) {
            r.run();
        }
    }
}
