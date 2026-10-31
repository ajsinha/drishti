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
package com.ash.drishti.plugin.delta;

import com.ash.drishti.api.MappedIdFile;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A date's id map in a file, read through a memory map ({@link MappedIdFile}): the ids sorted by their UTF-8 bytes, an offset per id and
 * the file index of each. A lookup is a binary search over the map; the heap holds nothing per id, and the operating system keeps the
 * pages that are used. Written once per table version and date (the folder is named by the version), so it is never stale and outlives a restart.
 * Since 2.9.7 the mapping is released when the map leaves the connector's cache or its file is pruned (never left to the collector).
 */
final class MappedIdMap implements DeltaTable.IdMap {

    /** Mapped again when its mapping was released for being idle ({@code id-map-disk.idle-release}, 2.9.7). */
    private final MappedIdFile.Handle f;

    private MappedIdMap(MappedIdFile f) {
        this.f = new MappedIdFile.Handle(f);
    }

    /** The map in {@code file}, or null when there is none (or it cannot be read: it is then written again). */
    static MappedIdMap open(Path file) {
        MappedIdFile f = MappedIdFile.open(file);
        return f == null ? null : new MappedIdMap(f);
    }

    /** Writes {@code m} to {@code file} (through a temporary file, so a reader never sees half of it) and maps it. */
    static MappedIdMap write(DeltaTable.IdMap m, Path file) throws IOException {
        return new MappedIdMap(MappedIdFile.write(m.size(), m::id, m::file, file));
    }

    @Override
    public int size() {
        return f.size();
    }

    @Override
    public String id(int i) {
        return f.id(i);
    }

    @Override
    public int file(String id) {
        return f.file(id);
    }

    @Override
    public long bytes() {
        return 256;                                                    // the map is outside the heap
    }

    /** The file's size (the address space and page cache it holds). */
    long fileBytes() {
        return f.fileBytes();
    }

    /** True once released (idle, pruned, trimmed or expired): a cache holding it maps the file again or reads it again. */
    boolean closed() {
        return f.closed();
    }

    /** Releases the mapping (once its readers are done). */
    void close() {
        f.close();
    }
}
