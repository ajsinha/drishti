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
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Rows of float64 values written to a local file as a load reads them, in the lake's order, so a date of any size is read without
 * holding its vectors on the heap (VAR_GUIDE.md section 26). When the rows are all in, {@link #finish} maps the file for the build's
 * reads (the node aggregates in float64, the hot file's float32 rows); {@link #close} unmaps and deletes it. Not thread-safe: the caller
 * serialises {@link #add} (the load's builder holds a lock).
 */
public final class RowSpill implements AutoCloseable {

    private static final int BUFFER = 4 << 20;

    private final Path file;
    private final int width;
    private final long budgetBytes;
    private final long bytesPerRow;
    private FileChannel ch;
    private ByteBuffer buf;
    private int rows;
    private MappedMatrix mapped;

    /**
     * @param file an empty file of the caller's (made by the server's temporary-file service, under its data folder)
     * @param width every row's length
     * @param budgetBytes the most bytes of vectors the date may hold, measured as {@code bytesPerRow} per row
     * @param bytesPerRow what a row costs against the budget (4 x width for the float32 hot copy)
     */
    public RowSpill(Path file, int width, long budgetBytes, long bytesPerRow) {
        this.file = file;
        this.width = width;
        this.budgetBytes = budgetBytes;
        this.bytesPerRow = bytesPerRow;
        try {
            this.ch = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the load's rows to " + file + ": " + e.getMessage(), e);
        }
        this.buf = ByteBuffer.allocateDirect(Math.max(BUFFER, width * Double.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
    }

    public int width() {
        return width;
    }

    public int rows() {
        return rows;
    }

    public Path file() {
        return file;
    }

    /** Adds a row ({@code values[0..width)}; a shorter vector is padded with NaN); returns its ordinal. */
    public int add(double[] values, int length) {
        if ((long) (rows + 1) * bytesPerRow > budgetBytes) {
            throw new VectorStore.BudgetExceeded(rows + 1, width, budgetBytes, bytesPerRow);
        }
        if (buf.remaining() < width * Double.BYTES) {
            flush();
        }
        int n = Math.max(0, Math.min(length, width));
        for (int j = 0; j < n; j++) {
            buf.putDouble(values[j]);
        }
        for (int j = n; j < width; j++) {
            buf.putDouble(Double.NaN);
        }
        return rows++;
    }

    private void flush() {
        buf.flip();
        try {
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the load's rows to " + file + ": " + e.getMessage(), e);
        }
        buf.clear();
    }

    /** Writes what is buffered, closes the file and maps it: the rows in the order they were added. */
    public MappedMatrix finish() {
        flush();
        try {
            ch.close();
            ch = null;
            buf = null;
            mapped = MappedMatrix.open(file, 0, rows, width, MappedMatrix.Type.FLOAT64, 0);
            return mapped;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot map the load's rows in " + file + ": " + e.getMessage(), e);
        }
    }

    /** Unmaps and deletes the file (at the end of the build, or when the load fails). */
    @Override
    public void close() {
        try {
            if (ch != null) {
                ch.close();
                ch = null;
            }
        } catch (IOException e) {
            // closing a file being abandoned
        }
        MappedMatrix m = mapped;
        if (m != null) {
            m.closeThen(this::delete);
        } else {
            delete();
        }
    }

    private void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // the temporary folder is removed at shutdown anyway
        }
    }
}
