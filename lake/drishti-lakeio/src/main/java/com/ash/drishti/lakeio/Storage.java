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
package com.ash.drishti.lakeio;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Where a lake's files are: sorted listings of one directory, a file's status, positional reads and, for the writers
 * (the Iceberg loader and maintenance), whole-file writes, an exclusive create and deletes. Implementations are
 * thread-safe and shared by every read of an engine. Paths are the canonical URI strings of {@link #qualify(String)}.
 */
public interface Storage {

    /** The canonical form of {@code path}: what child paths are joined onto and what listings return. */
    String qualify(String path) throws IOException;

    /**
     * The files of {@code path}'s directory whose names are equal to or after {@code path}'s last segment, sorted by
     * name (Delta Kernel finds commits and checkpoints this way; Iceberg's path-based tables their metadata versions).
     *
     * @throws FileNotFoundException if the directory does not exist
     */
    List<FileEntry> listFrom(String path) throws IOException;

    /**
     * One file's size and modification time.
     *
     * @throws FileNotFoundException if there is no such file
     */
    FileEntry status(String path) throws IOException;

    /**
     * Opens {@code path} for positional reads.
     *
     * @param knownLength the length when the caller knows it (from the table's log or manifests), or a negative number
     */
    RangeReader open(String path, long knownLength) throws IOException;

    /** The names of the sub-directories of {@code path}, sorted; empty when it does not exist. */
    List<String> directories(String path) throws IOException;

    /** Whether {@code path} is an existing directory (for an object store: a prefix with objects under it). */
    boolean isDirectory(String path) throws IOException;

    /** The whole file (metadata JSON, a manifest, a version hint). */
    default byte[] readAll(String path) throws IOException {
        try (RangeReader r = open(path, -1)) {
            long n = r.length();
            if (n > Integer.MAX_VALUE - 16) {
                throw new IOException("too large to read whole: " + path + " (" + n + " bytes)");
            }
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate((int) n);
            r.readFully(0, b);
            return b.array();
        }
    }

    /**
     * A new file at {@code path}, replacing one that is there, visible under its name once the stream is closed (an
     * object store) or as it is written (local disk: callers write files no reader has been told about yet).
     */
    default OutputStream create(String path) throws IOException {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not write");
    }

    /** Writes {@code content} as the whole of {@code path}, replacing it (a version hint). */
    default void put(String path, byte[] content) throws IOException {
        try (OutputStream out = create(path)) {
            out.write(content);
        }
    }

    /**
     * Creates {@code path} with {@code content} only if no file has that name, as one atomic step: of two writers
     * creating the same name, exactly one succeeds and a reader never sees a partly written file. Local disk does it
     * with a hard link of a finished temporary file; S3 with a conditional PUT ({@code If-None-Match: *}).
     *
     * @return true when this call created it, false when the name was taken
     */
    default boolean createIfAbsent(String path, byte[] content) throws IOException {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not write");
    }

    /**
     * Whether {@link #createIfAbsent} really is exclusive here: always on local disk; for an S3-compatible store, what
     * a probe found (a store that ignores conditional writes would let two commits both succeed).
     */
    default boolean exclusiveCreates() throws IOException {
        return false;
    }

    /** Deletes {@code path}; false when there was no such file. */
    default boolean delete(String path) throws IOException {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not write");
    }
}
