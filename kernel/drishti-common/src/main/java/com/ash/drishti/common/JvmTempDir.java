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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The JVM's own temp folder, as it was when the JVM started. Since JDK 17 {@code Files.createTempFile} and {@code File.createTempFile} read
 * {@code java.io.tmpdir} once, at JVM start, so setting the property later ({@link TempFiles#start}) does not move them: DuckDB (60 MB),
 * RocksDB and CatBoost unpack their native code with those calls and land in the system temp folder unless {@code -Djava.io.tmpdir=} is on the
 * command line. A JVM killed without a clean exit leaves that copy behind. This class tells whether the flag was passed (the server warns when
 * not) and removes stale native copies from the server's own temp base, never from anywhere else.
 */
public final class JvmTempDir {

    /** The value of {@code java.io.tmpdir} when this class was first loaded: {@link TempFiles#start} loads it before it sets the property. */
    private static final String STARTUP = System.getProperty("java.io.tmpdir", "");

    /** Native libraries that unpack themselves into the temp folder: DuckDB, RocksDB, CatBoost, jansi, sqlite-jdbc, snappy, lz4, zstd, netty. */
    private static final Pattern NATIVE = Pattern.compile(
            "(libduckdb_java|librocksdbjni|libcatboost|jansi-|libjansi|sqlite-|snappy-|liblz4-java|libzstd-jni|libnetty_).*\\.(so|dll|dylib|jnilib|lck)(\\.lck)?",
            Pattern.CASE_INSENSITIVE);

    /** A copy younger than this may still be on its way to being loaded by another process starting now: left for the next start. */
    static final Duration MIN_AGE = Duration.ofMinutes(10);

    private static volatile int removed;

    private JvmTempDir() {}

    /** {@code java.io.tmpdir} as the JVM started. */
    public static String startup() {
        return STARTUP;
    }

    /**
     * Makes the folder {@code java.io.tmpdir} names when it does not exist yet. The launchers pass {@code -Djava.io.tmpdir=<data>/tmp/server}
     * and an IDE run configuration cannot make the folder first: on a fresh clone the folder is missing, and DuckDB, RocksDB and CatBoost
     * then fail to unpack their native code (QA 2026-10-20, Windows: "java.io.tmpdir does not exist"). Called first thing in main.
     *
     * @return the folder made, or null when it existed or none was named
     */
    public static java.nio.file.Path ensureExists() {
        String dir = System.getProperty("java.io.tmpdir", "");
        if (dir.isBlank()) {
            return null;
        }
        java.nio.file.Path p = java.nio.file.Path.of(dir);
        if (java.nio.file.Files.isDirectory(p)) {
            return null;
        }
        try {
            return java.nio.file.Files.createDirectories(p);
        } catch (java.io.IOException | RuntimeException e) {
            System.err.println("java.io.tmpdir " + dir + " does not exist and cannot be made: " + e.getMessage());
            return null;
        }
    }

    /** Whether {@code -Djava.io.tmpdir=} reached this JVM: on its command line, or through {@code JAVA_TOOL_OPTIONS} / {@code JDK_JAVA_OPTIONS}. */
    public static boolean passed() {
        List<String> args;
        try {
            args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (RuntimeException | LinkageError e) {
            args = List.of();
        }
        return passed(args, System.getenv());
    }

    static boolean passed(List<String> inputArgs, Map<String, String> env) {
        if (inputArgs.stream().anyMatch(a -> a.startsWith("-Djava.io.tmpdir="))) {
            return true;
        }
        for (String v : new String[] {env.get("JAVA_TOOL_OPTIONS"), env.get("JDK_JAVA_OPTIONS"), env.get("_JAVA_OPTIONS")}) {
            if (v != null && v.contains("-Djava.io.tmpdir=")) {
                return true;
            }
        }
        return false;
    }

    /** The warning for the log and Admin → Health when the flag was not passed (null when it was): where the copies go and how to pass it. */
    static String warning(boolean passed, String startup, Path base) {
        if (passed) {
            return null;
        }
        String b = base.toAbsolutePath().toString();
        return "java.io.tmpdir was not set when Java started, so it is the system temp folder (" + startup + "): DuckDB, RocksDB and CatBoost unpack"
                + " their native libraries there (DuckDB 60 MB a start) and a server killed without a clean stop leaves them behind. Start Java with"
                + " -Djava.io.tmpdir=" + b + " (java -Djava.io.tmpdir=" + b + " -jar drishti-server-<version>-exec.jar), or set JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=" + b + ".";
    }

    /** {@link #warning} for this JVM. */
    public static String warning(Path base) {
        return warning(passed(), STARTUP, base);
    }

    /**
     * Removes the native-library copies left in {@code base} by processes that did not exit cleanly: regular files at its top level whose names a
     * library gives its copy, older than {@code minAge}. Nothing outside {@code base}, no folder, no link. A copy another process still holds is
     * harmless to unlink on Linux and macOS (its mapping stays); on Windows the delete fails and the file is skipped quietly.
     */
    static int sweep(Path base, Duration minAge, Instant now) {
        int n = 0;
        try (Stream<Path> s = Files.list(base)) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                if (!NATIVE.matcher(name).matches() || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(now.minus(minAge))) {
                        continue;
                    }
                    if (Files.deleteIfExists(p)) {
                        n++;
                    }
                } catch (IOException e) {
                    // held open by a live process (Windows), or gone already
                }
            }
        } catch (IOException e) {
            // no base yet: nothing to remove
        }
        return n;
    }

    /** {@link #sweep} at start: remembered for the health page. */
    static int sweep(Path base) {
        removed = sweep(base, MIN_AGE, Instant.now());
        return removed;
    }

    /** Whether a name is one a library gives its unpacked native copy (for a test). */
    static boolean isNativeCopy(String name) {
        return NATIVE.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    /** What the health page shows. */
    static Map<String, Object> info(Path base) {
        boolean p = passed();
        String w = base == null ? null : warning(p, STARTUP, base);
        return Map.of("jvmTmpdir", STARTUP, "jvmTmpdirPassed", p, "jvmTmpdirWarning", w == null ? "" : w, "nativeCopiesRemoved", removed);
    }
}
