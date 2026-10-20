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
package com.ash.drishti.iceberg.catalog;

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.meta.TableUpdate;
import java.io.IOException;
import java.util.Optional;

/**
 * How one table's metadata is found and changed, per catalog: a path-based table's {@code version-hint.text}, a
 * metadata file named directly, a REST catalog's {@code loadTable} and {@code commitTable}. Thread-safe.
 */
public interface TableOps {

    /** A version of the table's metadata, where it was read from, and (REST) the answer's ETag. */
    record Loaded(TableMetadata metadata, String location, String etag) {}

    /** The table's current metadata. */
    Loaded load() throws IOException;

    /** The current metadata if it changed since {@code current}, else empty (one small read when unchanged). */
    Optional<Loaded> refresh(Loaded current) throws IOException;

    /**
     * Commits {@code update} on top of {@code base} as one atomic step.
     *
     * @return the new current version
     * @throws CommitConflict when another writer committed first (the caller re-reads, re-applies, retries)
     */
    Loaded commit(Loaded base, TableUpdate update) throws IOException;

    /** The files of this table (a REST catalog may vend the table its own S3 credentials). */
    FileAccess io(Loaded current);

    /** Whether commits are safe with several writers (false: an S3 store without conditional writes). */
    default boolean exclusiveCommits() {
        return true;
    }

    /** For messages: which table this is. */
    String describe();
}
