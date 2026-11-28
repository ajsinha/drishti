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

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The one way a written file takes the place of the previous one: {@code tmp} moved over {@code file} in one step
 * ({@code ATOMIC_MOVE}, {@code REPLACE_EXISTING}). On Windows the replace is refused while anything holds the old file open (a reader in
 * this process, a virus scanner, the search indexer): {@link AccessDeniedException} or a sharing violation. Such holders let go within
 * moments, so the move is tried again for up to five seconds before the last refusal is thrown. On Linux and macOS the first try
 * succeeds and nothing is waited for.
 */
public final class FileReplace {

    /** How long a refused replace is tried again, in all. */
    static final long PATIENCE_MS = 5_000;

    /** Only Windows refuses to replace a file another handle holds open; elsewhere a refusal is real and is thrown at once. */
    static final boolean RETRIES = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    private FileReplace() {
    }

    /** {@code tmp} moved over {@code file} atomically; {@link java.nio.file.AtomicMoveNotSupportedException} is thrown as it comes. */
    public static void atomically(Path tmp, Path file) throws IOException {
        try {
            Roots.guardWrite(file);          // a site file is never written into the release folder (Roots.guardWrite)
        } catch (RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        long until = System.nanoTime() + PATIENCE_MS * 1_000_000;
        long pause = 5;
        while (true) {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (FileSystemException e) {
                if (!RETRIES || !held(e) || System.nanoTime() + pause * 1_000_000 > until) {
                    throw e;
                }
            }
            try {
                Thread.sleep(pause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted while " + file + " was held open");
            }
            pause = Math.min(250, pause * 2);
        }
    }

    /** A refusal because the old file is held open (Windows), not a missing file, an unsupported move or a folder in the way. */
    static boolean held(FileSystemException e) {
        return e instanceof AccessDeniedException || e.getClass() == FileSystemException.class;
    }
}
