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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * A built Parquet file put in place of the previous one. The new file is written under a name of its own ({@link #temp}), so two builds
 * of one partition never write the same temporary file, then moved over the old one in one step. On Windows a file that anything holds
 * open (a query still reading the previous version, the antivirus scanning it, the search indexer) cannot be replaced: the move fails
 * with a sharing violation, which used to fail the report with DRS-9027 (QA 2026-10-20, the product owner's Windows machine). The move
 * is now tried again for a few seconds, as such holders let go within moments; past that the answer says plainly what held it.
 */
final class FileSwap {

    /** Tries and the pause before each retry: about five seconds in all. */
    static final int TRIES = 20;
    static final long PAUSE_MS = 250;

    /** One move, replaceable in tests. */
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    static final Mover ATOMIC = (from, to) -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

    private FileSwap() {
    }

    /**
     * A temporary file beside {@code file}, unique to this build (removed on failure by {@link #replace}). Temporary files of the same
     * partition older than an hour (a build the process was stopped in the middle of) are removed first.
     */
    static Path temp(Path file) {
        String prefix = file.getFileName() + ".";
        try (var old = Files.newDirectoryStream(file.getParent(), prefix + "*.tmp")) {
            long hourAgo = System.currentTimeMillis() - 3_600_000L;
            for (Path p : old) {
                if (Files.getLastModifiedTime(p).toMillis() < hourAgo) {
                    deleteQuietly(p);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // no folder yet, or nothing to clean: the build goes on
        }
        return file.resolveSibling(prefix + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
    }

    /** Moves {@code tmp} over {@code file}, retrying while the old file is held open. */
    static void replace(Path tmp, Path file) {
        replace(tmp, file, ATOMIC, TRIES, PAUSE_MS);
    }

    static void replace(Path tmp, Path file, Mover mover, int tries, long pauseMs) {
        FileSystemException last = null;
        for (int i = 0; i < tries; i++) {
            try {
                mover.move(tmp, file);
                return;
            } catch (NoSuchFileException e) {
                throw new DrishtiException(ErrorCode.QUERY_ENGINE, "the engine failed: the built file " + tmp.getFileName() + " is gone");
            } catch (AccessDeniedException e) {
                last = e;                                     // Windows: the old file is open somewhere; it is let go within moments
            } catch (FileSystemException e) {
                last = e;                                     // the same on other file systems (a sharing violation, a busy file)
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw new DrishtiException(ErrorCode.QUERY_ENGINE, "the engine failed: " + e.getMessage());
            }
            if (i + 1 < tries) {
                try {
                    Thread.sleep(pauseMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        deleteQuietly(tmp);
        throw new DrishtiException(ErrorCode.QUERY_ENGINE, "the engine failed: " + file + " stayed in use for "
                + (tries * pauseMs / 1000) + " s (another query reading it, or a virus scanner or indexer holding it; on Windows, exclude "
                + "the data folder from real-time scanning): " + (last == null ? "" : last.getClass().getSimpleName()));
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // a stray temporary file is removed by the next build of the partition
        }
    }
}
