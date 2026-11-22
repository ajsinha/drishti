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

import com.ash.tools.files.TempFolder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one place the server makes temporary files and folders (the folder itself is Drishti Tools' {@code TempFolder}; this class adds the server's policy: the configured base, the JVM temp-folder warning and the health report). The server keeps a per-process folder, {@code drishti-<pid>}, under the configured temp base
 * ({@code drishti.tmp-dir} / {@code DRISHTI_TMP_DIR}, default {@code <data dir>/tmp/server}). {@link #start} makes it, lists (never removes) the folders of dead
 * processes, removes stale native-library copies from the base ({@link JvmTempDir}), sets the {@code java.io.tmpdir} property to the folder and removes the
 * folder on a clean shutdown.
 *
 * <p>Setting the property reaches only code that reads it at runtime ({@code System.getProperty}: sqlite-jdbc, snappy, Drishti's own files). It does not
 * move {@code Files.createTempFile} or {@code File.createTempFile}, which since JDK 17 use the value the JVM started with: DuckDB, RocksDB and CatBoost unpack
 * their native code with those, into the system temp folder unless Java was started with {@code -Djava.io.tmpdir=} (the launchers pass the base). The server
 * warns at start, in the log and on Admin → Health, when it was not. An architecture test forbids {@code Files.createTempFile},
 * {@code Files.createTempDirectory} and {@code File.createTempFile} anywhere else in main code.
 */
public final class TempFiles {

    /** Names a folder as one of ours (a stale folder is listed only if it holds this marker and its process is gone). */
    static final String MARKER = "drishti-temp-folder.txt";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TempFiles.class);
    private static final String PREFIX = "drishti-";
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static volatile TempFolder folder;
    private static volatile String startWarning;

    private TempFiles() {}

    /**
     * The base when {@link #start} did not run (unit tests, a library used outside the server): the configured base when one
     * is set, else the JVM's temp folder, so nothing is made under a working directory that is not the server's.
     */
    static String libraryBase() {
        String p = System.getProperty("drishti.tmp-dir");
        if (p == null || p.isBlank()) {
            p = System.getenv("DRISHTI_TMP_DIR");
        }
        return p == null || p.isBlank() ? System.getProperty("java.io.tmpdir", "/tmp") : com.ash.drishti.api.Roots.anchored(p.strip()).toString();
    }

    /**
     * The configured base: the {@code drishti.tmp-dir} system property, then {@code DRISHTI_TMP_DIR}, then {@code <data dir>/tmp/server}
     * ({@link com.ash.drishti.api.DataDir}: never the system temp folder, which may be a small in-memory file system).
     */
    public static String configuredBase() {
        String p = System.getProperty("drishti.tmp-dir");
        if (p == null || p.isBlank()) {
            p = System.getenv("DRISHTI_TMP_DIR");
        }
        return p == null || p.isBlank() ? com.ash.drishti.api.DataDir.under("tmp/server") : com.ash.drishti.api.Roots.anchored(p.strip()).toString();
    }

    /**
     * Starts the process folder under {@code baseDir} (null: the configured base), removes stale native-library copies from the base, warns when
     * {@code -Djava.io.tmpdir=} was not passed, sets the {@code java.io.tmpdir} property and registers the clean-up.
     * Call it first in {@code main}, before any library that unpacks native code is loaded. Calling it again returns the same folder.
     */
    public static Path start(String baseDir) {
        LOCK.lock();
        try {
            if (folder != null && folder.made()) {
                return folder.root();
            }
            JvmTempDir.startup();                                    // loads it, so it records java.io.tmpdir before it is set below
            Path b = Path.of(baseDir == null || baseDir.isBlank() ? configuredBase() : baseDir).toAbsolutePath();
            Path dir = make(b);
            int swept = JvmTempDir.sweep(b);
            if (swept > 0) {
                LOG.info("removed {} stale native-library copies left in {} by processes that did not stop cleanly", swept, b);
            }
            startWarning = JvmTempDir.warning(b);
            if (startWarning != null) {
                LOG.warn(startWarning);                               // before logging is set up: the console; the server repeats it once it is
            }
            System.setProperty("java.io.tmpdir", dir.toString());   // reaches System.getProperty readers only (see the class comment)
            return dir;
        } finally {
            LOCK.unlock();
        }
    }

    /** The warning {@link #start} logged when {@code -Djava.io.tmpdir=} was not passed to Java, or null (for the server's log once logging is set up). */
    public static String startWarning() {
        return startWarning;
    }

    /** The process folder; made (without changing {@code java.io.tmpdir}) the first time something needs a temporary file, when {@link #start} did not run. */
    public static Path root() {
        TempFolder f = folder;
        if (f != null) {
            return f.root();
        }
        LOCK.lock();
        try {
            if (folder == null) {
                make(Path.of(libraryBase()).toAbsolutePath());
            }
            return folder.root();
        } finally {
            LOCK.unlock();
        }
    }

    private static Path make(Path base) {
        folder = TempFolder.of(base, PREFIX, MARKER);
        return folder.root();                                        // makes it, with the clean-up on a clean stop
    }

    /** Removes the process folder (the shutdown hook; also callable by a test). */
    public static void cleanUp() {
        TempFolder f = folder;
        if (f != null) {
            f.cleanUp();
        }
    }

    /** A new empty file in {@code dir}, for a write that is then moved over its target (same folder, so the move is atomic). */
    public static Path createFile(Path dir, String prefix, String suffix) throws IOException {
        return TempFolder.createFile(dir, prefix, suffix);
    }

    /** A new empty file in the process folder. */
    public static Path createFile(String prefix, String suffix) throws IOException {
        return TempFolder.createFile(root(), prefix, suffix);
    }

    /** A new folder in {@code parent}. */
    public static Path createDir(Path parent, String prefix) throws IOException {
        return TempFolder.createDir(parent, prefix);
    }

    /** A new folder in the process folder. */
    public static Path createDir(String prefix) throws IOException {
        return TempFolder.createDir(root(), prefix);
    }

    /** A named subfolder of the process folder (made if absent), for a library's spill space. */
    public static Path subDir(String name) {
        root();
        return folder.subDir(name);
    }

    /** What the health page shows: where the folder is and how much it holds. */
    public static Map<String, Object> info() {
        TempFolder f = folder;
        Map<String, Object> jvm = JvmTempDir.info(Path.of(f != null ? f.base().toString() : configuredBase()));
        if (f == null || !f.made()) {
            return with(Map.of("base", f != null ? f.base().toString() : configuredBase(), "path", "", "bytes", 0L, "files", 0L), jvm);
        }
        long[] size = f.usage();
        return with(Map.of("base", f.base().toString(), "path", f.root().toString(), "bytes", size[0], "files", size[1]), jvm);
    }

    private static Map<String, Object> with(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(a);
        out.putAll(b);
        return out;
    }

    /** The process folders under the base that are left over from dead processes (for a test and the health page). */
    public static List<String> staleFolders() {
        TempFolder f = folder;
        return f == null ? List.of() : f.staleFolders().stream().map(Path::toString).toList();
    }
}
