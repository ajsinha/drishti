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
package com.ash.drishti.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * The one place the server makes temporary files and folders. The server keeps a per-process folder, {@code drishti-<pid>}, under the configured temp base
 * ({@code drishti.tmp-dir} / {@code DRISHTI_TMP_DIR}, default the system temp folder). {@link #start} makes it, removes the folders of dead processes left by a
 * crash, points {@code java.io.tmpdir} at it (so libraries that unpack native code or spill to disk, DuckDB, sqlite, snappy, land in it) and removes it on a
 * clean shutdown. An architecture test forbids {@code Files.createTempFile}, {@code Files.createTempDirectory} and {@code File.createTempFile} anywhere else in
 * main code.
 */
public final class TempFiles {

    /** Names a folder as one of ours (a stale folder is removed only if it holds this marker and its process is gone). */
    static final String MARKER = "drishti-temp-folder.txt";
    static final String LEGACY_MARKER = ".drishti-tmp";

    private static final String PREFIX = "drishti-";
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static volatile Path root;
    private static volatile Path base;

    private TempFiles() {}

    /** The configured base: the {@code drishti.tmp-dir} system property, then {@code DRISHTI_TMP_DIR}, then the system temp folder. */
    public static String configuredBase() {
        String p = System.getProperty("drishti.tmp-dir");
        if (p == null || p.isBlank()) {
            p = System.getenv("DRISHTI_TMP_DIR");
        }
        return p == null || p.isBlank() ? System.getProperty("java.io.tmpdir", "/tmp") : p;
    }

    /**
     * Starts the process folder under {@code baseDir} (null: the configured base), removes stale ones, sets {@code java.io.tmpdir} and registers the clean-up.
     * Call it first in {@code main}, before any library that unpacks native code is loaded. Calling it again returns the same folder.
     */
    public static Path start(String baseDir) {
        LOCK.lock();
        try {
            if (root != null) {
                return root;
            }
            Path b = Path.of(baseDir == null || baseDir.isBlank() ? configuredBase() : baseDir).toAbsolutePath();
            Path dir = make(b);
            System.setProperty("java.io.tmpdir", dir.toString());
            Runtime.getRuntime().addShutdownHook(new Thread(TempFiles::cleanUp, "drishti-tmp-cleanup"));
            return dir;
        } finally {
            LOCK.unlock();
        }
    }

    /** The process folder; made (without changing {@code java.io.tmpdir}) the first time something needs a temporary file, when {@link #start} did not run. */
    public static Path root() {
        Path r = root;
        if (r != null) {
            return r;
        }
        LOCK.lock();
        try {
            if (root == null) {
                make(Path.of(configuredBase()).toAbsolutePath());
                Runtime.getRuntime().addShutdownHook(new Thread(TempFiles::cleanUp, "drishti-tmp-cleanup"));
            }
            return root;
        } finally {
            LOCK.unlock();
        }
    }

    private static Path make(Path b) {
        try {
            Files.createDirectories(b);
            // removeStale(b); disabled 2026-10-17: removing other processes' folders at start is not safe on every machine (see removeStale)
            Path dir = b.resolve(PREFIX + ProcessHandle.current().pid());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(MARKER), "pid " + ProcessHandle.current().pid() + "\n");
            base = b;
            root = dir;
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot make the temp folder under " + b + ": " + e.getMessage(), e);
        }
    }

    /**
     * Disabled (2026-10-17): removing other processes' folders at start is not safe on every machine (a reused process id, or a
     * link inside a folder, can lead a delete to files that are not ours). It is no longer called; left-over folders are only
     * listed ({@link #staleFolders()}, Admin, Health) for a person to remove by hand. Kept for a test that runs it on purpose.
     */
    static int removeStale(Path b) {
        int removed = 0;
        try (Stream<Path> s = Files.list(b)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (!n.startsWith(PREFIX) || !n.substring(PREFIX.length()).matches("\\d{1,10}") || !(Files.isRegularFile(p.resolve(MARKER)) || Files.isRegularFile(p.resolve(LEGACY_MARKER)))) {
                    continue;
                }
                long pid = Long.parseLong(n.substring(PREFIX.length()));
                if (pid != ProcessHandle.current().pid() && ProcessHandle.of(pid).isEmpty()) {
                    deleteTree(p);
                    removed++;
                }
            }
        } catch (IOException e) {
            // a base that cannot be listed has nothing of ours to clean
        }
        return removed;
    }

    /** Removes the process folder (the shutdown hook; also callable by a test). */
    public static void cleanUp() {
        Path r = root;
        if (r != null) {
            deleteTree(r);
            root = null;
        }
    }

    /** A new empty file in {@code dir}, for a write that is then moved over its target (same folder, so the move is atomic). */
    public static Path createFile(Path dir, String prefix, String suffix) throws IOException {
        return Files.createTempFile(dir, prefix, suffix);
    }

    /** A new empty file in the process folder. */
    public static Path createFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(root(), prefix, suffix);
    }

    /** A new folder in {@code parent}. */
    public static Path createDir(Path parent, String prefix) throws IOException {
        return Files.createTempDirectory(parent, prefix);
    }

    /** A new folder in the process folder. */
    public static Path createDir(String prefix) throws IOException {
        return Files.createTempDirectory(root(), prefix);
    }

    /** A named subfolder of the process folder (made if absent), for a library's spill space. */
    public static Path subDir(String name) {
        try {
            return Files.createDirectories(root().resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the health page shows: where the folder is and how much it holds. */
    public static Map<String, Object> info() {
        Path r = root != null ? root : null;
        if (r == null) {
            return Map.of("base", configuredBase(), "path", "", "bytes", 0L, "files", 0L);
        }
        long[] size = {0, 0};
        try (Stream<Path> s = Files.walk(r)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                size[1]++;
                try {
                    size[0] += Files.size(f);
                } catch (IOException e) {
                    // vanished while counted
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // counted as far as it could be
        }
        return Map.of("base", base.toString(), "path", r.toString(), "bytes", size[0], "files", size[1]);
    }

    /** The process folders under the base that are left over from dead processes (for a test and the health page). */
    public static List<String> staleFolders() {
        Path b = base;
        if (b == null) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(b)) {
            return s.filter(p -> p.getFileName().toString().matches(PREFIX + "\\d{1,10}") && (Files.isRegularFile(p.resolve(MARKER)) || Files.isRegularFile(p.resolve(LEGACY_MARKER)))
                    && ProcessHandle.of(Long.parseLong(p.getFileName().toString().substring(PREFIX.length()))).isEmpty()).map(p -> p.toString()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void deleteTree(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    // a file still open elsewhere: the next start removes the folder as stale
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // already gone
        }
    }
}
