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
package com.ash.drishti.deltalake;

import io.delta.kernel.utils.FileStatus;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Where files are read from, as Delta Kernel needs them: sorted listings of one directory, a file's status and
 * positional reads. Implementations are thread-safe and shared by every read of an engine. Paths are the canonical
 * URI strings of {@link #qualify(String)}.
 */
public interface Storage {

    /** The canonical form of {@code path}: what Kernel joins child paths onto and what listings return. */
    String qualify(String path) throws IOException;

    /**
     * The files of {@code path}'s directory whose names are equal to or after {@code path}'s last segment, sorted by
     * name (Kernel finds commits and checkpoints this way).
     *
     * @throws FileNotFoundException if the directory does not exist
     */
    List<FileStatus> listFrom(String path) throws IOException;

    /**
     * One file's size and modification time.
     *
     * @throws FileNotFoundException if there is no such file
     */
    FileStatus status(String path) throws IOException;

    /**
     * Opens {@code path} for positional reads.
     *
     * @param knownLength the length when the caller knows it (from the Delta log), or a negative number
     */
    RangeReader open(String path, long knownLength) throws IOException;

    /** The names of the sub-directories of {@code path}, sorted; empty when it does not exist. */
    List<String> directories(String path) throws IOException;

    /** Whether {@code path} is an existing directory (for an object store: a prefix with objects under it). */
    boolean isDirectory(String path) throws IOException;
}
